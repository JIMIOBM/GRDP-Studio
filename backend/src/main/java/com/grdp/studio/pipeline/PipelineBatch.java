package com.grdp.studio.pipeline;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.common.BusinessException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineDtos.*;

/** Each batch uses one immutable set of well sources; saving never accepts client-calculated results. */
@Service
public class PipelineBatch {
    public static final String VERSION="network-batch-2.0";
    public record Calculate(@Positive long projectId,@Positive long gasReservoirId,@NotBlank @Size(max=100) String wellName,
            @PositiveOrZero int revision,@Positive int topologyRevision,@NotNull Input input) {}
    public record Save(@Positive long projectId,@Positive long gasReservoirId,@NotBlank @Size(max=100) String wellName,
            @NotBlank @Size(max=64) String calculationToken) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CaseResult(String caseId,String operatingAt,String status,String error,
            List<PipelineNetworkCalculator.PipeResult> pipes,List<PipelineNetworkCalculator.DeviceResult> equipment,
            List<PipelineNetworkCalculator.HydrateResult> hydrate,List<String> notes) {}
    public record Result(String algorithmVersion,PipelineHydrateModel.Metadata hydrateModel,List<CaseResult> cases,int successCount,int failureCount) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(Long id,int revision,int topologyRevision,Input input,Result result,
            String calculationToken,PipelineTopology.Graph graph) {}
    private record Scope(long projectId,long gasReservoirId,String wellName) {}
    private record TimedCase(BoundaryCase condition,LocalDateTime time,String error,int order) {}
    private static final class Cached {
        final Scope scope;final long wellId,expiresAt,bytes;final String calculated;
        String saved;
        Cached(Scope scope,long wellId,long expiresAt,long bytes,String calculated) {
            this.scope=scope;this.wellId=wellId;this.expiresAt=expiresAt;this.bytes=bytes;this.calculated=calculated;
        }
    }
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final PipelineWellContext wells;
    private final PipelineTopology topology;private final PipelineGasModel gas;private final PipelineTemperature temperature;
    private final PipelineNetworkCalculator network;private final TransactionTemplate write;
    private final Clock clock;private final long ttlMillis,maxBytes;private final int maxEntries;
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>();private long cachedBytes;
    @Autowired
    public PipelineBatch(JdbcTemplate jdbc,ObjectMapper json,PipelineWellContext wells,PipelineTopology topology,
            PipelineGasModel gas,PipelineTemperature temperature,PipelineNetworkCalculator network,PlatformTransactionManager transactions) {
        this(jdbc,json,wells,topology,gas,temperature,network,transactions,Clock.systemUTC(),30*60_000L,16,64*1024*1024L);
    }
    PipelineBatch(JdbcTemplate jdbc,ObjectMapper json,PipelineWellContext wells,PipelineTopology topology,
            PipelineGasModel gas,PipelineTemperature temperature,PipelineNetworkCalculator network,PlatformTransactionManager transactions,
            Clock clock,long ttlMillis,int maxEntries,long maxBytes) {
        this.jdbc=jdbc;this.json=json;this.wells=wells;this.topology=topology;this.gas=gas;this.temperature=temperature;this.network=network;
        this.clock=clock;this.ttlMillis=ttlMillis;this.maxEntries=maxEntries;this.maxBytes=maxBytes;
        write=new TransactionTemplate(transactions);
        // Commit before recording the cached ID, including when a caller already owns a transaction.
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Transactional(readOnly=true)
    public Detail calculate(Calculate request) {
        require(request!=null&&request.input()!=null,"请填写管流计算输入");
        var well=wells.require(request.projectId(),request.gasReservoirId(),request.wellName());
        var scope=new Scope(request.projectId(),request.gasReservoirId(),well.name());
        requireRevision(well.id(),request.revision());
        var stored=topology.detail(scope.projectId(),scope.gasReservoirId(),scope.wellName());
        if(stored==null||stored.revision()!=request.topologyRevision())throw changed("管网拓扑");
        var boundary=request.input().boundary();
        require(boundary!=null&&boundary.cases()!=null&&!boundary.cases().isEmpty(),"请在边界条件中填写工况");
        require(boundary.cases().size()<=744,"一次最多计算 744 条工况");
        if(boundary.topologyRevision()!=stored.revision())throw changed("边界条件关联的拓扑");
        require(json.writeValueAsString(request.input()).length()<=20_000_000,"批量计算输入数据过大");
        require(Set.of("heat","isothermal").contains(String.valueOf(request.input().thermalMode())),"未知温度计算方式");
        var gasSnapshot=gas.snapshot(scope.projectId(),scope.gasReservoirId(),scope.wellName());
        require(gasSnapshot!=null,"请先在 PVT 模型页保存完整气体组成和状态方程");
        var thermal="heat".equals(request.input().thermalMode())
                ?temperature.snapshot(scope.projectId(),scope.gasReservoirId(),scope.wellName(),stored.revision()):null;
        require(!"heat".equals(request.input().thermalMode())||thermal!=null,"请先保存各管段的完整温度模型参数");
        Input input=snapshotInput(request.input(),stored.graph(),gasSnapshot,thermal);
        var rows=new ArrayList<CaseResult>();int successes=0;
        for(var row:orderedCases(input.boundary().cases())) {
            var condition=row.condition();String id=condition==null?null:condition.id(),at=condition==null?null:condition.operatingAt();
            try {
                require(row.error()==null,row.error());
                var solved=network.calculate(stored.graph(),input,thermal,gasSnapshot,condition,stored.revision());
                require(solved!=null&&solved.pipes()!=null&&!solved.pipes().isEmpty(),"本工况未产生管道计算结果");
                rows.add(new CaseResult(id,at,"success",null,List.copyOf(solved.pipes()),List.copyOf(solved.equipment()),List.copyOf(solved.hydrate()),
                        solved.notes()==null?List.of():List.copyOf(solved.notes())));successes++;
            } catch(RuntimeException ex) {
                String error=(ex instanceof BusinessException||ex instanceof IllegalArgumentException)&&ex.getMessage()!=null
                        ?ex.getMessage():"本工况计算失败，请检查边界条件与管道参数";
                rows.add(new CaseResult(id,at,"error",error,List.of(),List.of(),List.of(),List.of()));
            }
        }
        String token=UUID.randomUUID().toString();
        var result=new Result(VERSION,PipelineHydrateModel.prepare(gasSnapshot).metadata(),List.copyOf(rows),successes,rows.size()-successes);
        var detail=new Detail(null,request.revision(),stored.revision(),input,result,token,stored.graph());
        String serialized=json.writeValueAsString(detail);long bytes=serialized.getBytes(StandardCharsets.UTF_8).length;
        require(bytes<=maxBytes/2,"批量结果过大，请减少工况数量后重新计算");
        synchronized(cache) {
            prune();
            while(!cache.isEmpty()&&(cache.size()>=maxEntries||cachedBytes+bytes>maxBytes))remove(cache.keySet().iterator().next());
            cache.put(token,new Cached(scope,well.id(),clock.millis()+ttlMillis,bytes,serialized));cachedBytes+=bytes;
        }
        return json.readValue(serialized,Detail.class);
    }
    public Detail save(Save request) {
        require(request!=null&&request.calculationToken()!=null&&!request.calculationToken().isBlank(),"请先完成批量计算");
        var well=wells.require(request.projectId(),request.gasReservoirId(),request.wellName());
        Cached cached;
        synchronized(cache) {prune();cached=cache.get(request.calculationToken());}
        if(cached==null)throw changed("本次临时计算已过期或服务已重启，请重新计算后保存");
        if(!cached.scope.equals(new Scope(request.projectId(),request.gasReservoirId(),well.name()))||cached.wellId!=well.id())
            throw new BusinessException(403,"计算结果不属于当前井");
        synchronized(cached) {
            if(cached.saved!=null)return json.readValue(cached.saved,Detail.class);
            if(cached.expiresAt<=clock.millis())throw changed("本次临时计算已过期，请重新计算后保存");
            var original=json.readValue(cached.calculated,Detail.class);
            require(original.result().successCount()>0,"全部工况均计算失败，请修正后重新计算，不能保存为空结果");
            PipelineBoundary.forSave(original.input().boundary());
            Detail saved=write.execute(status->saveWithinTransaction(cached,original));
            cached.saved=json.writeValueAsString(saved);
            return json.readValue(cached.saved,Detail.class);
        }
    }
    private Detail saveWithinTransaction(Cached cached,Detail calculated) {
        long wellId=cached.wellId;var scope=cached.scope;
        jdbc.queryForList("SELECT id FROM project_well_heads WHERE id=? FOR UPDATE",wellId);
        jdbc.queryForList("SELECT id FROM pipeline_model WHERE well_id=? FOR UPDATE",wellId);
        jdbc.queryForList("SELECT id FROM pipeline_topology WHERE well_id=? FOR UPDATE",wellId);
        jdbc.queryForList("SELECT id FROM pipeline_pvt_model WHERE well_id=? FOR UPDATE",wellId);
        if(calculated.input().thermalModel()!=null)jdbc.queryForList("SELECT id FROM pipeline_temperature WHERE well_id=? FOR UPDATE",wellId);
        requireRevision(wellId,calculated.revision());
        var currentTopology=topology.detail(scope.projectId(),scope.gasReservoirId(),scope.wellName());
        if(currentTopology==null||currentTopology.revision()!=calculated.topologyRevision()
                ||!Objects.equals(currentTopology.graph(),calculated.graph()))throw changed("管网拓扑");
        if(!Objects.equals(gas.snapshot(scope.projectId(),scope.gasReservoirId(),scope.wellName()),calculated.input().gasModel()))throw changed("PVT 模型");
        if(calculated.input().thermalModel()!=null&&!Objects.equals(temperature.snapshot(scope.projectId(),scope.gasReservoirId(),
                scope.wellName(),calculated.topologyRevision()),calculated.input().thermalModel()))throw changed("温度模型");
        int revision=calculated.revision()+1;
        String inputJson=json.writeValueAsString(calculated.input());
        if(calculated.revision()==0)jdbc.update("INSERT INTO pipeline_model(well_id,revision,topology_revision,input_json) VALUES(?,1,?,?)",
                wellId,calculated.topologyRevision(),inputJson);
        else if(jdbc.update("UPDATE pipeline_model SET revision=revision+1,topology_revision=?,input_json=?,updated_at=CURRENT_TIMESTAMP WHERE well_id=? AND revision=?",
                calculated.topologyRevision(),inputJson,wellId,calculated.revision())!=1)throw changed("管流模型");
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var ps=connection.prepareStatement("INSERT INTO pipeline_batch_run(well_id,model_revision,topology_revision,algorithm_version,input_json,result_json,topology_json) VALUES(?,?,?,?,?,?,?)",new String[]{"id"});
            ps.setLong(1,wellId);ps.setInt(2,revision);ps.setInt(3,calculated.topologyRevision());ps.setString(4,VERSION);
            ps.setString(5,inputJson);ps.setString(6,json.writeValueAsString(calculated.result()));ps.setString(7,json.writeValueAsString(calculated.graph()));return ps;
        },keys);
        return new Detail(Objects.requireNonNull(keys.getKey()).longValue(),revision,calculated.topologyRevision(),calculated.input(),
                calculated.result(),calculated.calculationToken(),calculated.graph());
    }
    public Detail latest(long projectId,long gasReservoirId,String wellName) {
        long wellId=wells.require(projectId,gasReservoirId,wellName).id();
        var rows=jdbc.query("SELECT id,model_revision,topology_revision,input_json,result_json,topology_json FROM pipeline_batch_run WHERE well_id=? ORDER BY id DESC LIMIT 1",
                (rs,n)->new Detail(rs.getLong(1),rs.getInt(2),rs.getInt(3),json.readValue(rs.getString(4),Input.class),
                        json.readValue(rs.getString(5),Result.class),null,json.readValue(rs.getString(6),PipelineTopology.Graph.class)),wellId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private void requireRevision(long wellId,int expected) {
        var values=jdbc.queryForList("SELECT revision FROM pipeline_model WHERE well_id=?",Integer.class,wellId);
        if(expected<0||(values.isEmpty()?expected!=0:values.getFirst()!=expected))throw changed("该井模型或边界条件");
    }
    private void prune() {
        var expired=cache.entrySet().stream().filter(e->e.getValue().expiresAt<=clock.millis()).map(Map.Entry::getKey).toList();
        expired.forEach(this::remove);
    }
    private void remove(String token) {var value=cache.remove(token);if(value!=null)cachedBytes-=value.bytes;}
    static List<TimedCase> orderedCases(List<BoundaryCase> cases) {
        var ids=new HashMap<String,Integer>();var times=new HashMap<LocalDateTime,Integer>();var rows=new ArrayList<TimedCase>();
        for(int i=0;i<cases.size();i++) {
            var row=cases.get(i);LocalDateTime time=null;String error=null;
            if(row==null)error="工况记录为空";
            else {
                if(row.id()==null||row.id().isBlank())error="工况缺少唯一标识";
                else ids.merge(row.id(),1,Integer::sum);
                try {
                    time=PipelineBoundaryTime.parse(row.operatingAt());
                    times.merge(time,1,Integer::sum);
                } catch(DateTimeParseException e) {error="请填写有效的工况时间（YYYY/MM/DD HH:mm），不能使用空时间或无效日期、时分";}
            }
            rows.add(new TimedCase(row,time,error,i));
        }
        return rows.stream().map(row->{
            String error=row.error();
            if(row.condition()!=null&&ids.getOrDefault(row.condition().id(),0)>1)error="工况标识重复，请重新登记该工况";
            if(row.time()!=null&&times.getOrDefault(row.time(),0)>1)error="工况时间重复，请为每个时间保留一条工况";
            return new TimedCase(row.condition(),row.time(),error,row.order());
        }).sorted(Comparator.comparing(TimedCase::time,Comparator.nullsLast(Comparator.naturalOrder())).thenComparingInt(TimedCase::order)).toList();
    }
    private static Input snapshotInput(Input original,PipelineTopology.Graph graph,PipelineGasModel.Snapshot gas,
            PipelineTemperature.Snapshot thermal) {
        require(graph!=null&&graph.nodes()!=null&&graph.edges()!=null&&!graph.edges().isEmpty(),"请保存包含有效管道的拓扑");
        var nodes=new HashMap<String,PipelineTopology.Node>();graph.nodes().forEach(n->nodes.put(n.id(),n));
        var segments=new ArrayList<Segment>();var equipment=new ArrayList<Equipment>();
        for(var edge:graph.edges()) {
            var from=nodes.get(edge.source());var to=nodes.get(edge.target());require(from!=null&&to!=null,"拓扑管道端点不存在");
            var p=edge.parameters();int index=segments.size();
            segments.add(new Segment(edge.name(),number(p,"lengthM"),number(p,"diameterMm"),number(p,"roughnessMm"),
                    number(to.parameters(),"elevationM")-number(from.parameters(),"elevationM"),0,0));
            if(Set.of("valve","compressor").contains(to.type())) {
                equipment.add(equipment(to.name(),to.type(),to.parameters(),index));
                Object extra=to.parameters().get("extraEquipment");
                if(extra instanceof List<?> list)for(Object value:list) {
                    require(value instanceof Map<?,?>,"附加设备参数无效");
                    @SuppressWarnings("unchecked") var parameters=(Map<String,Object>)value;
                    equipment.add(equipment(Objects.toString(parameters.get("name"),""),Objects.toString(parameters.get("type"),""),parameters,index));
                }
            }
        }
        var boundary=original.boundary();
        var normalizedBoundary=new Boundary(boundary.topologyRevision(),boundary.activeCaseId(),boundary.cases().stream().map(c->{
            if(c==null)return null;
            String time=c.operatingAt();try {time=PipelineBoundaryTime.canonical(time);}catch(DateTimeParseException ignored) { /* Preserve invalid input on its failed row. */ }
            return new BoundaryCase(c.id(),time,c.nodes());
        }).toList());
        String waterState=original.constraints()==null||original.constraints().waterState()==null?"unknown":original.constraints().waterState();
        require(Set.of("available","unknown").contains(waterState),"请选择有效的水状态：存在可用水或水状态未知");
        return new Input(null,original.thermalMode(),original.frictionMethod(),null,null,null,null,null,null,null,null,
                original.jtKmpa(),original.standardPressurePa(),original.standardTemperatureK(),original.standardZ(),
                List.copyOf(segments),List.copyOf(equipment),new Constraints(waterState),gas,normalizedBoundary,thermal);
    }
    private static Equipment equipment(String name,String type,Map<String,Object> p,int index) {
        return PipelineFlowTopology.device(name,type,p,index);
    }
    private static double number(Map<String,Object> values,String key) {
        Object value=values==null?null:values.get(key);
        require(value instanceof Number&&Double.isFinite(((Number)value).doubleValue()),"拓扑参数缺失或无效："+key);
        return ((Number)value).doubleValue();
    }
    private static void require(boolean condition,String message) {if(!condition)throw new BusinessException(400,message);}
    private static BusinessException changed(String source) {return new BusinessException(409,source+"已变化或不可用，请重新加载并计算后再保存");}
}

@RestController
@RequestMapping("/pipeline-capacity/batch")
class PipelineBatchController {
    private final PipelineBatch batch;
    PipelineBatchController(PipelineBatch batch){this.batch=batch;}
    @GetMapping("/latest") public ApiResponse<PipelineBatch.Detail> latest(@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName) {
        return ApiResponse.success(batch.latest(projectId,gasReservoirId,wellName));
    }
    @PostMapping("/calculate") public ApiResponse<PipelineBatch.Detail> calculate(@Valid @RequestBody PipelineBatch.Calculate request) {
        return ApiResponse.success(batch.calculate(request));
    }
    @PostMapping("/save") public ApiResponse<PipelineBatch.Detail> save(@Valid @RequestBody PipelineBatch.Save request) {
        return ApiResponse.success(batch.save(request));
    }
}
