package com.grdp.studio.wellbore.risk.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.wellbore.risk.dto.HydrateRequest;
import com.grdp.studio.wellbore.risk.dto.HydrateResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Java 等价迁移自 single_point_hydrate_prediction.py。 */
@Service
public class HydrateCalculator {
    private static final double EPS=1e-12;
    private static final double CORRECTION=0.85;
    private static final String METHOD="Revised Du-Guo / hydT2 statistical thermodynamic model";
    private static final List<String> HYD_COMPONENTS=List.of("C1","C2","C3","IC4","NC4","O2","N2","CO2","H2S");
    private static final Map<String,String> ALIASES=aliases();
    private static final Map<String,Gas> GASES=gases();
    private static final Map<Integer,Map<String,Triple>> CAVES=caves();
    private static final Map<String,Pair> A0B0=a0b0();

    public HydrateResult calculate(HydrateRequest request) {
        double pressure=request.pressureMpa();
        double actual=request.actualTemperatureC();
        double fugacityScale=request.effectiveFugacityScale();
        LinkedHashMap<String,Double> normalized=parseComposition(request.composition());
        if (normalized.isEmpty()) throw new BusinessException(400,"请输入有效组分");

        LinkedHashMap<String,Double> matched=new LinkedHashMap<>();
        LinkedHashMap<String,Double> dropped=new LinkedHashMap<>();
        normalized.forEach((name,value)->{
            if (HYD_COMPONENTS.contains(name)) matched.put(name,value); else dropped.put(name,value);
        });
        if (matched.isEmpty()) throw new BusinessException(400,"未识别到可参与 Du-Guo 统计热力学法的组分");

        double temperatureK=hydT2(normalized,pressure,fugacityScale);
        double rawC=temperatureK-273.15;
        double hydrateC=rawC*CORRECTION;
        double margin=actual-hydrateC;
        String level;
        String description;
        if (margin<0) { level="高风险"; description=String.format(Locale.ROOT,"低于生成温度 %.2f °C",Math.abs(margin)); }
        else if (margin<5) { level="临界风险"; description=String.format(Locale.ROOT,"安全裕量 %.2f °C",margin); }
        else { level="较安全"; description=String.format(Locale.ROOT,"安全裕量 %.2f °C",margin); }
        return new HydrateResult(pressure,temperatureK,rawC,hydrateC,actual,margin,level,description,
                matched,dropped,METHOD,fugacityScale,CORRECTION);
    }

    LinkedHashMap<String,Double> parseComposition(Map<String,Double> composition) {
        LinkedHashMap<String,Double> merged=new LinkedHashMap<>();
        if (composition==null) return merged;
        composition.forEach((rawName,rawValue)->{
            String key=rawName==null ? "" : rawName.trim().toUpperCase(Locale.ROOT).replace(" ","");
            String name=ALIASES.getOrDefault(key,"");
            if (!name.isEmpty() && rawValue!=null && Double.isFinite(rawValue) && rawValue>0)
                merged.merge(name,rawValue,Double::sum);
        });
        if (merged.isEmpty()) return merged;
        double total=merged.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total>1.5) merged.replaceAll((name,value)->value/100.0);
        total=merged.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total<=0) return new LinkedHashMap<>();
        final double divisor=total;
        merged.replaceAll((name,value)->value/divisor);
        return merged;
    }

    private double hydT2(LinkedHashMap<String,Double> input,double pressure,double fugacityScale) {
        if (pressure<=0) throw new BusinessException(400,"压力必须大于 0 MPa");
        if (fugacityScale<=0) throw new BusinessException(400,"逸度缩放系数必须大于 0");
        List<String> names=HYD_COMPONENTS.stream().filter(input::containsKey).toList();
        double matchedTotal=names.stream().mapToDouble(input::get).sum();
        List<Double> values=names.stream().map(name->input.get(name)/matchedTotal).toList();
        double initial=6.38*Math.log(9.869*pressure)+262.0;
        double structure1=calculateStructure(1,initial,names,values,pressure,fugacityScale);
        double structure2=calculateStructure(2,structure1,names,values,pressure,fugacityScale);
        return Math.min(structure1,structure2);
    }

    private double calculateStructure(int structure,double initial,List<String> names,List<Double> values,
                                      double pressure,double fugacityScale) {
        List<Double> temperatures=new ArrayList<>();
        List<Double> residuals=new ArrayList<>();
        temperatures.add(initial);
        for (int iteration=1;iteration<=110;iteration++) {
            double temperature=temperatures.getLast();
            Map<String,Double> fugacity=pengRobinson(names,values,temperature,pressure).gasFugacity;
            int small=structure==1?1:3, big=structure==1?2:4;
            List<Double> langSmall=new ArrayList<>(),langBig=new ArrayList<>();
            for (String name:names) {
                langSmall.add(langmuir(CAVES.get(small).get(name),temperature));
                langBig.add(langmuir(CAVES.get(big).get(name),temperature));
            }
            double totalSmall=0,totalBig=0;
            for (int i=0;i<names.size();i++) {
                totalSmall+=1e3*fugacity.getOrDefault(names.get(i),0.0)*fugacityScale*langSmall.get(i);
                totalBig+=1e3*fugacity.getOrDefault(names.get(i),0.0)*fugacityScale*langBig.get(i);
            }
            double occSmall=0,occBig=0;
            for (int i=0;i<names.size();i++) {
                occSmall+=1e3*fugacity.getOrDefault(names.get(i),0.0)*fugacityScale*langSmall.get(i)/Math.max(1+totalSmall,EPS);
                occBig+=1e3*fugacity.getOrDefault(names.get(i),0.0)*fugacityScale*langBig.get(i)/Math.max(1+totalBig,EPS);
            }
            double waterActivity=1.0;
            if (temperature>273.15) {
                double dissolved=0;
                for (String name:names) {
                    Pair p=A0B0.get(name);
                    dissolved+=Math.exp(p.a+p.b/temperature)*fugacity.getOrDefault(name,0.0)*fugacityScale
                            *Math.exp(32.0*(1.0-pressure)/(82.06*temperature));
                }
                waterActivity=1.0-dissolved;
            }
            waterActivity=clamp(waterActivity,1e-10,1.0);
            occSmall=clamp(occSmall,0,1.0-1e-10);
            occBig=clamp(occBig,0,1.0-1e-10);
            double deltaH,deltaV,deltaCp,coefficientB,chemical1,chemical2;
            if (structure==1) {
                if (temperature>273.15) { deltaH=1714-6011; deltaV=2.9959+1.6; deltaCp=-34.583; coefficientB=0.189; }
                else { deltaH=1714; deltaV=2.9959; deltaCp=3.315; coefficientB=0.0121; }
                chemical1=1120.0/(8.314*273.15)-(deltaCp*273.15-deltaH-coefficientB*273.15*273.15/2.0)
                        /8.314*(1.0/temperature-1.0/273.15);
                chemical2=-Math.log(1-occSmall)/23.0-3.0*Math.log(1-occBig)/23.0+Math.log(waterActivity);
            } else {
                if (temperature>273.15) { deltaH=1400-6011; deltaV=3.39644+1.6; deltaCp=-36.8607; coefficientB=0.1809; }
                else { deltaH=1400; deltaV=3.39644; deltaCp=1.029; coefficientB=0.00377; }
                chemical1=931.0/(8.314*273.15)-(deltaCp*273.15-deltaH-coefficientB*273.15*273.15/2.0)
                        /8.314*(1.0/temperature-1.0/273.15);
                chemical2=-2.0*Math.log(1-occSmall)/17.0-Math.log(1-occBig)/17.0+Math.log(waterActivity);
            }
            chemical1=chemical1-Math.log(waterActivity)+deltaV/(8.314*temperature)*(pressure-0.01035)
                    -(deltaCp-coefficientB*273.15)/8.314*Math.log(temperature/273.15)
                    -coefficientB/(2.0*8.314)*(temperature-273.15);
            double residual=chemical1-chemical2;
            residuals.add(residual);
            if (Math.abs(residual)<=1e-4) return temperature;
            if (iteration>100) throw new BusinessException(422,"统计热力学法迭代未收敛");
            double next;
            if (iteration<=2) next=0.98999*temperature;
            else {
                double previousTemperature=temperatures.get(temperatures.size()-2);
                double previousResidual=residuals.get(residuals.size()-2);
                double denominator=residual-previousResidual;
                double step=Math.abs(denominator)<1e-12 ? -0.005*temperature
                        : -(temperature-previousTemperature)*residual/denominator;
                while (Math.abs(step)-0.01*temperature>0) step*=0.5;
                next=temperature+step;
                if (Math.abs(step)<1e-4) return next;
            }
            temperatures.add(next);
        }
        return temperatures.getLast();
    }

    private PrResult pengRobinson(List<String> names,List<Double> y,double temperature,double pressure) {
        double r=0.008314;
        int count=names.size();
        double[] aa=new double[count],bb=new double[count],alpha=new double[count],psi=new double[count];
        for (int i=0;i<count;i++) {
            Gas gas=GASES.get(names.get(i));
            double m=0.37464+1.54226*gas.omega-0.26992*gas.omega*gas.omega;
            aa[i]=0.45724*r*r*gas.tc*gas.tc/gas.pc;
            bb[i]=0.0778*r*gas.tc/gas.pc;
            alpha[i]=Math.pow(1+m*(1-Math.sqrt(temperature/gas.tc)),2);
        }
        double sumAA=0,sumB=0;
        for (int i=0;i<count;i++) {
            for (int j=0;j<count;j++) {
                double pair=Math.sqrt(aa[i]*aa[j]*alpha[i]*alpha[j]);
                sumAA+=y.get(i)*y.get(j)*pair;
                psi[i]+=y.get(j)*pair;
            }
            sumB+=y.get(i)*bb[i];
        }
        if (sumB<=EPS||sumAA<=EPS) throw new BusinessException(400,"PR 方程混合参数无效，请检查组分输入");
        double a=sumAA*pressure/(r*r*temperature*temperature);
        double b=sumB*pressure/(r*temperature);
        double[] roots=factorz(b-1,a-b*(2+3*b),b*(b*b+b-a));
        double zGas=roots[0],zLiquid=roots[1]<0?roots[0]:roots[1];
        return new PrResult(zGas,zLiquid,fugacity(names,y,zGas,a,b,bb,psi,sumAA,sumB,pressure));
    }

    private Map<String,Double> fugacity(List<String> names,List<Double> y,double z,double a,double b,
                                          double[] bb,double[] psi,double sumAA,double sumB,double pressure) {
        LinkedHashMap<String,Double> result=new LinkedHashMap<>();
        for (int i=0;i<names.size();i++) {
            double term1=bb[i]*(z-1)/sumB-Math.log(Math.max(z-b,EPS));
            double term2=(2*psi[i]/sumAA-bb[i]/sumB)*a/(2.828*b);
            double logArg=Math.max((z+2.414*b)/Math.max(z-0.414*b,EPS),EPS);
            result.put(names.get(i),y.get(i)*pressure*Math.exp(term1-term2*Math.log(logArg)));
        }
        return result;
    }

    private double[] factorz(double a,double b,double c) {
        double m=b-a*a/3.0;
        double n=2*a*a*a/27.0-a*b/3.0+c;
        double delta=n*n/4.0+m*m*m/27.0;
        double z1,z3;
        if (delta>0) {
            z1=Math.cbrt(-n/2.0+Math.sqrt(delta))+Math.cbrt(-n/2.0-Math.sqrt(delta))-a/3.0;
            z3=z1;
        } else if (Math.abs(delta)<=1e-15) {
            double alpha=Math.cbrt(-n/2.0);
            z1=2*alpha-a/3.0; z3=-alpha-a/3.0;
            if (z1<=z3) { double t=z1;z1=z3;z3=t; }
        } else if (Math.abs(n)<=1e-15) {
            List<Double> roots=new ArrayList<>(List.of(-a/3.0,Math.sqrt(-m)-a/3.0,-Math.sqrt(-m)-a/3.0));
            roots.sort(Comparator.reverseOrder()); z1=roots.get(0);z3=roots.get(2);
        } else {
            double cosfi=-n/(Math.sqrt(Math.pow(Math.abs(m)/3.0,3))*2.0);
            cosfi=clamp(cosfi,-1,1);
            double tgfi=Math.sqrt(Math.max(0,(1-cosfi*cosfi)/Math.max(cosfi*cosfi,EPS)));
            double fi=n>0?Math.PI/2+Math.atan(tgfi):Math.atan(tgfi);
            List<Double> roots=new ArrayList<>(List.of(
                    2*Math.sqrt(-m/3.0)*Math.cos(fi/3.0)-a/3.0,
                    2*Math.sqrt(-m/3.0)*Math.cos(fi/3.0+2*Math.PI/3.0)-a/3.0,
                    2*Math.sqrt(-m/3.0)*Math.cos(fi/3.0+4*Math.PI/3.0)-a/3.0));
            roots.sort(Comparator.reverseOrder()); z1=roots.get(0);z3=roots.get(2);
        }
        return new double[]{z1,z3};
    }

    private static double langmuir(Triple raw,double temperature) {
        if (raw==null) return 0;
        double a=raw.a*1e-3,b=raw.b*1e3,d=raw.d*1e6;
        return a/temperature*Math.exp(b/temperature+d/(temperature*temperature));
    }
    private static double clamp(double value,double min,double max){return Math.min(Math.max(value,min),max);}

    private static Map<String,String> aliases() {
        Map<String,String> m=new LinkedHashMap<>();
        String[][] pairs={{"CH4","C1"},{"C1","C1"},{"C2H6","C2"},{"C2","C2"},{"C3H8","C3"},{"C3","C3"},
                {"IC4","IC4"},{"I-C4","IC4"},{"ISO-C4","IC4"},{"ISOBUTANE","IC4"},{"NC4","NC4"},{"N-C4","NC4"},{"NBUTANE","NC4"},
                {"IC5","IC5"},{"I-C5","IC5"},{"ISO-C5","IC5"},{"NC5","NC5"},{"N-C5","NC5"},{"C6","C6"},
                {"C7+","C7+"},{"C7","C7+"},{"C8","C7+"},{"C9","C7+"},{"HE","HE"},{"HELIUM","HE"},{"N2","N2"},{"O2","O2"},
                {"H2","H2"},{"CO2","CO2"},{"C02","CO2"},{"CO","CO"},{"H2S","H2S"},{"H2O","H2O"},{"WATER","H2O"}};
        for (String[] p:pairs)m.put(p[0],p[1]); return Map.copyOf(m);
    }
    private static Map<String,Gas> gases() {
        Map<String,Gas> m=new LinkedHashMap<>();
        m.put("C1",new Gas(190.55,4.604,0.0126));m.put("C2",new Gas(305.43,4.880,0.0978));m.put("C3",new Gas(369.82,4.249,0.1541));
        m.put("IC4",new Gas(408.13,3.648,0.1840));m.put("NC4",new Gas(425.16,3.797,0.2015));m.put("IC5",new Gas(460.39,3.381,0.2286));
        m.put("NC5",new Gas(469.60,3.369,0.2524));m.put("C6",new Gas(507.40,3.012,0.2998));m.put("C7+",new Gas(540.20,2.736,0.3494));
        m.put("HE",new Gas(5.20,0.277,0));m.put("N2",new Gas(126.10,3.399,0.0372));m.put("O2",new Gas(154.70,5.081,0.0200));
        m.put("H2",new Gas(33.20,0.297,-0.2190));m.put("CO2",new Gas(304.19,7.382,0.2667));m.put("CO",new Gas(132.92,3.499,0.0442));
        m.put("H2S",new Gas(373.50,9.005,0.0920));m.put("H2O",new Gas(647.30,22.118,0.3434));return Map.copyOf(m);
    }
    private static Map<Integer,Map<String,Triple>> caves() {
        Map<Integer,Map<String,Triple>> all=new LinkedHashMap<>();
        all.put(1,map(new Object[][]{{"C1",.0486681,2.495265,.04435273},{"O2",.03148597,2.183719,.04177249},{"N2",.06378685,2.239165,.03749328},{"CO2",.00007543,4.189479,.04384257},{"H2S",.00189176,4.159501,.04612894}}));
        all.put(2,map(new Object[][]{{"C1",.1744596,2.495682,.03298235},{"C2",.00681542,3.973025,.04649971},{"C3",.00069078,3.753454,.05196844},{"O2",.1317687,2.110787,.02953735},{"N2",.2197608,2.019079,.02647857},{"CO2",.00762223,3.663201,.02901211},{"H2S",.2861215,3.871123,.03407918}}));
        all.put(3,map(new Object[][]{{"C1",.0470456,2.47385,.04413609},{"O2",.03007932,2.174343,.04103096},{"N2",.06329998,2.219133,.03879059},{"CO2",.00007154,4.180468,.04341002},{"H2S",.00181608,4.135996,.04594356}}));
        all.put(4,map(new Object[][]{{"C1",.9045891,2.223118,.01560289},{"C2",.1080831,3.991668,.02397593},{"C3",.00180538,5.512835,.03497519},{"IC4",.0000051,7.196937,.04955848},{"NC4",.0000014,6.842408,.04424423},{"O2",.966793,1.846493,.01433612},{"N2",1.200932,1.743634,.01259045},{"CO2",.00076437,2.897102,.01510854},{"H2S",.273724,3.207237,.01569558}}));
        return Map.copyOf(all);
    }
    private static Map<String,Triple> map(Object[][] rows){Map<String,Triple> m=new LinkedHashMap<>();for(Object[] r:rows)m.put((String)r[0],new Triple((double)r[1],(double)r[2],(double)r[3]));return Map.copyOf(m);}
    private static Map<String,Pair> a0b0(){Map<String,Pair> m=new LinkedHashMap<>();m.put("C1",new Pair(-15.826277,1559.0631));m.put("C2",new Pair(-18.400368,2410.4807));m.put("C3",new Pair(-20.958631,3109.3910));m.put("IC4",new Pair(-20.108263,2739.7313));m.put("NC4",new Pair(-22.150557,3407.2181));m.put("O2",new Pair(-17.160634,1914.1440));m.put("N2",new Pair(-17.934347,1933.3810));m.put("CO2",new Pair(-15.103508,2603.9795));m.put("H2S",new Pair(-14.283146,2050.3267));return Map.copyOf(m);}
    private record Gas(double tc,double pc,double omega){}
    private record Triple(double a,double b,double d){}
    private record Pair(double a,double b){}
    private record PrResult(double zGas,double zLiquid,Map<String,Double> gasFugacity){}
}
