package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Supplier;

/** Formulas transcribed from temperature documents 1–4; area bases are explicit. */
@Service
public class PipelineTemperatureCalculator {
    public record Layer(String name, Double thicknessMm, Double conductivityWmK) {}
    public record Config(String edgeId, String externalMethod, Double ambientC, Double burialDepthM,
            Double soilConductivityWmK, Double surfaceCoefficientWm2K, Double densityKgM3,
            Double actualFlowM3s, Double viscosityMpaS, Double cpJkgK, Double gasConductivityWmK,
            Long pvtId, Double propertyPressureMpa, Double propertyTemperatureC, String propertySource,
            List<Layer> layers, Double innerDiameterMm) {
        public Config(String edgeId, String externalMethod, Double ambientC, Double burialDepthM,
                Double soilConductivityWmK, Double surfaceCoefficientWm2K, Double densityKgM3,
                Double actualFlowM3s, Double viscosityMpaS, Double cpJkgK, Double gasConductivityWmK,
                Long pvtId, Double propertyPressureMpa, Double propertyTemperatureC, String propertySource,
                List<Layer> layers) {
            this(edgeId,externalMethod,ambientC,burialDepthM,soilConductivityWmK,surfaceCoefficientWm2K,
                    densityKgM3,actualFlowM3s,viscosityMpaS,cpJkgK,gasConductivityWmK,pvtId,
                    propertyPressureMpa,propertyTemperatureC,propertySource,layers,null);
        }
    }
    public record LayerResult(String name,double innerDiameterM,double outerDiameterM,
            double flatResistance,double innerAreaResistance) {}
    public record Result(Double reynolds,Double prandtl,Double nusselt,Double alphaInside,
            Double alphaOutside,Double wallConductance,Double documentK,Double innerAreaU,
            Double innerResistance,Double wallResistance,Double outerResistance,Double outerDiameterM,
            List<LayerResult> layers,List<String> notes) {}

    static void require(boolean condition,String message) {if(!condition)throw new BusinessException(400,message);}
    static double positive(Double x,String field) {require(x!=null&&Double.isFinite(x)&&x>0,"请填写有效的"+field);return x;}
    static void validateKind(String kind) {
        require(kind!=null&&Set.of("inner","wall","outer","overall").contains(kind),"未知的温度计算类型");
    }
    private static void finitePositive(double... values) {
        for(double v:values)require(Double.isFinite(v)&&v>0,"参数导致计算数值异常，请检查单位与数量级");
    }
    private static void requireLayers(Config c) {
        require(c.layers()!=null&&!c.layers().isEmpty()&&c.layers().size()<=12,"请填写管壁及保温层，最多12层");
        for(Layer layer:c.layers())require(layer!=null,"请填写有效的材料层");
    }

    public Result calculate(String kind,double diameterMm,Config c) {
        validateKind(kind);
        require(c!=null,"请填写管段计算参数");
        return switch(kind) {
            case "inner" -> inner(c.innerDiameterMm()==null?diameterMm:c.innerDiameterMm(),c);
            case "wall" -> wall(diameterMm,c);
            case "outer" -> outer(diameterMm,c);
            default -> overall(diameterMm,c);
        };
    }
    public Result calculate(double diameterMm,Config c) {return calculate("overall",diameterMm,c);}

    private Result inner(double diameterMm,Config c) {
        double d=positive(diameterMm,"内径")/1000;
        double rho=positive(c.densityKgM3(),"气体密度"),q=positive(c.actualFlowM3s(),"工况体积流量");
        double mu=positive(c.viscosityMpaS(),"动力黏度")/1000,cp=positive(c.cpJkgK(),"定压比热"),lambda=positive(c.gasConductivityWmK(),"气体导热系数 λ（W/(m·K)）");
        double re=4*q*rho/(Math.PI*d*mu),pr=mu*cp/lambda;
        require(re>=10000,"资料内壁换热关联式按充分湍流使用，当前 Re < 10000，不能直接套用");
        double nu=.021*Math.pow(re,.8)*Math.pow(pr,.43),ai=nu*lambda/d,ri=1/ai;
        finitePositive(re,pr,nu,ai,ri);
        return new Result(re,pr,nu,ai,null,null,null,null,ri,null,null,null,List.of(),List.of(
                "Nu=0.021 Re^0.8 Pr^0.43，按资料1；工程上限制为充分湍流，其他流态不外推。",
                "密度、动力黏度和定压比热由当前井 PVT 按温压计算；气体导热系数 λ 采用本管段手动输入或导入值，工况体积流量来自本页输入或水力联算。"));
    }
    private Result wall(double diameterMm,Config c) {
        double d=positive(diameterMm,"内径")/1000;
        requireLayers(c);
        List<LayerResult> layers=new ArrayList<>();double outer=d,flat=0,wall=0;
        for(Layer l:c.layers()) {
            require(l.name()!=null&&!l.name().isBlank(),"请填写材料层名称");
            double thickness=positive(l.thicknessMm(),"材料层厚度")/1000,k=positive(l.conductivityWmK(),"材料导热系数");
            double next=outer+2*thickness,rf=thickness/k,rc=d*Math.log(next/outer)/(2*k);
            finitePositive(next,rf,rc);
            layers.add(new LayerResult(l.name(),outer,next,rf,rc));flat+=rf;wall+=rc;outer=next;
        }
        double conductance=1/flat;
        finitePositive(conductance,wall,outer);
        return new Result(null,null,null,null,null,conductance,null,null,null,wall,null,outer,layers,List.of(
                "资料2的管道导热系数按 α3=1/Σ(δ/λ) 计算，单位为 W/(m²·K)。",
                "逐层圆筒热阻折算到管内表面积，供总传热系数计算使用。"));
    }
    private Result outer(double diameterMm,Config c) {
        double d=positive(diameterMm,"内径")/1000;
        requireLayers(c);
        double outer=d;
        for(Layer l:c.layers())outer+=2*positive(l.thicknessMm(),"材料层厚度")/1000;
        finitePositive(outer);
        double h=positive(c.burialDepthM(),"管中心埋深"),soil=positive(c.soilConductivityWmK(),"土壤导热系数");
        require(h>outer/2,"管中心埋深必须大于最外层半径");
        double ratio=2*h/outer,acosh=Math.log(ratio+Math.sqrt(ratio*ratio-1));
        double ao;String note;
        if("surface-fixed".equals(c.externalMethod())) {
            ao=2*soil/(outer*acosh);
            note="资料3第一类边界：地表温度给定，按最外层直径计算埋地外部放热系数。";
        } else if("surface-resistance".equals(c.externalMethod())) {
            // Document 3's flowchart assigns h/D = 2 to the shallow branch.
            // Its deep-branch image grows without bound as the surface coefficient increases,
            // unlike the finite soil resistance limit; do not extrapolate that unverified formula.
            require(h/outer<=2,"第二类边界目前支持 h/D外 ≤ 2；h/D外 > 2 的资料公式尚待核定");
            double alpha=positive(c.surfaceCoefficientWm2K(),"地表综合放热系数"),y0=Math.sqrt(h*h-outer*outer/4),b=alpha*y0/soil;
            ao=2*soil*b/(outer*(1+b*acosh));
            note="资料3浅埋第二类边界：计入给定的地表综合放热系数；按流程图用于 h/D外 ≤ 2。";
        } else throw new BusinessException(400,"当前仅支持资料中已核定的埋地换热方法");
        double ro=d/(outer*ao);
        finitePositive(ao,ro);
        return new Result(null,null,null,null,ao,null,null,null,null,null,ro,outer,List.of(),List.of(
                note,"材料层仅提供厚度以确定最外层直径；此项计算不使用材料导热系数、气体物性或环境温度。"));
    }
    private Result component(String name,Supplier<Result> computation) {
        try{return computation.get();}
        catch(BusinessException ex){throw new BusinessException(ex.getCode(),name+"："+ex.getMessage());}
    }
    private Result overall(double diameterMm,Config c) {
        Result inside=component("管内壁放热系数",()->inner(diameterMm,c));
        Result wall=component("管道导热系数",()->wall(diameterMm,c));
        Result outside=component("外部放热系数",()->outer(diameterMm,c));
        double u=1/(inside.innerResistance()+wall.wallResistance()+outside.outerResistance());
        double doc=1/(inside.innerResistance()+1/wall.wallConductance()+1/outside.alphaOutside());
        finitePositive(u,doc);
        var notes=new ArrayList<String>();notes.addAll(inside.notes());notes.addAll(wall.notes());notes.addAll(outside.notes());
        notes.add("资料4的平壁近似K单列供核对；应用到管流计算时使用圆筒热阻折算的内表面积基准U。");
        return new Result(inside.reynolds(),inside.prandtl(),inside.nusselt(),inside.alphaInside(),
                outside.alphaOutside(),wall.wallConductance(),doc,u,inside.innerResistance(),
                wall.wallResistance(),outside.outerResistance(),outside.outerDiameterM(),wall.layers(),notes);
    }
    public Config withMass(Config c,double mass) {
        return new Config(c.edgeId(),c.externalMethod(),c.ambientC(),c.burialDepthM(),c.soilConductivityWmK(),c.surfaceCoefficientWm2K(),c.densityKgM3(),mass/positive(c.densityKgM3(),"气体密度"),c.viscosityMpaS(),c.cpJkgK(),c.gasConductivityWmK(),c.pvtId(),c.propertyPressureMpa(),c.propertyTemperatureC(),c.propertySource(),c.layers(),c.innerDiameterMm());
    }
}
