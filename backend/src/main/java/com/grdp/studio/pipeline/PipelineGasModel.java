package com.grdp.studio.pipeline;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineGasProperties.*;
import static com.grdp.studio.pipeline.PipelineDtos.Input;

/** Z/Cp inspection points share the authoritative PVT model; they are never a prerequisite for physical calculations. */
@Service
public class PipelineGasModel {
    public record Snapshot(int revision,long pvtId,String compositionRevision,String method,List<Fraction> composition) {}
    public record Point(double pressureMpa,double temperatureC,State result,ParameterSnapshot parameterSnapshot) {
        public Point(double pressureMpa,double temperatureC,State result){this(pressureMpa,temperatureC,result,null);}
    }
    public record Calculation(double z,double cpJkgK,double densityKgM3,double molarMassKgMol,String compositionRevision,
                              ParameterSnapshot parameterSnapshot) {}
    public record ParameterDetail(long pvtId,String compositionRevision,ParameterSnapshot parameters) {}
    public record Detail(int revision,long pvtId,String compositionRevision,String method,Map<String,Point> points,
                         boolean compositionChanged) {}
    public record Request(@Positive long projectId,@Positive long gasReservoirId,@NotBlank String wellName,
            @Positive long pvtId,@PositiveOrZero int revision,@NotBlank String method,@NotBlank String kind,
            @NotNull @Positive Double pressureMpa,@NotNull Double temperatureC) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PipelineWellContext wells;
    private final PipelinePvtComposition compositions;
    private final PipelineGasProperties gas;
    public PipelineGasModel(JdbcTemplate jdbc,ObjectMapper json,PipelineWellContext wells,
                            PipelinePvtComposition compositions,PipelineGasProperties gas) {
        this.jdbc=jdbc;this.json=json;this.wells=wells;this.compositions=compositions;this.gas=gas;
    }
    private Detail find(long wellId) {
        var rows=jdbc.query("SELECT revision,settings_json FROM pipeline_gas_model WHERE well_id=?",
            (rs,n)->{var d=json.readValue(rs.getString(2),Detail.class);
                return new Detail(rs.getInt(1),d.pvtId(),d.compositionRevision(),d.method(),d.points(),false);},wellId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Detail detail(long project,long reservoir,String well) {
        Detail d=find(wells.require(project,reservoir,well).id());
        if(d==null)return null;
        boolean changed;
        try {
            var current=compositions.detail(project,reservoir,well,d.pvtId());
            changed=!current.issue().isBlank() || !Objects.equals(current.revision(),d.compositionRevision())
                    || !Objects.equals(current.method(),d.method());
        }
        catch(BusinessException e) { changed=true; }
        return new Detail(d.revision(),d.pvtId(),d.compositionRevision(),d.method(),d.points(),changed);
    }
    public Calculation calculate(Request r) {
        check(r);
        var c=compositions.detail(r.projectId(),r.gasReservoirId(),r.wellName(),r.pvtId());
        compositions.requireComplete(c);requireMethod(r.method(),c.method());
        var prepared=gas.prepare(c.method(),c.composition());
        State state=prepared.at(r.pressureMpa(),r.temperatureC());
        return new Calculation(state.z(),state.cpJkgK(),state.densityKgM3(),state.molarMassKgMol(),c.revision(),
                prepared.parameters(c.composition(),r.pressureMpa(),r.temperatureC(),state));
    }
    public ParameterDetail parameters(long project,long reservoir,String well,long pvtId,String method,Double pressureMpa,Double temperatureC) {
        var c=compositions.detail(project,reservoir,well,pvtId);
        requireMethod(method,c.method());
        return new ParameterDetail(c.pvtId(),c.revision(),gas.parameters(c.method(),c.composition(),pressureMpa,temperatureC,c.issue()));
    }
    private void check(Request r) {
        if(r==null || r.kind()==null || !Set.of("z","cp").contains(r.kind()))throw new BusinessException(400,"未知物性计算页面");
        if(r.pressureMpa()==null || r.temperatureC()==null)throw new BusinessException(400,"请填写压力和温度");
    }
    @Transactional
    public Detail save(Request r) {
        check(r);long wellId=wells.require(r.projectId(),r.gasReservoirId(),r.wellName()).id();
        var c=compositions.detail(r.projectId(),r.gasReservoirId(),r.wellName(),r.pvtId());
        compositions.requireComplete(c);requireMethod(r.method(),c.method());
        var prepared=gas.prepare(c.method(),c.composition());
        State result=prepared.at(r.pressureMpa(),r.temperatureC());
        Detail previous=find(wellId);
        if((previous==null?r.revision()!=0:previous.revision()!=r.revision()))
            throw new BusinessException(409,"物性模型已变化，请重新加载后保存");
        Map<String,Point> points=new LinkedHashMap<>();
        if(previous!=null) {
            boolean same=previous.pvtId()==r.pvtId() && Objects.equals(previous.compositionRevision(),c.revision()) && previous.method().equals(r.method());
            previous.points().forEach((kind,point)->points.put(kind,same?point:new Point(point.pressureMpa(),point.temperatureC(),null)));
        }
        points.put(r.kind(),new Point(r.pressureMpa(),r.temperatureC(),result,
                prepared.parameters(c.composition(),r.pressureMpa(),r.temperatureC(),result)));
        Detail next=new Detail(r.revision()+1,r.pvtId(),c.revision(),r.method(),points,false);
        String data=json.writeValueAsString(next);
        if(previous==null) {
            try { jdbc.update("INSERT INTO pipeline_gas_model(well_id,revision,settings_json) VALUES(?,1,?)",wellId,data); }
            catch(DuplicateKeyException e) { throw new BusinessException(409,"物性模型已保存，请重新加载"); }
        } else if(jdbc.update("UPDATE pipeline_gas_model SET settings_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE well_id=? AND revision=?",data,wellId,r.revision())!=1)
            throw new BusinessException(409,"物性模型已变化，请重新加载后保存");
        return next;
    }
    public Snapshot snapshot(long project,long reservoir,String well) {
        var current=compositions.current(project,reservoir,well);
        if(current==null)return null;
        compositions.requireComplete(current);
        return new Snapshot(current.modelRevision(),current.pvtId(),current.revision(),current.method(),List.copyOf(current.composition()));
    }
    /** Ignore browser EOS contents and saved inspection-point values; the current well PVT is authoritative. */
    public Input resolve(long project,long reservoir,String well,Input in) {
        var current=snapshot(project,reservoir,well);
        if(current==null)throw new BusinessException(400,"请先在 PVT 模型页保存完整气体组成和状态方程，再进行管流计算");
        return withSnapshot(in,current);
    }
    private void requireMethod(String requested,String authoritative) {
        if(!Objects.equals(requested,authoritative))throw new BusinessException(409,"计算方法已由 PVT 模型统一管理，请重新加载当前 PVT 方法后计算");
    }
    public Input withSnapshot(Input in,Snapshot s) {
        Double gravity=in.gasGravity(),z=in.z(),cp=in.cpJkgK(),standardZ=in.standardZ(),viscosity=in.viscosityMpaS();
        if(s!=null) {
            double p="inlet".equals(in.target()) ? require(in.outletMpa(),"出口压力") : require(in.inletMpa(),"入口压力");
            var eos=gas.prepare(s.method(),s.composition());
            State state=eos.at(p,require(in.inletC(),"入口温度"));
            gravity=state.molarMassKgMol()/(8.31446261815324/287.05);z=state.z();cp=state.cpJkgK();
            viscosity=eos.standingViscosity(p,in.inletC()).viscosityMpaS();
            standardZ=eos.at(require(in.standardPressurePa(),"标况压力")/1e6,require(in.standardTemperatureK(),"标况温度")-273.15).z();
        }
        return new Input(in.target(),in.thermalMode(),in.frictionMethod(),in.inletMpa(),in.outletMpa(),in.rate10k(),
                in.inletC(),gravity,z,viscosity,cp,in.jtKmpa(),in.standardPressurePa(),in.standardTemperatureK(),
                standardZ,in.segments(),in.equipment(),in.constraints(),s,in.boundary(),in.thermalModel());
    }
    private double require(Double value,String name) {
        if(value==null || !Double.isFinite(value))throw new BusinessException(400,"请在边界条件中填写"+name);
        return value;
    }
}

@RestController
@RequestMapping("/pipeline-capacity/gas-properties")
class PipelineGasModelController {
    private final PipelineGasModel service;private final PipelineGasProperties gas;
    PipelineGasModelController(PipelineGasModel service,PipelineGasProperties gas){this.service=service;this.gas=gas;}
    @GetMapping public ApiResponse<PipelineGasModel.Detail> detail(@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName) {
        return ApiResponse.success(service.detail(projectId,gasReservoirId,wellName));
    }
    @GetMapping("/components") public ApiResponse<List<PipelineGasProperties.ComponentInfo>> components(){return ApiResponse.success(gas.catalog());}
    @GetMapping("/parameters") public ApiResponse<PipelineGasModel.ParameterDetail> parameters(@RequestParam long projectId,
            @RequestParam long gasReservoirId,@RequestParam String wellName,@RequestParam long pvtId,@RequestParam String method,
            @RequestParam(required=false) Double pressureMpa,@RequestParam(required=false) Double temperatureC) {
        return ApiResponse.success(service.parameters(projectId,gasReservoirId,wellName,pvtId,method,pressureMpa,temperatureC));
    }
    @PostMapping("/calculate") public ApiResponse<PipelineGasModel.Calculation> calculate(@Valid @RequestBody PipelineGasModel.Request r){return ApiResponse.success(service.calculate(r));}
    @PutMapping public ApiResponse<PipelineGasModel.Detail> save(@Valid @RequestBody PipelineGasModel.Request r){return ApiResponse.success(service.save(r));}
}
