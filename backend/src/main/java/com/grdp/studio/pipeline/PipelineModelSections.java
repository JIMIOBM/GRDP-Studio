package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import java.util.List;
import static com.grdp.studio.pipeline.PipelineDtos.*;

/** A boundary edit may change only node conditions and their standard-volume definition. */
final class PipelineModelSections {
    private PipelineModelSections() {}
    static Input empty() {
        return new Input("outlet","heat","colebrook",null,null,null,null,null,null,null,null,null,
                101325.0,293.15,1.0,List.of(),List.of(),new Constraints("unknown"));
    }
    static Input merge(String scope,Input saved,Input page) {
        require(page!=null,"请填写当前页面参数");
        require("boundary".equals(scope),"该页面不单独保存参数，请在管流或水合物页面计算并保存整批结果");
        require(page.boundary()!=null,"请先保存拓扑并按节点登记边界条件");
        positive(page.standardPressurePa(),"标况压力");
        positive(page.standardTemperatureK(),"标况温度");
        positive(page.standardZ(),"标况压缩因子");
        return new Input(saved.target(),saved.thermalMode(),saved.frictionMethod(),saved.inletMpa(),saved.outletMpa(),
                saved.rate10k(),saved.inletC(),saved.gasGravity(),saved.z(),saved.viscosityMpaS(),saved.cpJkgK(),saved.jtKmpa(),
                page.standardPressurePa(),page.standardTemperatureK(),page.standardZ(),saved.segments(),saved.equipment(),
                saved.constraints(),saved.gasModel(),PipelineBoundary.forSave(page.boundary()),saved.thermalModel());
    }
    private static void positive(Double value,String label) {
        require(value==null||(Double.isFinite(value)&&value>0),label+"必须为正的有限数值");
    }
    private static void require(boolean valid,String message) {if(!valid)throw new BusinessException(400,message);}
}
