package com.grdp.studio.softwareintegration.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationCreateRunRequest;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationRunDetailResponse;
import com.grdp.studio.softwareintegration.entity.EclipseBatchEntity;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStatus;
import com.grdp.studio.softwareintegration.mapper.EclipseBatchMapper;
import com.grdp.studio.softwareintegration.support.EclipseBatchParameters;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationRunExceptionHandler.RunException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
@Service
public class EclipseBatchService {
    private final EclipseBatchMapper batches;private final SoftwareIntegrationRunService runs;private final ObjectMapper mapper;
    public EclipseBatchService(EclipseBatchMapper batches,SoftwareIntegrationRunService runs,ObjectMapper mapper){this.batches=batches;this.runs=runs;this.mapper=mapper;}
    @Transactional public Batch create(long versionId,JsonNode request){
        if(!EclipseBatchParameters.valid(request))throw error(HttpStatus.BAD_REQUEST,"批量方案需要2–8个有效、独立的同基线方案，名称不得重复");
        if(batches.lockActiveProjectForVersion(versionId)==null)throw error(HttpStatus.NOT_FOUND,"模型项目不可用");
        var prior=batches.selectOne(new LambdaQueryWrapper<EclipseBatchEntity>().eq(EclipseBatchEntity::getModelVersionId,versionId).eq(EclipseBatchEntity::getRequestKey,request.path("requestKey").asText()));
        if(prior!=null){if(!EclipseBatchParameters.sameContent(mapper.readTree(prior.getRequestJson()),request))throw error(HttpStatus.CONFLICT,"同一提交标识已用于不同批量方案");return detail(prior);}
        var baseline=runs.get(request.path("baselineRunId").asLong());
        if(baseline.modelVersionId()!=versionId||!"eclipse".equals(baseline.runType())||!"SUCCEEDED".equals(baseline.status())||!"VALID_FULL".equals(baseline.resultContract())||baseline.parameters()!=null&&!baseline.parameters().isNull()||baseline.result()==null||baseline.result().isNull()||baseline.resultExpired()||!"eclipse_100".equals(baseline.result().path("modelKind").asText()))throw error(HttpStatus.CONFLICT,"请选择同版本、结果未过期的完整无参数E100基线");
        var members=mapper.createArrayNode();
        // Existing createQueued joins this transaction. A rejected later case rolls back every child and the collection.
        for(var item:request.path("cases")){
            var child=new SoftwareIntegrationCreateRunRequest();child.setRunType("eclipse");child.setStudy(null);child.setParameters(item.path("parameters"));
            var created=runs.create(versionId,child);var member=mapper.createObjectNode();member.put("name",item.path("name").asText());member.put("runId",created.id());members.add(member);
        }
        var entity=new EclipseBatchEntity();entity.setModelVersionId(versionId);entity.setBaselineRunId(baseline.id());entity.setRequestKey(request.path("requestKey").asText());entity.setName(request.path("name").asText());entity.setRequestJson(mapper.writeValueAsString(request));entity.setMembersJson(mapper.writeValueAsString(members));entity.setCreatedAt(LocalDateTime.now());batches.insert(entity);
        return detail(entity);
    }
    public Batch get(long id){var batch=batches.selectById(id);if(batch==null)throw error(HttpStatus.NOT_FOUND,"批量方案不存在");return detail(batch);}
    public List<Summary> list(long versionId){
        // Reuse the existing model/project availability check even when the version has no batches.
        runs.list(versionId,1);
        return batches.selectList(new LambdaQueryWrapper<EclipseBatchEntity>().eq(EclipseBatchEntity::getModelVersionId,versionId).orderByDesc(EclipseBatchEntity::getId).last("LIMIT 50")).stream().map(this::summary).toList();
    }
    public Batch cancel(long id){
        var before=get(id);for(var member:before.members())if(member.run().cancellable())try{runs.cancel(member.run().id());}catch(RunException e){if(e.status()!=HttpStatus.CONFLICT)throw e;}
        return get(id);
    }
    private Batch detail(EclipseBatchEntity entity){
        var baseline=runs.get(entity.getBaselineRunId());var members=new ArrayList<Member>();
        for(var item:mapper.readTree(entity.getMembersJson())){var run=runs.get(item.path("runId").asLong());if(!run.modelVersionId().equals(entity.getModelVersionId()))throw error(HttpStatus.CONFLICT,"批量方案子运行的模型版本不一致");members.add(new Member(item.path("name").asText(),run));}
        String status=aggregate(members.stream().map(m->m.run().status()+":"+m.run().resultContract()).toList());
        return new Batch(entity.getId(),entity.getModelVersionId(),entity.getBaselineRunId(),entity.getName(),status,entity.getCreatedAt(),baseline,members);
    }
    private Summary summary(EclipseBatchEntity entity){
        var states=new ArrayList<String>();
        for(var item:mapper.readTree(entity.getMembersJson())){
            String state=batches.runState(item.path("runId").asLong(),entity.getModelVersionId());
            if(state==null)throw error(HttpStatus.CONFLICT,"批量方案子运行不可用");states.add(state);
        }
        return new Summary(entity.getId(),entity.getModelVersionId(),entity.getBaselineRunId(),entity.getName(),aggregate(states),entity.getCreatedAt(),states.size());
    }
    private static String aggregate(List<String> states){
        var statuses=states.stream().map(s->SoftwareIntegrationRunStatus.valueOf(s.split(":",2)[0])).toList();
        boolean pending=statuses.stream().anyMatch(s->!s.isTerminal()),active=statuses.stream().anyMatch(SoftwareIntegrationRunStatus::isActive);
        long success=states.stream().filter(s->s.equals("SUCCEEDED:VALID_FULL")).count();
        return pending?(active?"RUNNING":"QUEUED"):success==states.size()?"SUCCEEDED":success>0?"PARTIAL_SUCCEEDED":statuses.stream().allMatch(s->s==SoftwareIntegrationRunStatus.CANCELLED)?"CANCELLED":"FAILED";
    }
    public record Summary(Long id,Long modelVersionId,Long baselineRunId,String name,String status,LocalDateTime createdAt,int caseCount){}
    private static RunException error(HttpStatus status,String message){return new RunException(status,message);}
    public record Member(String name,SoftwareIntegrationRunDetailResponse run){}
    public record Batch(Long id,Long modelVersionId,Long baselineRunId,String name,String status,LocalDateTime createdAt,SoftwareIntegrationRunDetailResponse baseline,List<Member> members){}
}
