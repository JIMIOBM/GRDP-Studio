package com.grdp.studio.softwareintegration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.grdp.studio.softwareintegration.entity.EclipseHistoryEntity;
import com.grdp.studio.softwareintegration.mapper.EclipseHistoryMapper;
import com.grdp.studio.softwareintegration.mapper.EclipseBatchMapper;
import com.grdp.studio.softwareintegration.support.EclipseHistoryCsv;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationRunExceptionHandler.RunException;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationRunDetailResponse;
import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EclipseHistoryService {
    private final EclipseHistoryMapper history;
    private final EclipseBatchMapper projects;
    private final SoftwareIntegrationRunService runs;
    public EclipseHistoryService(EclipseHistoryMapper history,EclipseBatchMapper projects,SoftwareIntegrationRunService runs){this.history=history;this.projects=projects;this.runs=runs;}

    @Transactional public Dataset create(long versionId,JsonNode request) {
        if(request==null||!request.isObject()||!request.properties().stream().map(java.util.Map.Entry::getKey).collect(Collectors.toSet()).equals(Set.of("name","baselineRunId","sourceFile","csv"))
            ||!text(request.path("name"),100)||!text(request.path("sourceFile"),128)||!request.path("sourceFile").asText().toLowerCase(java.util.Locale.ROOT).endsWith(".csv")
            ||request.path("sourceFile").asText().matches(".*[/\\\\:].*")||!request.path("csv").isString()
            ||!request.path("baselineRunId").isIntegralNumber()||!request.path("baselineRunId").canConvertToLong()||request.path("baselineRunId").asLong()<=0)throw error(HttpStatus.BAD_REQUEST,"需要有效名称、CSV文件名、完整基线编号和UTF-8 CSV内容");
        var observations=parse(request.path("csv").asText());
        if(projects.lockActiveProjectForVersion(versionId)==null)throw error(HttpStatus.NOT_FOUND,"模型项目不可用");
        var baseline=runs.get(request.path("baselineRunId").asLong());requireRun(baseline,versionId);
        if(baseline.parameters()!=null&&!baseline.parameters().isNull())throw error(HttpStatus.CONFLICT,"请从同版本无参数完整基线导入实测");
        try{EclipseHistoryCsv.requireBaselineCoverage(observations,baseline.result().path("summary"));}catch(IllegalArgumentException e){throw error(HttpStatus.BAD_REQUEST,e.getMessage());}
        String sha=sha(request.path("csv").asText());
        var prior=history.selectOne(new LambdaQueryWrapper<EclipseHistoryEntity>().eq(EclipseHistoryEntity::getModelVersionId,versionId).eq(EclipseHistoryEntity::getSourceSha256,sha));
        if(prior!=null)return detail(prior);
        var entity=new EclipseHistoryEntity();entity.setModelVersionId(versionId);entity.setBaselineRunId(baseline.id());entity.setName(request.path("name").asText());entity.setSourceFile(request.path("sourceFile").asText());entity.setSourceSha256(sha);entity.setSourceCsv(request.path("csv").asText());entity.setObservationCount(observations.size());entity.setCreatedAt(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));history.insert(entity);
        return detail(entity);
    }
    public List<Summary> list(long versionId) {
        runs.list(versionId,1);
        return history.selectList(new LambdaQueryWrapper<EclipseHistoryEntity>().select(EclipseHistoryEntity::getId,EclipseHistoryEntity::getModelVersionId,EclipseHistoryEntity::getBaselineRunId,EclipseHistoryEntity::getName,EclipseHistoryEntity::getSourceFile,EclipseHistoryEntity::getSourceSha256,EclipseHistoryEntity::getObservationCount,EclipseHistoryEntity::getCreatedAt)
                .eq(EclipseHistoryEntity::getModelVersionId,versionId).orderByDesc(EclipseHistoryEntity::getId).last("LIMIT 50")).stream().map(EclipseHistoryService::summary).toList();
    }
    public Dataset get(long id){var entity=find(id);runs.list(entity.getModelVersionId(),1);return detail(entity);}
    public Evaluation compare(long id,long runId) {
        var entity=find(id);var run=runs.get(runId);requireRun(run,entity.getModelVersionId());var dataset=detail(entity);
        try {return new Evaluation(summary(entity),runId,EclipseHistoryCsv.compare(dataset.observations(),run.result().path("summary")));}
        catch(IllegalArgumentException e){throw error(HttpStatus.CONFLICT,e.getMessage());}
    }
    private EclipseHistoryEntity find(long id){var entity=history.selectById(id);if(entity==null)throw error(HttpStatus.NOT_FOUND,"实测数据记录不存在");return entity;}
    private Dataset detail(EclipseHistoryEntity entity) {
        if(!sha(entity.getSourceCsv()).equals(entity.getSourceSha256()))throw error(HttpStatus.CONFLICT,"观察源文件校验不一致");
        var observations=parse(entity.getSourceCsv());if(observations.size()!=entity.getObservationCount())throw error(HttpStatus.CONFLICT,"观察源点数不一致");
        return new Dataset(summary(entity),entity.getSourceCsv(),observations);
    }
    private static Summary summary(EclipseHistoryEntity e){return new Summary(e.getId(),e.getModelVersionId(),e.getBaselineRunId(),e.getName(),e.getSourceFile(),e.getSourceSha256(),e.getObservationCount(),e.getCreatedAt());}
    private static List<EclipseHistoryCsv.Observation> parse(String csv){try{return EclipseHistoryCsv.parse(csv);}catch(IllegalArgumentException e){throw error(HttpStatus.BAD_REQUEST,e.getMessage());}}
    private static boolean text(JsonNode n,int max){return n.isString()&&!n.asText().isBlank()&&n.asText().equals(n.asText().trim())&&n.asText().length()<=max&&n.asText().codePoints().noneMatch(Character::isISOControl);}
    private static void requireRun(SoftwareIntegrationRunDetailResponse run,long versionId){
        if(run.modelVersionId()!=versionId||!"eclipse".equals(run.runType())||!"SUCCEEDED".equals(run.status())||!"VALID_FULL".equals(run.resultContract())||run.resultExpired()||run.result()==null||run.result().isNull()||!"eclipse_100".equals(run.result().path("modelKind").asText()))throw error(HttpStatus.CONFLICT,"需要同模型版本、未过期的完整E100实际结果");
    }
    private static String sha(String csv){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static RunException error(HttpStatus status,String message){return new RunException(status,message);}
    public record Summary(Long id,Long modelVersionId,Long baselineRunId,String name,String sourceFile,String sourceSha256,int observationCount,LocalDateTime createdAt){}
    public record Dataset(Summary metadata,String csv,List<EclipseHistoryCsv.Observation> observations){}
    public record Evaluation(Summary dataset,long runId,EclipseHistoryCsv.Comparison comparison){}
}
