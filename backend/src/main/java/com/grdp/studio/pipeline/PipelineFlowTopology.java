package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineDtos.*;

/** Validates boundary ownership and decodes saved equipment for network and thermal calculations. */
@Service
public class PipelineFlowTopology {
    private final PipelineTopology topology;
    public PipelineFlowTopology(PipelineTopology topology) { this.topology=topology; }

    public void validateBoundary(long project,long reservoir,String well,Boundary boundary) {
        var saved=topology.detail(project,reservoir,well);
        if(saved==null)throw new BusinessException(400,"请先保存当前井拓扑，再登记节点边界条件");
        PipelineBoundary.validateForSave(boundary,saved.graph(),saved.revision());
    }
    static Equipment device(String name,String type,Map<?,?> p,int index) {
        if(!Set.of("valve","compressor").contains(type)) { invalid(name+"：未知设备类型"); }
        double loss=type.equals("valve")?nonnegative(p,"lossK",name):optional(p,"lossK",0,name);
        double ratio=type.equals("compressor")?number(p,"pressureRatio",name,true):optional(p,"pressureRatio",1,name);
        double efficiency=type.equals("compressor")?number(p,"efficiency",name,true):optional(p,"efficiency",1,name);
        double maxPower=type.equals("compressor")?number(p,"maxPowerKw",name,true):optional(p,"maxPowerKw",500,name);
        return new Equipment(name,type,index,loss,ratio,efficiency,number(p,"maxPressureMpa",name,true),maxPower);
    }
    private static double optional(Map<?,?> values,String key,double fallback,String name) {
        return values.get(key)==null?fallback:number(values,key,name,false);
    }
    private static double nonnegative(Map<?,?> values,String key,String name) {
        double value=number(values,key,name,false);
        if(value<0) invalid(name+"："+label(key)+"不能小于零");
        return value;
    }
    private static double number(Map<?,?> values,String key,String name,boolean positive) {
        var raw=values.get(key);
        if(!(raw instanceof Number) || !Double.isFinite(((Number)raw).doubleValue()) || (positive && ((Number)raw).doubleValue()<=0))
            invalid(name+"：请在拓扑中填写有效的"+label(key));
        return ((Number)raw).doubleValue();
    }
    private static String label(String key) {
        return switch(key) {
            case "lengthM" -> "管长";case "diameterMm" -> "内径";case "roughnessMm" -> "粗糙度";
            case "elevationM" -> "高程";case "ambientC" -> "环境温度";case "heatTransferWm2K" -> "总传热系数";
            case "lossK" -> "局部阻力系数";case "pressureRatio" -> "压缩比";case "efficiency" -> "效率";
            case "maxPressureMpa" -> "压力限值";case "maxPowerKw" -> "功率限值";default -> key;
        };
    }
    private static void invalid(String message) { throw new BusinessException(400,message); }
}
