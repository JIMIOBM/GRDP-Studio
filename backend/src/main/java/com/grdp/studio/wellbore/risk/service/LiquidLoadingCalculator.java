package com.grdp.studio.wellbore.risk.service;

import com.grdp.studio.wellbore.risk.dto.LiquidLoadingRequest;
import com.grdp.studio.wellbore.risk.dto.LiquidLoadingResult;
import org.springframework.stereotype.Service;

/** Java 等价迁移自 critical_liquid_carrying_algorithm.js。 */
@Service
public class LiquidLoadingCalculator {
    public LiquidLoadingResult calculate(LiquidLoadingRequest input) {
        double temperatureK = input.temperatureC() + 273.15;
        double z = zFactor(input.pressureMpa(), temperatureK, input.gasSpecificGravity());
        double rhoGas = gasDensity(input.pressureMpa(), temperatureK, input.gasSpecificGravity());
        Velocities critical = criticalVelocity(input.surfaceTensionMnM(), input.liquidDensityKgM3(), rhoGas);
        double actual = actualVelocity(input.qg(), input.pressureMpa(), temperatureK,
                input.gasSpecificGravity(), input.tubingIdMm());
        double ratioTurner = actual / critical.turner;
        double ratioTurner20 = actual / critical.turner20;
        double ratioLiMin = actual / critical.liMin;
        double criticalRate = criticalRate(critical.turner20, input.pressureMpa(), temperatureK,
                input.gasSpecificGravity(), input.tubingIdMm());
        String level;
        String levelKey;
        if (ratioTurner20 >= 1.0) { level = "不积液"; levelKey = "ok"; }
        else if (ratioTurner20 >= 0.8) { level = "轻度积液"; levelKey = "warn"; }
        else { level = "重度积液"; levelKey = "danger"; }

        return new LiquidLoadingResult(input.wellName(), input.qg(), input.qw(), input.pressureMpa(),
                input.temperatureC(), input.gasSpecificGravity(), input.liquidDensityKgM3(),
                round(rhoGas, 2), round(z, 3), input.surfaceTensionMnM(), input.tubingIdMm(),
                round(actual, 3), round(critical.turner20, 3), round(criticalRate, 2),
                round(ratioTurner20, 3), round(critical.turner, 3), round(critical.turner20, 3),
                round(critical.liMin, 3), round(ratioTurner, 3), round(ratioTurner20, 3),
                round(ratioLiMin, 3), level, levelKey);
    }

    double zFactor(double pressureMpa, double temperatureK, double gammaG) {
        PseudoCritical pseudo = pseudoCritical(pressureMpa, temperatureK, gammaG);
        double ppr = pseudo.ppr;
        double tpr = pseudo.tpr;
        if (!(ppr > 0) || !(tpr > 0)) return 0.9;
        double a1=0.3265,a2=-1.0700,a3=-0.5339,a4=0.01569,a5=-0.05165,a6=0.5475;
        double a7=-0.7361,a8=0.1844,a9=0.1056,a10=0.6134,a11=0.7210;
        double invT=1/tpr, invT2=invT*invT, invT3=invT2*invT, invT4=invT3*invT, invT5=invT4*invT;
        double r1=a1+a2*invT+a3*invT3+a4*invT4+a5*invT5;
        double r2=0.27*ppr/tpr;
        double r3=a6+a7*invT+a8*invT2;
        double r4=a9*(a7*invT+a8*invT2);
        double r5=a10*invT3;
        double rho=0.27*ppr/tpr;
        for (int i=0;i<50;i++) {
            if (rho<=0) rho=1e-5;
            double rho2=rho*rho, rho4=rho2*rho2, rho5=rho4*rho;
            double exp=Math.exp(-a11*rho2);
            double f=r1*rho-r2/rho+r3*rho2-r4*rho5+r5*rho2*(1+a11*rho2)*exp+1;
            double bracket=(1+2*a11*rho2)-a11*rho2*(1+a11*rho2);
            double df=r1+r2/rho2+2*r3*rho-5*r4*rho4+2*r5*rho*exp*bracket;
            if (Math.abs(df)<1e-10) break;
            double next=rho-f/df;
            if (Math.abs(next-rho)<1e-8) {
                double z=0.27*ppr/(next*tpr);
                return Double.isFinite(z)&&z>0 ? z : 0.9;
            }
            rho=next;
        }
        return 0.9;
    }

    private PseudoCritical pseudoCritical(double pressureMpa, double temperatureK, double gammaG) {
        double pressurePsi=pressureMpa*1e6*0.000145038;
        double temperatureRankine=temperatureK*1.8;
        double ppc=678-50*(gammaG-0.5);
        double tpc=326+315.7*(gammaG-0.5);
        return new PseudoCritical(pressurePsi/ppc, Math.max(temperatureRankine/tpc,1.0));
    }

    private double gasDensity(double p, double t, double gamma) {
        return p*1e6*(gamma*0.02896)/(zFactor(p,t,gamma)*8.314*t);
    }

    private Velocities criticalVelocity(double sigma, double rhoL, double rhoG) {
        double base=Math.pow(((sigma/1000)*Math.max(rhoL-rhoG,0.1))/(rhoG*rhoG),0.25);
        return new Velocities(Math.max(6.56*base,0),Math.max(7.872*base,0),Math.max(2.50*base,0));
    }

    private double actualVelocity(double qg,double p,double t,double gamma,double diameterMm) {
        double z=zFactor(p,t,gamma);
        double area=Math.PI*Math.pow(diameterMm/1000,2)/4;
        double actual=qg*10000*(0.101325/p)*(t/293.15)*(1/z);
        return actual/86400/area;
    }

    private double criticalRate(double velocity,double p,double t,double gamma,double diameterMm) {
        double z=zFactor(p,t,gamma);
        double area=Math.PI*Math.pow(diameterMm/1000,2)/4;
        return velocity*area*(p/0.101325)*(293.15/t)*z*86400/10000;
    }

    private static double round(double value,int scale) {
        double factor=Math.pow(10,scale);
        return Math.round(value*factor)/factor;
    }

    private record PseudoCritical(double ppr,double tpr) {}
    private record Velocities(double turner,double turner20,double liMin) {}
}
