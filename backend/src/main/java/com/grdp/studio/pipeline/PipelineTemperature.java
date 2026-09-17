package com.grdp.studio.pipeline;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineTemperatureCalculator.*;

@Service
public class PipelineTemperature {
    public record Settings(@NotNull @Size(max=400) List<@NotNull Config> segments,
            @DecimalMin("0.00001") @DecimalMax("0.1") double tolerance,@Min(2) @Max(80) int maxIterations,
            SavedResults savedResults) {
        public Settings(List<Config> segments,double tolerance,int maxIterations){this(segments,tolerance,maxIterations,null);}
    }
    public record Request(@Positive long projectId,@Positive long gasReservoirId,@NotBlank String wellName,
            @PositiveOrZero int revision,@Positive int topologyRevision,@NotNull @Valid Settings settings) {}
    public record CalculateRequest(@Positive long projectId,@Positive long gasReservoirId,@NotBlank String wellName,
            @Positive int topologyRevision,@NotNull @Size(max=400) List<@NotNull Config> segments) {}
    public record Detail(int revision,Settings settings) {}
    /** Only source parameters belong in flow histories; cached display results are deliberately excluded. */
    public record FlowSettings(List<Config> segments,double tolerance,int maxIterations) {}
    public record Snapshot(int revision,int topologyRevision,FlowSettings settings) {}
    public record Row(String edgeId,String name,Result result,String error,PipelineGasProperties.State properties,
            Double effectiveDiameterMm,Config usedInput,PipelineGasProperties.ParameterSnapshot gasParameters,
            PipelinePvtThermalProperties.Detail pvtProperties) {
        public Row(String edgeId,String name,Result result,String error){this(edgeId,name,result,error,null,null,null,null,null);}
        public Row(String edgeId,String name,Result result,String error,PipelineGasProperties.State properties){this(edgeId,name,result,error,properties,null,null,null,null);}
        public Row(String edgeId,String name,Result result,String error,PipelineGasProperties.State properties,
                Double effectiveDiameterMm,Config usedInput,PipelineGasProperties.ParameterSnapshot gasParameters) {
            this(edgeId,name,result,error,properties,effectiveDiameterMm,usedInput,gasParameters,null);
        }
    }
    public record Solve(@NotNull @Valid Request thermal,@NotNull PipelineDtos.Input input) {}
    public record Solution(PipelineDtos.Input input,PipelineDtos.Result result,List<Row> coefficients,int iterations) {}
    public record ResultScope(long projectId,long gasReservoirId,String wellName) {}
    public record SavedRow(Row row,String stamp,String flowStamp,boolean fromSolve) {}
    /** Read-only display snapshots: solving always reads Settings.segments, never these stored results. */
    public record SavedResults(ResultScope scope,int topologyRevision,Map<String,List<SavedRow>> coefficients,
            List<SavedRow> coupled,Solution solution,String solutionMark,String calculationMode) {}
    private final JdbcTemplate jdbc; private final ObjectMapper json;private final PipelineWellContext wells;
    private final PipelineTopology topology;private final PipelineTemperatureCalculator heat;private final PipelineCalculator flow;
    private final PipelineGasModel gasModel;private final PipelineGasProperties gas;
    private final PipelinePvtThermalProperties pvtProperties;
    public PipelineTemperature(JdbcTemplate jdbc,ObjectMapper json,PipelineWellContext wells,PipelineTopology topology,PipelineTemperatureCalculator heat,PipelineCalculator flow,PipelineGasModel gasModel,PipelineGasProperties gas,PipelinePvtThermalProperties pvtProperties) {
        this.jdbc=jdbc;this.json=json;this.wells=wells;this.topology=topology;this.heat=heat;this.flow=flow;this.gasModel=gasModel;this.gas=gas;
        this.pvtProperties=pvtProperties;
    }
    public Detail detail(long project,long reservoir,String well) {
        long id=wells.require(project,reservoir,well).id();
        var rows=jdbc.query("SELECT revision,settings_json FROM pipeline_temperature WHERE well_id=?",(rs,n)->new Detail(rs.getInt(1),json.readValue(rs.getString(2),Settings.class)),id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Snapshot snapshot(long project,long reservoir,String well,int topologyRevision) {
        var saved=detail(project,reservoir,well);
        if(saved==null)return null;
        var settings=saved.settings();
        require(settings!=null&&settings.segments()!=null,"已保存的温度模型参数不完整，请重新保存温度模型");
        require(settings.segments().stream().allMatch(Objects::nonNull),"已保存的温度模型包含空管段，请重新保存温度模型");
        return new Snapshot(saved.revision(),topologyRevision,new FlowSettings(settings.segments().stream()
                .map(PipelineTemperature::sourceInputs).toList(),settings.tolerance(),settings.maxIterations()));
    }
    public Solution solveSnapshot(long project,long reservoir,String well,Snapshot snapshot,PipelineDtos.Input input) {
        require(snapshot!=null&&snapshot.revision()>0&&snapshot.settings()!=null,"请先在温度模型中保存各管段的完整传热参数");
        var settings=snapshot.settings();
        require(Double.isFinite(settings.tolerance())&&settings.tolerance()>=.00001&&settings.tolerance()<=.1
                &&settings.maxIterations()>=2&&settings.maxIterations()<=80,"已保存温度模型的迭代设置无效，请重新保存");
        var request=new Request(project,reservoir,well,snapshot.revision(),snapshot.topologyRevision(),
                new Settings(settings.segments(),settings.tolerance(),settings.maxIterations()));
        var solved=solve(new Solve(request,input));
        return new Solution(solved.input().withThermalModel(snapshot),solved.result(),solved.coefficients(),solved.iterations());
    }
    private PipelineTopology.Graph graph(Request r) {
        // Shared drafts may retain a deleted PVT reference; material/environment settings must remain saveable.
        // The authoritative provider validates ownership and existence whenever gas properties are consumed.
        var g=graph(r.projectId(),r.gasReservoirId(),r.wellName(),r.topologyRevision(),r.settings().segments());
        for(Config c:r.settings().segments())require(c.layers()!=null,"请填写材料层列表");
        require(json.writeValueAsString(new Settings(r.settings().segments(),r.settings().tolerance(),r.settings().maxIterations())).length()<2_000_000,"温度模型数据过大");
        return g;
    }
    private PipelineTopology.Graph graph(long projectId,long reservoirId,String wellName,int topologyRevision,
            List<Config> configs) {
        require(configs!=null&&configs.size()<=400,"温度管段参数最多400段");
        var t=topology.detail(projectId,reservoirId,wellName);
        require(t!=null,"请先保存当前井的管网拓扑");require(t.revision()==topologyRevision,"拓扑已变化，请重新加载温度模型后再计算或保存");
        var edgeIds=new HashSet<String>();for(var e:t.graph().edges())edgeIds.add(e.id());
        var seen=new HashSet<String>();
        for(Config c:configs) {
            require(c!=null,"请填写有效的管段计算参数");
            require(edgeIds.contains(c.edgeId())&&seen.add(c.edgeId()),"温度参数的管段不存在或重复，请重新加载");
            require(c.layers()==null||c.layers().size()<=12,"材料层最多12层");
        }
        require(json.writeValueAsString(configs).length()<2_000_000,"温度模型数据过大");
        return t.graph();
    }
    @Transactional public Detail save(Request r) {
        var g=graph(r);validateSavedResults(r,g);long wellId=wells.require(r.projectId(),r.gasReservoirId(),r.wellName()).id();
        // Density, viscosity and Cp are PVT outputs; each pipe's supplied conductivity is an editable input.
        var settings=new Settings(r.settings().segments().stream().map(PipelineTemperature::sourceInputs).toList(),
                r.settings().tolerance(),r.settings().maxIterations(),r.settings().savedResults());
        String data=json.writeValueAsString(settings);
        require(data.length()<20_000_000,"温度模型与计算结果数据过大");
        if(r.revision()==0) {try{jdbc.update("INSERT INTO pipeline_temperature(well_id,revision,settings_json) VALUES(?,1,?)",wellId,data);}catch(DuplicateKeyException e){throw new BusinessException(409,"温度模型已保存，请重新加载");}}
        else require(jdbc.update("UPDATE pipeline_temperature SET settings_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE well_id=? AND revision=?",data,wellId,r.revision())==1,"温度模型已变化，请重新加载");
        return detail(r.projectId(),r.gasReservoirId(),r.wellName());
    }
    static void validateSavedResults(Request request,PipelineTopology.Graph graph) {
        var saved=request.settings().savedResults();if(saved==null)return;
        require(saved.scope()!=null&&saved.scope().projectId()==request.projectId()&&saved.scope().gasReservoirId()==request.gasReservoirId()
                &&Objects.equals(saved.scope().wellName(),request.wellName()),"温度计算结果不属于当前井");
        require(saved.topologyRevision()==request.topologyRevision(),"温度计算结果的拓扑版本已变化");
        require(saved.calculationMode()!=null&&Set.of("coefficient","coupled").contains(saved.calculationMode()),"温度结果计算方式无效");
        var ids=new HashSet<String>();graph.edges().forEach(edge->ids.add(edge.id()));
        if(saved.coefficients()!=null)for(var entry:saved.coefficients().entrySet()) {
            validateKind(entry.getKey());validateSavedRows(entry.getValue(),ids,false);
        }
        validateSavedRows(saved.coupled(),ids,true);
        if(saved.solution()!=null) {
            require(saved.solutionMark()!=null&&!saved.solutionMark().isBlank()&&saved.solutionMark().length()<2_000_000,"温压联算结果缺少输入标识");
            require(saved.solution().input()!=null&&saved.solution().result()!=null&&saved.solution().coefficients()!=null,"温压联算结果不完整");
            require(saved.coupled()!=null&&!saved.coupled().isEmpty(),"温压联算结果缺少管段系数");
            var solutionIds=new HashSet<String>();
            for(var row:saved.solution().coefficients())require(row!=null&&ids.contains(row.edgeId())&&solutionIds.add(row.edgeId()),"温压联算结果包含其他管段或重复管段");
            require(solutionIds.equals(ids),"温压联算结果未覆盖当前拓扑管段");
        }else require(saved.coupled()==null||saved.coupled().isEmpty(),"温压联算系数缺少对应的完整结果");
    }
    private static void validateSavedRows(List<SavedRow> rows,Set<String> ids,boolean coupled) {
        if(rows==null)return;
        require(rows.size()<=ids.size(),"温度计算结果管段数超限");var seen=new HashSet<String>();
        for(var saved:rows) {
            require(saved!=null&&saved.row()!=null,"温度计算结果无效");var row=saved.row();
            require(ids.contains(row.edgeId())&&seen.add(row.edgeId()),"温度计算结果包含其他管段或重复管段");
            require(row.result()!=null||(row.error()!=null&&!row.error().isBlank()),"温度计算结果为空");
            require(saved.stamp()!=null&&!saved.stamp().isBlank()&&saved.stamp().length()<100_000,"温度计算结果缺少输入标识");
            require(saved.fromSolve()==coupled,"温度计算结果类型不一致");
            if(coupled)require(saved.flowStamp()!=null&&!saved.flowStamp().isBlank()&&saved.flowStamp().length()<2_000_000,"温压联算系数缺少输入标识");
            if(row.usedInput()!=null)require(Objects.equals(row.edgeId(),row.usedInput().edgeId()),"温度计算输入快照管段不一致");
        }
    }
    static double value(Map<String,Object> map,String key) {
        Object v=map.get(key);require(v instanceof Number,"缺少拓扑参数："+key);double d=((Number)v).doubleValue();require(Double.isFinite(d),"拓扑参数无效："+key);return d;
    }
    public List<Row> calculate(String kind,CalculateRequest r) {
        validateKind(kind);
        boolean needsProperties="inner".equals(kind)||"overall".equals(kind);
        var g=graph(r.projectId(),r.gasReservoirId(),r.wellName(),r.topologyRevision(),r.segments());var rows=new ArrayList<Row>();
        for(Config c:r.segments()) {
            var e=g.edges().stream().filter(x->x.id().equals(c.edgeId())).findFirst().orElseThrow();
            PipelinePvtThermalProperties.Detail source=null;
            try{
                if(needsProperties) {
                    positive(c.gasConductivityWmK(),"气体导热系数 λ（W/(m·K)）");
                    source=properties(r.projectId(),r.gasReservoirId(),r.wellName(),c.pvtId(),c.propertyPressureMpa(),c.propertyTemperatureC());
                    pvtProperties.requireComplete(source);
                }
                var input=needsProperties?withProperties(c,source,null):sourceInputs(c);
                double diameter="inner".equals(kind)&&c.innerDiameterMm()!=null?c.innerDiameterMm():value(e.parameters(),"diameterMm");
                rows.add(new Row(e.id(),e.name(),heat.calculate(kind,diameter,input),null,null,diameter,input,null,source));
            }
            catch(BusinessException ex){rows.add(new Row(e.id(),e.name(),null,ex.getMessage(),null,null,null,null,source));}
        }return rows;
    }
    public PipelinePvtThermalProperties.Detail properties(long project,long reservoir,String well,Long pvtId,Double p,Double t) {
        return pvtProperties.detail(project,reservoir,well,pvtId,p,t);
    }
    private List<PipelineTopology.Edge> ordered(PipelineTopology.Graph g) {
        require(g.nodes().stream().filter(n->n.type().equals("well")).count()==1,"必须有一个固定井口");
        String current=g.nodes().stream().filter(n->n.type().equals("well")).findFirst().orElseThrow().id();
        var seen=new HashSet<String>();var edges=new ArrayList<PipelineTopology.Edge>();
        while(seen.add(current)) {
            String id=current;var outs=g.edges().stream().filter(e->e.source().equals(id)).toList();
            require(outs.size()<=1&&g.edges().stream().filter(e->e.target().equals(id)).count()<=1,"分支管网暂不能进行串联温压联算");
            if(outs.isEmpty())break;edges.add(outs.getFirst());current=outs.getFirst().target();
        }
        require(edges.size()==g.edges().size()&&seen.size()==g.nodes().size()&&edges.size()==g.nodes().size()-1&&!edges.isEmpty()&&edges.size()<=30,"拓扑不连通、存在环路或管段数超限，不能串联联算");
        return edges;
    }
    public Solution solve(Solve request) {
        Request r=request.thermal();var g=graph(r);var edges=ordered(g);var configs=new HashMap<String,Config>();r.settings().segments().forEach(c->configs.put(c.edgeId(),c));
        require(configs.size()==edges.size(),"请补齐全部管段温度参数");
        // Standalone thermal trials can use unsaved settings, so they cannot inherit a saved flow-source claim.
        var boundaryInput=PipelineBoundary.resolve(request.input().withThermalModel(null),g,r.topologyRevision());
        for(Config c:configs.values())require(c.ambientC()!=null&&Double.isFinite(c.ambientC())&&c.ambientC()>-100&&c.ambientC()<300,"温度分布联算：请填写各管段有效的环境温度");
        Config first=configs.get(edges.getFirst().id());
        for(Config c:configs.values())require(c.pvtId()!=null&&Objects.equals(c.pvtId(),first.pvtId()),"温压联算：全部管段须选择同一份当前井 PVT");
        for(Config c:configs.values())positive(c.gasConductivityWmK(),"各管段的气体导热系数 λ（W/(m·K)）");
        Double referenceP="inlet".equals(boundaryInput.target())?boundaryInput.outletMpa():boundaryInput.inletMpa();
        var reference=properties(r.projectId(),r.gasReservoirId(),r.wellName(),first.pvtId(),referenceP,boundaryInput.inletC());
        pvtProperties.requireComplete(reference);
        var base=gasModel.resolve(r.projectId(),r.gasReservoirId(),r.wellName(),boundaryInput);
        require(base.gasModel()!=null&&base.gasModel().pvtId()==first.pvtId(),"温压联算：当前井 PVT模型已变化，请重新加载温度模型");
        double cp=reference.cpJkgK(),mu=reference.viscosityMpaS();
        positive(base.gasGravity(),"气体相对密度");positive(base.standardPressurePa(),"标况压力");positive(base.standardTemperatureK(),"标况温度");positive(base.standardZ(),"标况Z");
        double massPerRate=base.standardPressurePa()/(base.standardZ()*(287.05/base.gasGravity())*base.standardTemperatureK())/8.64;
        double rate=base.rate10k()==null?1:base.rate10k();double previousT=Double.NaN;
        if("rate".equals(base.target())) {
            // Start within the convection correlation's range; reject low Re only for the solved flow.
            double largestDiameter=edges.stream().mapToDouble(e->value(e.parameters(),"diameterMm")/1000).max().orElseThrow();
            double minimumRate=10000*Math.PI*largestDiameter*(mu*.001)/(4*massPerRate);
            rate=Math.max(rate,minimumRate*1.05);
        }
        PipelineDtos.Result previousResult=null;double[] previousU=new double[edges.size()];
        for(int iteration=1;iteration<=r.settings().maxIterations();iteration++) {
            var segments=new ArrayList<PipelineDtos.Segment>();var equipment=new ArrayList<PipelineDtos.Equipment>();var rows=new ArrayList<Row>();
            double maximumUChange=0;
            for(int i=0;i<edges.size();i++) {
                var e=edges.get(i);Config c=configs.get(e.id());
                double meanP="inlet".equals(base.target())?base.outletMpa():base.inletMpa(),meanT=base.inletC();
                if(previousResult!=null) {
                    final int segmentIndex=i;
                    var points=previousResult.points().stream().filter(p->p.segmentIndex()==segmentIndex && (p.location().equals(e.name()) || p.location().equals(e.name()+" 起点"))).toList();
                    meanP=points.stream().mapToDouble(PipelineDtos.Point::pressureMpa).average().orElse(meanP);
                    meanT=points.stream().mapToDouble(PipelineDtos.Point::temperatureC).average().orElse(meanT);
                }
                var source=properties(r.projectId(),r.gasReservoirId(),r.wellName(),c.pvtId(),meanP,meanT);
                pvtProperties.requireComplete(source);
                var state=state(base.gasModel(),meanP,meanT);
                var used=withProperties(c,source,rate*massPerRate);
                double diameter=value(e.parameters(),"diameterMm");Result coeff=heat.calculate(diameter,used);
                maximumUChange=Math.max(maximumUChange,Math.abs(coeff.innerAreaU()-previousU[i])/Math.max(coeff.innerAreaU(),1e-12));previousU[i]=coeff.innerAreaU();
                rows.add(new Row(e.id(),e.name(),coeff,null,state,diameter,used,parameters(base.gasModel(),meanP,meanT,state),source));
                var start=g.nodes().stream().filter(n->n.id().equals(e.source())).findFirst().orElseThrow();var end=g.nodes().stream().filter(n->n.id().equals(e.target())).findFirst().orElseThrow();
                segments.add(new PipelineDtos.Segment(e.name(),value(e.parameters(),"lengthM"),value(e.parameters(),"diameterMm"),value(e.parameters(),"roughnessMm"),value(end.parameters(),"elevationM")-value(start.parameters(),"elevationM"),c.ambientC(),coeff.innerAreaU()));
                if(Set.of("valve","compressor").contains(end.type())) {
                    var p=end.parameters();equipment.add(PipelineFlowTopology.device(end.name(),end.type(),p,i));
                    if(p.get("extraEquipment") instanceof List<?> extra)for(Object x:extra)if(x instanceof Map<?,?> raw)
                        equipment.add(PipelineFlowTopology.device(String.valueOf(raw.get("name")),String.valueOf(raw.get("type")),raw,i));
                }
            }
            var input=new PipelineDtos.Input(base.target(),"heat",base.frictionMethod(),base.inletMpa(),base.outletMpa(),base.rate10k(),base.inletC(),base.gasGravity(),base.z(),mu,cp,base.jtKmpa(),base.standardPressurePa(),base.standardTemperatureK(),base.standardZ(),segments,equipment,base.constraints(),base.gasModel(),base.boundary());
            var result=flow.calculate(input);double relative=Math.abs(result.rate10k()-rate)/Math.max(result.rate10k(),1e-8);
            if(iteration>1&&relative<r.settings().tolerance()&&Math.abs(result.outletC()-previousT)<r.settings().tolerance()&&maximumUChange<r.settings().tolerance())return new Solution(input,PipelineBoundary.assess(input,g,result),rows,iteration);
            rate=(rate+result.rate10k())/2;previousT=result.outletC();previousResult=result;
        }throw new BusinessException(400,"温压联算未收敛，请检查边界、物性与迭代设置");
    }
    private PipelineGasProperties.State state(PipelineGasModel.Snapshot model,Double p,Double t) {
        if(model==null)return null;
        require(p!=null&&t!=null,"状态方程计算需要物性工况压力和温度");
        return gas.calculate(model.method(),model.composition(),p,t);
    }
    private PipelineGasProperties.ParameterSnapshot parameters(PipelineGasModel.Snapshot model,Double p,Double t,PipelineGasProperties.State state) {
        return model==null?null:gas.prepare(model.method(),model.composition()).parameters(model.composition(),p,t,state);
    }
    static Config sourceInputs(Config c) {
        return new Config(c.edgeId(),c.externalMethod(),c.ambientC(),c.burialDepthM(),c.soilConductivityWmK(),c.surfaceCoefficientWm2K(),
                null,c.actualFlowM3s(),null,null,c.gasConductivityWmK(),c.pvtId(),c.propertyPressureMpa(),c.propertyTemperatureC(),null,c.layers(),c.innerDiameterMm());
    }
    static Config withProperties(Config c,PipelinePvtThermalProperties.Detail source,Double mass) {
        Double actualFlow=c.actualFlowM3s();
        if(mass!=null)actualFlow=mass/source.densityKgM3();
        return new Config(c.edgeId(),c.externalMethod(),c.ambientC(),c.burialDepthM(),c.soilConductivityWmK(),c.surfaceCoefficientWm2K(),
                source.densityKgM3(),actualFlow,source.viscosityMpaS(),source.cpJkgK(),c.gasConductivityWmK(),
                source.pvtId(),source.pressureMpa(),source.temperatureC(),source.pvtName(),c.layers(),c.innerDiameterMm());
    }
}
@RestController
@RequestMapping("/pipeline-capacity/temperature")
class PipelineTemperatureController {
    private final PipelineTemperature service;
    PipelineTemperatureController(PipelineTemperature service){this.service=service;}
    @GetMapping public ApiResponse<PipelineTemperature.Detail> detail(@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName){return ApiResponse.success(service.detail(projectId,gasReservoirId,wellName));}
    @GetMapping("/properties") public ApiResponse<PipelinePvtThermalProperties.Detail> properties(@RequestParam long projectId,
            @RequestParam long gasReservoirId,@RequestParam String wellName,@RequestParam(required=false) Long pvtId,
            @RequestParam(required=false) Double pressureMpa,@RequestParam(required=false) Double temperatureC) {
        return ApiResponse.success(service.properties(projectId,gasReservoirId,wellName,pvtId,pressureMpa,temperatureC));
    }
    @PutMapping public ApiResponse<PipelineTemperature.Detail> save(@Valid @RequestBody PipelineTemperature.Request r){return ApiResponse.success(service.save(r));}
    @PostMapping("/calculate/{kind}") public ApiResponse<List<PipelineTemperature.Row>> calculate(@PathVariable String kind,@Valid @RequestBody PipelineTemperature.CalculateRequest r){return ApiResponse.success(service.calculate(kind,r));}
    @PostMapping("/solve") public ApiResponse<PipelineTemperature.Solution> solve(@Valid @RequestBody PipelineTemperature.Solve r){return ApiResponse.success(service.solve(r));}
}
