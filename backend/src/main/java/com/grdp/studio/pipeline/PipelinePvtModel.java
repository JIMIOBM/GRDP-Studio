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
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineGasProperties.Fraction;

/** One authoritative composition and EOS method per well; independent of legacy empirical PVT records. */
@Service
public class PipelinePvtModel {
    public record ComponentInput(@NotBlank String code,@NotNull Double moleFraction) {}
    public record Save(@Positive long projectId,@Positive long gasReservoirId,@NotBlank String wellName,
                       @PositiveOrZero int revision,@NotBlank @Size(max=100) String pvtName,@NotBlank String method,
                       @NotNull @Size(min=1,max=100) List<@NotNull @Valid ComponentInput> composition) {}
    public record Detail(long pvtId,String pvtName,int revision,String method,List<Fraction> composition,
                         String compositionRevision,String issue) {
        public Detail { composition=List.copyOf(composition); }
    }
    private record Parsed(List<Fraction> fractions,String issue) {}
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> METHODS=Set.of("PR","SRK","BWRS");
    private static final Set<String> CODES=new HashSet<>(new PipelineGasProperties().catalog().stream()
            .map(PipelineGasProperties.ComponentInfo::code).toList());
    private final JdbcTemplate jdbc;
    private final PipelineWellContext wells;
    public PipelinePvtModel(JdbcTemplate jdbc,PipelineWellContext wells) {this.jdbc=jdbc;this.wells=wells;}

    @Transactional(readOnly=true)
    public Detail detail(long projectId,long gasReservoirId,String wellName) {
        return find(wells.require(projectId,gasReservoirId,wellName).id());
    }
    private Detail find(long wellId) {
        var rows=jdbc.query("SELECT id,pvt_name,revision,method,composition_json FROM pipeline_pvt_model WHERE well_id=?",
                (rs,n)->{
                    String method=rs.getString(4),raw=rs.getString(5);Parsed parsed=parse(raw);
                    String issue=join(parsed.issue(),validationIssue(method,parsed.fractions()));
                    return new Detail(rs.getLong(1),rs.getString(2),rs.getInt(3),method,parsed.fractions(),
                            fingerprint(rs.getLong(1),method,parsed.fractions(),issue.isBlank()?null:raw),issue);
                },wellId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    @Transactional
    public Detail save(Save request) {
        if(request==null)throw new BusinessException(400,"请填写 PVT 模型参数");
        long wellId=wells.require(request.projectId(),request.gasReservoirId(),request.wellName()).id();
        if(request.revision()<0)throw new BusinessException(400,"PVT 模型版本无效");
        if(request.pvtName()==null||request.pvtName().isBlank()||request.pvtName().trim().length()>100)
            throw new BusinessException(400,"请填写 100 字以内的 PVT 模型名称");
        if(request.composition()==null||request.composition().isEmpty()||request.composition().size()>100)
            throw new BusinessException(400,"请填写完整的气体组分及摩尔含量");
        var fractions=new ArrayList<Fraction>();
        for(ComponentInput entry:request.composition()) {
            if(entry==null||entry.moleFraction()==null)throw new BusinessException(400,"每个气体组分都必须明确填写摩尔含量，缺失值不能按 0 处理");
            fractions.add(new Fraction(entry.code(),entry.moleFraction()));
        }
        String issue=validationIssue(request.method(),fractions);
        if(!issue.isBlank())throw new BusinessException(400,issue);
        String data=JSON.writeValueAsString(fractions);
        if(request.revision()==0) {
            try {jdbc.update("INSERT INTO pipeline_pvt_model(well_id,revision,pvt_name,method,composition_json) VALUES(?,1,?,?,?)",
                    wellId,request.pvtName().trim(),request.method(),data);}
            catch(DuplicateKeyException exception) {throw new BusinessException(409,"PVT 模型已保存，请重新加载后修改");}
        } else if(jdbc.update("UPDATE pipeline_pvt_model SET revision=revision+1,pvt_name=?,method=?,composition_json=?,updated_at=CURRENT_TIMESTAMP WHERE well_id=? AND revision=?",
                request.pvtName().trim(),request.method(),data,wellId,request.revision())!=1)
            throw new BusinessException(409,"PVT 模型已变化，请重新加载后保存");
        return find(wellId);
    }
    static String validationIssue(String method,List<Fraction> fractions) {
        if(method==null||!METHODS.contains(method))return "请选择 PR、SRK 或 BWRS 状态方程";
        if(fractions==null||fractions.isEmpty())return "请填写完整的气体组分及摩尔含量";
        var seen=new HashSet<String>();double total=0;
        for(Fraction fraction:fractions) {
            if(fraction==null||fraction.code()==null||!CODES.contains(fraction.code()))return "存在未知气体组分，请从组分目录中选择";
            if(!seen.add(fraction.code()))return "气体组分重复："+fraction.code();
            if(!Double.isFinite(fraction.moleFraction())||fraction.moleFraction()<0||fraction.moleFraction()>1)
                return fraction.code()+" 摩尔含量须为 0～100% 的有效数值";
            total+=fraction.moleFraction();
        }
        if(Math.abs(total-1)>1e-6)return String.format(Locale.ROOT,"气体摩尔含量合计为 %.6f%%，全部组分合计须为 100%%；不会自动补足或归一化",total*100);
        return "";
    }
    private static Parsed parse(String raw) {
        var fractions=new ArrayList<Fraction>();String issue="";
        try {
            var node=JSON.readTree(raw);
            if(node==null||!node.isArray())return new Parsed(List.of(),"已保存的 PVT 组分数据格式无效，请重新保存");
            for(var entry:node) {
                if(!entry.isObject()||!entry.path("code").isTextual()||!entry.path("moleFraction").isNumber()) {
                    issue="已保存的 PVT 组分或摩尔含量缺失，请核对并重新保存";continue;
                }
                fractions.add(new Fraction(entry.path("code").asText(),entry.path("moleFraction").asDouble()));
            }
        }catch(RuntimeException exception) {return new Parsed(List.of(),"已保存的 PVT 组分数据格式无效，请重新保存");}
        return new Parsed(fractions,issue);
    }
    private static String join(String first,String second) {
        return first.isBlank()?second:second.isBlank()?first:first+"；"+second;
    }
    private static String fingerprint(long pvtId,String method,List<Fraction> fractions,String invalidRaw) {
        var sorted=new ArrayList<>(fractions);sorted.sort(Comparator.comparing(Fraction::code,Comparator.nullsFirst(Comparator.naturalOrder())));
        StringBuilder source=new StringBuilder("pipeline-pvt:v1:").append(pvtId).append(':').append(method);
        for(var fraction:sorted)source.append('|').append(fraction.code()).append(':')
                .append(Double.toHexString(fraction.moleFraction()==0?0:fraction.moleFraction()));
        if(invalidRaw!=null)source.append("|invalid:").append(invalidRaw);
        try {return "pipeline-pvt:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException exception) {throw new IllegalStateException("SHA-256 unavailable",exception);}
    }
}

@RestController
@RequestMapping("/pipeline-capacity/pvt-model")
class PipelinePvtModelController {
    private final PipelinePvtModel service;
    PipelinePvtModelController(PipelinePvtModel service) {this.service=service;}
    @GetMapping public ApiResponse<PipelinePvtModel.Detail> detail(@RequestParam long projectId,@RequestParam long gasReservoirId,
            @RequestParam String wellName) {return ApiResponse.success(service.detail(projectId,gasReservoirId,wellName));}
    @PutMapping public ApiResponse<PipelinePvtModel.Detail> save(@Valid @RequestBody PipelinePvtModel.Save request) {
        return ApiResponse.success(service.save(request));
    }
}
