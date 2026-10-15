package com.grdp.studio.pipeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.grdp.studio.wellbore.erosion.model.ErosionCalculator;
import java.util.*;
import java.util.function.BiFunction;
import static com.grdp.studio.pipeline.PipelineDtos.Point;
import static com.grdp.studio.pipeline.PipelineTopology.Edge;

/** Reuses the wellbore P110 formula without changing the single-phase hydraulic solver. */
public final class PipelineErosion {
    private PipelineErosion() {}
    public record SegmentInput(String edgeId,Double liquidHoldupPercent,Double sandContentPercent,Double sandDensityKgM3) {}
    public record CaseInput(String caseId,String edgeId,Double liquidHoldupPercent,Double sandContentPercent,Double sandDensityKgM3) {}
    public record Configuration(Long liquidPvtId,PipelineErosionLiquidSource.Snapshot liquidPvt,
            List<SegmentInput> segments,List<CaseInput> cases) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Result(String edgeId,String name,Double distanceM,String pointLabel,Double pressureMpa,
            Double temperatureC,Double diameterMm,Double rate10k,Double gasDensityKgM3,Double liquidDensityKgM3,
            Double sandDensityKgM3,Double liquidHoldupPercent,Double sandContentPercent,Double mixtureDensityKgM3,
            Double sandFactor,Double liquidHoldupFactor,Double criticalCoefficient,Double actualVelocityMs,
            Double criticalVelocityMs,Double velocityRatio,String status,boolean applicable,boolean velocityDefinitionVerified,
            String reason,int sampledPoints,int evaluatedPoints,String modelVersion) {}
    private record Sample(Point point,double liquidDensity,double mixtureDensity,double critical,double ratio) {}

    /** A nullable override inherits its segment default; zero always remains an explicit measured value. */
    static SegmentInput parameters(Configuration configuration,String edgeId,String caseId) {
        if(configuration==null)return null;
        SegmentInput defaults=null;
        if(configuration.segments()!=null)for(var row:configuration.segments())if(row!=null&&Objects.equals(edgeId,row.edgeId())) {
            if(defaults!=null)throw new IllegalArgumentException("该管道的冲蚀默认参数重复");defaults=row;
        }
        CaseInput override=null;
        if(configuration.cases()!=null)for(var row:configuration.cases())if(row!=null&&Objects.equals(edgeId,row.edgeId())&&Objects.equals(caseId,row.caseId())) {
            if(override!=null)throw new IllegalArgumentException("该工况与管道的冲蚀参数重复");override=row;
        }
        if(override==null)return defaults;
        return new SegmentInput(edgeId,choose(override.liquidHoldupPercent(),defaults==null?null:defaults.liquidHoldupPercent()),
                choose(override.sandContentPercent(),defaults==null?null:defaults.sandContentPercent()),
                choose(override.sandDensityKgM3(),defaults==null?null:defaults.sandDensityKgM3()));
    }

    public static Result screen(Edge edge,String caseId,List<Point> points,double diameterMm,double rate10k,
            Configuration configuration,BiFunction<Double,Double,Double> liquidDensity) {
        SegmentInput input;
        try {input=parameters(configuration,edge.id(),caseId);}
        catch(IllegalArgumentException ex){return unavailable(edge,points,diameterMm,rate10k,null,"not_evaluated",ex.getMessage(),0);}
        if(input==null||input.liquidHoldupPercent()==null||input.sandContentPercent()==null||input.sandDensityKgM3()==null)
            return unavailable(edge,points,diameterMm,rate10k,input,"not_evaluated","请补齐本管道或本工况的持液率、含砂率、砂粒密度",0);
        double hl=input.liquidHoldupPercent(),hs=input.sandContentPercent(),solid=input.sandDensityKgM3();
        if(!Double.isFinite(hl)||!Double.isFinite(hs)||!Double.isFinite(solid)||hl<0||hs<0||solid<=0||hl+hs>=100)
            return unavailable(edge,points,diameterMm,rate10k,input,"not_applicable","持液率、含砂率须为非负有限数，其和小于100%；砂粒密度须大于0",0);
        if(hs==0)return unavailable(edge,points,diameterMm,rate10k,input,"not_applicable","含砂率为0，含砂模型含对数项，不能用于无砂工况；不能据此判定不存在冲蚀",0);
        if(configuration.liquidPvt()==null||configuration.liquidPvt().issue()!=null||liquidDensity==null)
            return unavailable(edge,points,diameterMm,rate10k,input,"not_evaluated",
                    configuration.liquidPvt()!=null&&configuration.liquidPvt().issue()!=null?configuration.liquidPvt().issue():"请选择当前井已保存的水相 PVT 作为液体密度来源",0);
        if(points.isEmpty())return unavailable(edge,points,diameterMm,rate10k,input,"not_evaluated","缺少管道沿程物性采样点",0);
        var calculator=new ErosionCalculator();var issues=new LinkedHashSet<String>();Sample worst=null;int evaluated=0;
        double k,f,c;
        try {k=calculator.calculateSandFactor(hs);f=calculator.calculateLiquidHoldupFactor(hl);c=calculator.calculateCriticalCoefficient(f,k);}
        catch(IllegalArgumentException|ArithmeticException ex){return unavailable(edge,points,diameterMm,rate10k,input,"not_applicable",ex.getMessage(),0);}
        for(Point point:points) {
            try {
                Double liquid=liquidDensity.apply(point.pressureMpa(),point.temperatureC());
                if(liquid==null||!Double.isFinite(liquid)||liquid<=0)throw new IllegalArgumentException("该温压下液体密度不可用");
                double mixture=calculator.calculateMixtureDensity(hl,hs,point.densityKgM3(),liquid,solid);
                double critical=calculator.calculateCriticalVelocity(c,mixture),ratio=point.velocityMs()/critical;
                if(!Double.isFinite(ratio)||point.velocityMs()<0)throw new IllegalArgumentException("管道实际流速无效");
                evaluated++;issues.addAll(calculator.validateModelDomain(hl,hs,point.velocityMs()));
                if(worst==null||ratio>worst.ratio())worst=new Sample(point,liquid,mixture,critical,ratio);
            } catch(RuntimeException ex) {
                String reason=ex.getMessage()==null?"物性服务不可用":ex.getMessage();
                return unavailable(edge,points,diameterMm,rate10k,input,"not_evaluated",
                        "沿程采样点 "+point.location()+" 未完成评价，不能给出全管最不利结果："+reason,evaluated);
            }
        }
        boolean applicable=issues.isEmpty();var point=worst.point();
        String status=switch(calculator.classify(point.velocityMs(),worst.critical())) {
            case BELOW_LIMIT -> "reference_below";case AT_LIMIT -> "reference_at";default -> "reference_above";
        };
        issues.add(ErosionCalculator.VELOCITY_REASON);issues.add("采用井筒现行 P110 研究公式；以单相气体沿程状态作冲蚀参考比较，不代替多相水力计算或其他材质的试验验证");
        return new Result(edge.id(),edge.name(),point.distanceM()-points.getFirst().distanceM(),point.location(),point.pressureMpa(),
                point.temperatureC(),diameterMm,rate10k,point.densityKgM3(),worst.liquidDensity(),solid,hl,hs,worst.mixtureDensity(),
                k,f,c,point.velocityMs(),worst.critical(),worst.ratio(),status,applicable,false,String.join("；",issues),
                points.size(),evaluated,ErosionCalculator.VERSION);
    }
    private static Result unavailable(Edge edge,List<Point> points,double diameter,double rate,SegmentInput input,
            String status,String reason,int evaluated) {
        return new Result(edge.id(),edge.name(),null,null,null,null,diameter,rate,null,null,input==null?null:input.sandDensityKgM3(),
                input==null?null:input.liquidHoldupPercent(),input==null?null:input.sandContentPercent(),null,null,null,null,null,null,null,
                status,false,false,reason,points.size(),evaluated,ErosionCalculator.VERSION);
    }
    private static Double choose(Double override,Double fallback){return override==null?fallback:override;}
}
