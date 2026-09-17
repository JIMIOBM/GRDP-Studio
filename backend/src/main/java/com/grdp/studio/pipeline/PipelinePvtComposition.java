package com.grdp.studio.pipeline;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineGasProperties.Fraction;

/** Compatibility projection of the authoritative full-composition PVT model, never legacy impurity fields. */
@Service
public class PipelinePvtComposition {
    public record Detail(long pvtId,String revision,List<Fraction> composition,String issue,
                         String pvtName,String method,int modelRevision) {
        public Detail(long pvtId,String revision,List<Fraction> composition,String issue) {
            this(pvtId,revision,composition,issue,null,null,0);
        }
        public Detail {composition=List.copyOf(composition);}
    }
    private final PipelinePvtModel models;
    public PipelinePvtComposition(JdbcTemplate jdbc,PipelineWellContext wells) {models=new PipelinePvtModel(jdbc,wells);}
    public Detail current(long projectId,long gasReservoirId,String wellName) {
        var model=models.detail(projectId,gasReservoirId,wellName);
        return model==null?null:new Detail(model.pvtId(),model.compositionRevision(),model.composition(),model.issue(),
                model.pvtName(),model.method(),model.revision());
    }
    public Detail detail(long projectId,long gasReservoirId,String wellName,long pvtId) {
        var current=current(projectId,gasReservoirId,wellName);
        if(current==null||pvtId<=0||current.pvtId()!=pvtId)
            throw new BusinessException(400,"PVT 模型不属于当前井或已删除，请在 PVT 模型页重新保存完整组成并选择");
        return current;
    }
    public void requireComplete(Detail detail) {
        if(detail==null)throw new BusinessException(400,"请先在 PVT 模型页保存完整气体组成和状态方程");
        if(detail.issue()!=null&&!detail.issue().isBlank())throw new BusinessException(400,detail.issue());
        String issue=PipelinePvtModel.validationIssue(detail.method(),detail.composition());
        if(!issue.isBlank())throw new BusinessException(400,issue);
    }
}

@RestController
@RequestMapping("/pipeline-capacity/pvt-composition")
class PipelinePvtCompositionController {
    private final PipelinePvtComposition service;
    PipelinePvtCompositionController(PipelinePvtComposition service) {this.service=service;}
    @GetMapping
    public ApiResponse<PipelinePvtComposition.Detail> detail(@RequestParam long projectId,@RequestParam long gasReservoirId,
            @RequestParam String wellName,@RequestParam long pvtId) {
        return ApiResponse.success(service.detail(projectId,gasReservoirId,wellName,pvtId));
    }
}
