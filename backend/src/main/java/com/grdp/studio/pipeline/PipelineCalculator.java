package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineDtos.*;

/** Steady single-phase series line. EOS or fixed properties, Darcy friction, elevation and heat loss.
 * No phase flash, hydrate kinetics, transient shutdown or multiphase claims are made. */
@Service
public class PipelineCalculator {
    public static final String VERSION = "series-gas-2.5";
    private static final double R_AIR = 287.05;
    private static final double MIN_P = 20000;
    private static final double MAX_MACH = 0.3;

    public void validate(Input in) {
        require(in!=null,"请填写计算输入");
        require(in.target()!=null,"请选择求解目标");
        require(in.thermalMode()!=null,"请选择温度模型");
        require(in.frictionMethod()!=null,"请选择摩阻模型");
        require(in.inletC()!=null,"请填写入口温度");
        require(in.gasGravity()!=null,"请在边界条件中填写气体相对密度");
        require(in.z()!=null,"请在边界条件中填写压缩因子");
        require(in.viscosityMpaS()!=null,"请在边界条件中填写气体黏度");
        require(in.cpJkgK()!=null,"请在边界条件中填写气体比热");
        boolean heat="heat".equals(in.thermalMode());
        require(!heat||in.jtKmpa()!=null,"请在管流计算参数设置中填写焦耳—汤姆逊系数");
        double jt=heat?in.jtKmpa():0;
        require(in.standardPressurePa()!=null && in.standardTemperatureK()!=null && in.standardZ()!=null,"请在边界条件中补全标况参数");
        require(in.segments()!=null && !in.segments().isEmpty(),"请先保存当前井的管网拓扑结构");
        require(in.equipment()!=null,"请加载当前井拓扑中的设备");
        finite(in.inletMpa()==null?0:in.inletMpa(),in.outletMpa()==null?0:in.outletMpa(),in.rate10k()==null?0:in.rate10k(),in.inletC(),in.gasGravity(),in.z(),in.viscosityMpaS(),in.cpJkgK(),jt,in.standardPressurePa(),in.standardTemperatureK(),in.standardZ());
        require((in.inletMpa()==null||in.inletMpa()>0)&&(in.outletMpa()==null||in.outletMpa()>0)
                &&(in.rate10k()==null||in.rate10k()>0),"解析后的压力和流量必须为正数");
        require((in.target().equals("rate") || (in.rate10k()!=null && in.rate10k()>0)) && in.standardPressurePa()>0 && in.standardTemperatureK()>0 && in.standardZ()>0, "流量及标况参数必须为正数");
        require(Set.of("outlet", "inlet", "rate").contains(in.target()), "未知求解目标");
        require(Set.of("isothermal", "heat").contains(in.thermalMode()), "未知温度模型");
        require(Set.of("colebrook", "haaland").contains(in.frictionMethod()), "未知摩阻模型");
        require(in.inletC() > -100 && in.inletC() < 300, "入口温度应在 -100～300℃");
        require((in.target().equals("inlet") || (in.inletMpa()!=null && in.inletMpa() >= .02 && in.inletMpa() <= 100)) && (in.target().equals("outlet") || (in.outletMpa()!=null && in.outletMpa() >= .02 && in.outletMpa() <= 100)),
                "压力应在 0.02～100 MPa（绝压）");
        require(in.gasModel()!=null || (in.gasGravity() >= .3 && in.gasGravity() <= 2 && in.z() >= .1 && in.z() <= 2),
                "请检查气体相对密度和压缩因子");
        require(in.viscosityMpaS() > 0 && in.cpJkgK() > 0 && Double.isFinite(jt), "物性参数无效");
        for (Segment s : in.segments()) {
            finite(s.lengthM(),s.diameterMm(),s.roughnessMm(),s.elevationChangeM(),s.ambientC(),s.heatTransferWm2K());
            require(s.lengthM()>0 && s.diameterMm()>0 && s.roughnessMm()>=0 && s.heatTransferWm2K()>=0, s.name()+"：管段几何或传热参数无效");
            require(s.lengthM() <= 1000000 && s.diameterMm() <= 5000 && s.roughnessMm() < s.diameterMm()/10,
                    s.name()+"：长度、管径或粗糙度超出适用范围");
            require(Math.abs(s.elevationChangeM()) <= s.lengthM(), s.name()+"：高程差不能大于管段长度");
            require(s.ambientC() > -100 && s.ambientC() < 300, s.name()+"：环境温度超出范围");
        }
        for (Equipment e : in.equipment()) {
            finite(e.lossK(),e.pressureRatio(),e.efficiency(),e.maxPressureMpa(),e.maxPowerKw());
            require(Set.of("valve", "compressor").contains(e.type()), "未知设备类型");
            require(e.afterSegment()>=0 && e.afterSegment() < in.segments().size(), "设备位置对应的管段不存在");
            require(e.lossK()>=0 && e.maxPressureMpa()>0 && e.maxPowerKw()>0, "设备阻力或运行限值无效");
            require(e.pressureRatio() >= 1 && e.pressureRatio() <= 5, "压缩比应在 1～5");
            require(e.efficiency() > 0 && e.efficiency() <= 1, "压缩机效率应在 0～1");
        }

    }

    public Result calculate(Input in) {
        validate(in);
        FlowGas gas=new FlowGas(in);
        double maximumPressure=1e8;
        double pin = in.inletMpa()==null?0:in.inletMpa()*1e6, rate = in.rate10k()==null?0:in.rate10k(), target = in.outletMpa()==null?0:in.outletMpa()*1e6;
        int iterations = 1;
        double residual = 0;
        if (!in.target().equals("outlet")) {
            double low, high;
            if (in.target().equals("inlet")) {
                low = MIN_P; high = Math.min(Math.max(target*1.2, 1e6),maximumPressure);
                while (terminal(in, high, rate,gas) < target && high < maximumPressure) high = Math.min(high*2, maximumPressure);
                require(terminal(in, low, rate,gas) < target && terminal(in, high, rate,gas) >= target, "给定边界在压力适用范围内无解");
            } else {
                low = .000001; high = Math.max(rate, 1);
                require(terminal(in, pin, low,gas) > target, "给定两端压力不支持正向输送，请检查高程、设备和压力边界");
                while (terminal(in, pin, high,gas) > target && high < 100000) high *= 2;
                require(terminal(in, pin, high,gas) <= target, "在本模型流量范围内无法找到解");
            }
            for (iterations = 1; iterations <= 80; iterations++) {
                double mid = (low+high)/2;
                double p = in.target().equals("inlet") ? terminal(in, mid, rate,gas) : terminal(in, pin, mid,gas);
                if (in.target().equals("inlet")) pin = mid; else rate = mid;
                residual = Math.abs(p-target)/1e6;
                if (residual < .000001) break;
                if (in.target().equals("inlet") ? p < target : p > target) low = mid; else high = mid;
            }
            require(iterations <= 80, "求解未收敛，请检查边界条件");
        }
        Trace trace;
        try { trace = forward(in, pin, rate,gas); }
        catch (FlowLimit e) { throw new BusinessException(400,"工况超出低速单相模型适用范围：压力或温度越界、流速过高，请调整边界"); }
        catch (PressureCeiling e) { throw new BusinessException(400,"沿程或设备出口压力超出当前管流计算的 100 MPa 上限，请调整边界或压缩比"); }
        List<Assessment> checks = assess(in, trace);
        Point last = trace.points.getLast();
        return new Result(VERSION, pin/1e6, last.pressureMpa(), rate, last.temperatureC(),
                pin/1e6-last.pressureMpa(), trace.points.stream().mapToDouble(Point::velocityMs).max().orElse(0),
                trace.points.stream().mapToDouble(Point::temperatureC).min().orElse(0),
                trace.equipment.stream().mapToDouble(EquipmentResult::powerKw).sum(), iterations, residual,
                trace.points, trace.equipment, checks,
                List.of(in.gasModel()==null ? "稳态单相气体、串联管线；使用给定的固定 Z、黏度、比热和焦耳—汤姆逊系数。"
                        : "稳态单相气体、串联管线；"+in.gasModel().method()+" 按每步平均温压迭代更新 Z、密度和 Cp；Standing（资料原式）同时更新黏度与摩阻。气体导热系数和 JT 按给定值。",
                        "Darcy 摩阻模型；Re 2300～4000 使用连续过渡插值；忽略加速项，限制局部等效马赫数 < 0.3。",
                        "设备校核包含允许压力与压缩机额定功率；水合物由管网层按同点温压作经验筛查，冲蚀、冻堵暂不启用。"));
    }

    // In inverse solves, exceeding the subsonic model range is a lower terminal-pressure bracket.
    private double terminal(Input in, double pin, double rate,FlowGas gas) {
        try { return forward(in,pin,rate,gas).points.getLast().pressureMpa()*1e6; }
        catch (FlowLimit e) { return 0; }
        // A pressure ceiling at a compressor is an upper bracket, never a pressure-collapse bracket.
        catch (PressureCeiling e) { return Double.POSITIVE_INFINITY; }
    }
    private static class FlowLimit extends RuntimeException {}
    private static class PressureCeiling extends RuntimeException {}
    private static class Trace {
        final List<Point> points = new ArrayList<>();
        final List<EquipmentResult> equipment = new ArrayList<>();
    }
    private static class FlowGas {
        final Input input;final PipelineGasProperties.Prepared eos;final double r,standardDensity;
        FlowGas(Input in) {
            input=in;
            if(in.gasModel()==null) {
                eos=null;r=R_AIR/in.gasGravity();
                standardDensity=in.standardPressurePa()/(in.standardZ()*r*in.standardTemperatureK());
            } else {
                eos=new PipelineGasProperties().prepare(in.gasModel().method(),in.gasModel().composition());
                var standard=eos.at(in.standardPressurePa()/1e6,in.standardTemperatureK()-273.15);
                r=8.31446261815324/standard.molarMassKgMol();standardDensity=standard.densityKgM3();
            }
        }
        PipelineGasProperties.State at(double p,double t) {
            if(p>1e8)throw new PressureCeiling();
            return eos==null ? new PipelineGasProperties.State(input.z(),input.cpJkgK(),p/(input.z()*r*t),8.31446261815324/r)
                    : eos.at(p/1e6,t-273.15);
        }
        double viscosity(double p,double t) {
            return eos==null ? input.viscosityMpaS() : eos.standingViscosity(p/1e6,t-273.15).viscosityMpaS();
        }
    }
    private Trace forward(Input in, double pin, double rate,FlowGas gas) {
        Trace trace = new Trace();
        double r = gas.r;
        double mass = rate/8.64 * gas.standardDensity;
        double p=pin, t=in.inletC()+273.15, distance=0;
        for (int index=0; index<in.segments().size(); index++) {
            Segment s=in.segments().get(index);
            double d=s.diameterMm()/1000, area=Math.PI*d*d/4;
            addPoint(trace,in,index,s.name()+" 起点",distance,p,t,mass,area,gas);
            int steps=Math.max(40,Math.min(400,(int)Math.ceil(s.lengthM()/50)));
            double dx=s.lengthM()/steps, dz=s.elevationChangeM()/steps;
            for (int k=0;k<steps;k++) {
                double guessP=p,guessT=t,next=p,nextT=t;
                boolean converged=false;
                for(int iteration=0;iteration<(gas.eos==null?1:40);iteration++) {
                    double tm=gas.eos==null?t:(t+guessT)/2;
                    double pm=(p+guessP)/2;
                    var state=gas.at(pm,tm);
                    double re=mass*d/(area*gas.viscosity(pm,tm)*.001);
                    double f=friction(re,s.roughnessMm()/s.diameterMm(),in.frictionMethod());
                    // Exact p² update for the current mean-state coefficients, including elevation.
                    double a=f*mass*mass*state.z()*r*tm/(d*area*area);
                    double b=2*9.80665*dz/(dx*state.z()*r*tm);
                    double decay=Math.exp(-b*dx);
                    double p2=Math.abs(b*dx)<1e-8 ? p*p-a*dx : p*p*decay+a*Math.expm1(-b*dx)/b;
                    if (!Double.isFinite(p2) || p2<MIN_P*MIN_P) throw new FlowLimit();
                    next=Math.sqrt(p2);nextT=t;
                    if (in.thermalMode().equals("heat")) {
                        double ambient=s.ambientC()+273.15;
                        double exponent=s.heatTransferWm2K()*Math.PI*d*dx/(mass*state.cpJkgK());
                        nextT=stepTemperature(t,ambient,exponent,in.jtKmpa(),(next-p)/1e6);
                    }
                    if(gas.eos==null || (Math.abs(next-guessP)<.1 && Math.abs(nextT-guessT)<1e-6)) {converged=true;break;}
                    guessP=(guessP+next)/2;guessT=(guessT+nextT)/2;
                }
                require(converged,s.name()+"：温压与物性迭代未收敛，请检查工况或减小管段长度");
                p=next;t=nextT;distance+=dx;
                addPoint(trace,in,index,s.name(),distance,p,t,mass,area,gas);
            }
            for (Equipment e:in.equipment()) {
                if (e.afterSegment()!=index) continue;
                double before=p, power=0;
                var state=gas.at(p,t);
                if (e.type().equals("valve")) {
                    double rho=state.densityKgM3(), velocity=mass/(rho*area);
                    p-=e.lossK()*rho*velocity*velocity/2;
                    if(in.thermalMode().equals("heat")) t+=in.jtKmpa()*(p-before)/1e6;
                } else {
                    // Effective ideal-gas compression using the equipment inlet EOS state.
                    double exponent=state.z()*r/state.cpJkgK();
                    require(exponent<1, "比热必须大于 Z×气体比气体常数");
                    double delta=t*(Math.pow(e.pressureRatio(),exponent)-1)/e.efficiency();
                    power=mass*state.cpJkgK()*delta/1000;
                    t+=delta; p*=e.pressureRatio();
                }
                addPoint(trace,in,index,e.name()+" 出口",distance,p,t,mass,area,gas);
                trace.equipment.add(new EquipmentResult(e.name(),e.type(),distance,before/1e6,p/1e6,t-273.15,power));
            }
        }
        return trace;
    }
    /** Sukhov energy balance with constant coefficients and a linear pressure change in this step. */
    static double stepTemperature(double inletK,double ambientK,double thermalExponent,double jtKmpa,double pressureChangeMpa) {
        require(Double.isFinite(thermalExponent)&&thermalExponent>=0,"管段传热参数导致数值异常，请检查单位与数量级");
        double loss=-Math.expm1(-thermalExponent);
        double jtWeight=thermalExponent==0?1:loss/thermalExponent;
        return inletK+(ambientK-inletK)*loss+jtKmpa*pressureChangeMpa*jtWeight;
    }
    private void addPoint(Trace trace,Input in,int index,String name,double x,double p,double t,double mass,double area,FlowGas gas) {
        if(p<MIN_P || t<100 || t>800 || !Double.isFinite(p+t)) throw new FlowLimit();
        var state=gas.at(p,t);double density=state.densityKgM3(), velocity=mass/(density*area);
        // Conservative isothermal acoustic speed bounds the omitted acceleration term.
        if(velocity/Math.sqrt(state.z()*gas.r*t)>MAX_MACH) throw new FlowLimit();
        var segment=in.segments().get(index);
        double viscosity=gas.viscosity(p,t);
        double re=mass*(segment.diameterMm()/1000)/(area*viscosity*.001);
        double f=friction(re,segment.roughnessMm()/segment.diameterMm(),in.frictionMethod());
        trace.points.add(new Point(index,name,x,p/1e6,t-273.15,velocity,density,re,f,state.z(),state.cpJkgK(),viscosity));
    }
    static double friction(double re,double roughness,String method) {
        require("colebrook".equals(method) || "haaland".equals(method),"未知摩阻模型");
        require(Double.isFinite(re) && re>0,"摩阻计算的雷诺数必须为有限正数");
        require(Double.isFinite(roughness) && roughness>=0 && roughness<.1,"摩阻计算的相对粗糙度应在 0～0.1（不含）之间");
        if(re<=2300) {
            double laminar=64/re;
            require(Double.isFinite(laminar),"雷诺数过小，摩阻系数超出数值范围");
            return laminar;
        }
        double reynolds=Math.max(re,4000),a=roughness/3.7,b=2.51/reynolds;
        double inverseRoot=-1.8*Math.log10(Math.pow(a,1.11)+6.9/reynolds);
        if(method.equals("colebrook")) {
            // Teacher's Colebrook equation, solved by safeguarded Newton in x=1/sqrt(lambda).
            // F(x)=x+2*log10(a+b*x) is increasing. These bounds contain its positive root.
            double low=0,high=Math.max(1,-2*Math.log10(b));
            inverseRoot=Math.min(inverseRoot,high);
            boolean converged=false;
            for(int i=0;i<80;i++) {
                double argument=a+b*inverseRoot;
                double residual=inverseRoot+2*Math.log10(argument);
                if(Math.abs(residual)<=1e-12) {converged=true;break;}
                if(residual<0) low=inverseRoot; else high=inverseRoot;
                double derivative=1+2/Math.log(10)*(b/argument);
                double next=inverseRoot-residual/derivative;
                inverseRoot=Double.isFinite(next) && next>low && next<high ? next : (low+high)/2;
            }
            require(converged,"Colebrook 摩阻系数迭代未收敛，请检查雷诺数与相对粗糙度");
        }
        double turbulent=1/(inverseRoot*inverseRoot);
        if(re<4000) return (64.0/2300)*(4000-re)/1700+turbulent*(re-2300)/1700;
        return turbulent;
    }
    private List<Assessment> assess(Input in,Trace trace) {
        List<Assessment> checks=new ArrayList<>();
        int equipmentIndex=0;
        for(int s=0;s<in.segments().size();s++) for(Equipment e:in.equipment()) {
            if(e.afterSegment()!=s) continue;
            EquipmentResult er=trace.equipment.get(equipmentIndex++);
            double pressure=Math.max(er.inletMpa(),er.outletMpa());
            checks.add(new Assessment("equipment",e.name(),er.distanceM(),pressure<=e.maxPressureMpa()?"pass":"fail",
                    pressure,e.maxPressureMpa(),e.maxPressureMpa()-pressure,"MPa","设备两端最大压力 / 允许压力"));
            if(e.type().equals("compressor")) checks.add(new Assessment("equipment",e.name(),er.distanceM(),er.powerKw()<=e.maxPowerKw()?"pass":"fail",
                    er.powerKw(),e.maxPowerKw(),e.maxPowerKw()-er.powerKw(),"kW","所需功率 / 额定功率；未评价特性曲线边界"));
        }
        if(trace.equipment.isEmpty()) checks.add(new Assessment("equipment","全线",0,"not_evaluated",null,null,null,"","未配置关键设备"));
        return checks;
    }
    private static void require(boolean condition,String message) { if(!condition) throw new BusinessException(400,message); }
    private static void finite(double... values) { for(double value:values) require(Double.isFinite(value), "输入必须是有限数值"); }
}
