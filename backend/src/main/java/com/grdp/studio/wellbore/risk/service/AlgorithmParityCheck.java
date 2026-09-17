package com.grdp.studio.wellbore.risk.service;

import com.grdp.studio.wellbore.risk.dto.HydrateRequest;
import com.grdp.studio.wellbore.risk.dto.LiquidLoadingRequest;

import java.util.LinkedHashMap;

/** Development-only executable parity check; automated assertions live in WellboreRiskAlgorithmTest. */
public final class AlgorithmParityCheck {
    private AlgorithmParityCheck() {}
    public static void main(String[] args) {
        var liquid=new LiquidLoadingCalculator().calculate(new LiquidLoadingRequest(null,null,"A1",2.5,2.0,3.8,30.0,0.65,1000.0,60.0,62.0));
        require(liquid.gasDensityKgM3()==31.11&&liquid.zFactor()==0.912&&liquid.actualVelocityMs()==2.897&&liquid.criticalVelocityMs()==3.897&&liquid.criticalRate1e4M3d()==3.36&&liquid.ratio()==0.743,"liquid fixture mismatch: "+liquid);
        var liquid2=new LiquidLoadingCalculator().calculate(new LiquidLoadingRequest(null,null,"A2",10.0,0.0,10.0,50.0,0.75,950.0,45.0,76.0));
        require(liquid2.gasDensityKgM3()==104.39&&liquid2.zFactor()==0.774&&liquid2.actualVelocityMs()==3.68&&liquid2.criticalVelocityMs()==1.914&&liquid2.ratio()==1.923,"liquid fixture 2 mismatch: "+liquid2);
        var liquid3=new LiquidLoadingCalculator().calculate(new LiquidLoadingRequest(null,null,"A3",0.1,null,1.0,20.0,0.55,800.0,26.0,50.0));
        require(liquid3.gasDensityKgM3()==6.66&&liquid3.zFactor()==0.981&&liquid3.criticalRate1e4M3d()==1.07&&liquid3.ratio()==0.094,"liquid fixture 3 mismatch: "+liquid3);
        var composition=new LinkedHashMap<String,Double>();composition.put("CH4",80.52);composition.put("C2H6",0.04);composition.put("HE",0.05);composition.put("H2",0.02);composition.put("N2",0.75);composition.put("CO2",6.97);composition.put("H2S",11.68);
        var hydrate=new HydrateCalculator().calculate(new HydrateRequest(null,null,"A1",null,null,null,composition,50.0,40.0,2.0));
        require(Math.abs(hydrate.temperatureK()-311.7100386680372)<1e-9&&Math.abs(hydrate.hydrateTemperatureC()-32.77603286783165)<1e-9&&Math.abs(hydrate.temperatureMarginC()-7.223967132168347)<1e-9,"hydrate fixture mismatch: "+hydrate);
        var gas2=new LinkedHashMap<String,Double>();gas2.put("CH4",90.0);gas2.put("CO2",5.0);gas2.put("N2",5.0);
        var hydrate2=new HydrateCalculator().calculate(new HydrateRequest(null,null,"A2",null,null,null,gas2,10.0,20.0,2.0));
        require(Math.abs(hydrate2.temperatureK()-296.15977291691513)<1e-9&&Math.abs(hydrate2.temperatureMarginC()-0.4416930206221217)<1e-9,"hydrate fixture 2 mismatch: "+hydrate2);
        var gas3=new LinkedHashMap<String,Double>();gas3.put("C1",0.95);gas3.put("C2",0.03);gas3.put("C3",0.02);
        var hydrate3=new HydrateCalculator().calculate(new HydrateRequest(null,null,"A3",null,null,null,gas3,5.0,15.0,1.0));
        require(Math.abs(hydrate3.temperatureK()-281.6323641687846)<1e-9&&Math.abs(hydrate3.temperatureMarginC()-7.789990456533078)<1e-9,"hydrate fixture 3 mismatch: "+hydrate3);
        System.out.println("Original JS/Python fixtures matched.");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
