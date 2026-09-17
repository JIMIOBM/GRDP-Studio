package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.grdp.studio.pipeline.PipelineDtos.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineCalculatorTests {
    private final PipelineCalculator calculator=new PipelineCalculator();
    static Input example() {
        return new Input("outlet","isothermal","colebrook",6.0,4.0,8.0,45.0,.65,.9,.012,2300.0,3.0,101325.0,293.15,1.0,
                List.of(new Segment("水平管",4000,100,.03,0,15,2)),List.of(),
                null);
    }
    static Input modify(Input i,String target,double pin,double pout,double q,String thermal,List<Segment> segments,List<Equipment> equipment,Constraints constraints) {
        return new Input(target,thermal,i.frictionMethod(),pin,pout,q,i.inletC(),i.gasGravity(),i.z(),i.viscosityMpaS(),i.cpJkgK(),i.jtKmpa(),i.standardPressurePa(),i.standardTemperatureK(),i.standardZ(),segments,equipment,constraints);
    }
    @Test void horizontalIsothermalMatchesIntegratedDarcyEquation() {
        Input i=example(); Result result=calculator.calculate(i);
        double r=287.05/i.gasGravity(),t=i.inletC()+273.15,d=.1,area=Math.PI*d*d/4;
        double mass=i.rate10k()/8.64*i.standardPressurePa()/(i.standardZ()*r*i.standardTemperatureK());
        // Independent expected p² balance, including correct standard-to-actual conversion.
        double f=result.points().getFirst().frictionFactor();
        double expected=Math.sqrt(6e6*6e6-f*4000*mass*mass*i.z()*r*t/(d*area*area))/1e6;
        assertEquals(expected,result.outletMpa(),1e-10);
        assertEquals(45,result.outletC(),1e-10);
        for(Point p:result.points()) assertEquals(mass,p.densityKgM3()*p.velocityMs()*area,1e-10);
    }
    @Test void forwardAndBothInverseDirectionsRecoverSameOperatingPoint() {
        Input i=example(); Result forward=calculator.calculate(i);
        Result inverse=calculator.calculate(modify(i,"inlet",2,forward.outletMpa(),8,"isothermal",i.segments(),i.equipment(),i.constraints()));
        Result flow=calculator.calculate(modify(i,"rate",6,forward.outletMpa(),2,"isothermal",i.segments(),i.equipment(),i.constraints()));
        assertEquals(6,inverse.inletMpa(),2e-6); assertEquals(8,flow.rate10k(),.0002);
    }
    @Test void splittingUniformPipePreservesSolution() {
        Input i=example(); Result whole=calculator.calculate(i);
        Result split=calculator.calculate(modify(i,"outlet",6,4,8,"isothermal",List.of(new Segment("A",2000,100,.03,0,15,2),new Segment("B",2000,100,.03,0,15,2)),List.of(),i.constraints()));
        assertEquals(whole.outletMpa(),split.outletMpa(),1e-9);
    }
    @Test void heatLossCoolsAndElevationConsumesPressure() {
        Input i=example(); Result plain=calculator.calculate(i);
        Result heat=calculator.calculate(modify(i,"outlet",6,4,8,"heat",i.segments(),List.of(),i.constraints()));
        Result uphill=calculator.calculate(modify(i,"outlet",6,4,8,"isothermal",List.of(new Segment("上坡",4000,100,.03,200,15,2)),List.of(),i.constraints()));
        assertTrue(heat.outletC()<45 && heat.outletC()>0); assertTrue(uphill.outletMpa()<plain.outletMpa());
    }
    @Test void equipmentChangesPressureAndPowerAndChecksBothSides() {
        Input i=example(); var compressor=new Equipment("压缩机","compressor",0,0,1.2,.75,5,1);
        Result r=calculator.calculate(modify(i,"outlet",6,4,8,"heat",i.segments(),List.of(compressor),i.constraints()));
        assertTrue(r.totalPowerKw()>1); assertTrue(r.equipment().getFirst().outletMpa()>r.equipment().getFirst().inletMpa());
        assertTrue(r.assessments().stream().anyMatch(a->a.kind().equals("equipment") && a.status().equals("fail")));
    }
    @Test void waterScreeningIsIndependentOfHydraulicKernelAssessment() {
        Input input=example();
        assertNull(input.constraints());
        Result result=calculator.calculate(input);
        assertEquals("series-gas-2.5",result.algorithmVersion());
        assertEquals(1,result.assessments().size());
        assertEquals("equipment",result.assessments().getFirst().kind());
        assertEquals("not_evaluated",result.assessments().getFirst().status());
    }
    @Test void invalidGeometryAndImpossibleBoundariesAreRejected() {
        Input i=example();
        assertThrows(BusinessException.class,()->calculator.calculate(modify(i,"rate",4,6,8,"heat",i.segments(),List.of(),i.constraints())));
        assertThrows(BusinessException.class,()->calculator.calculate(modify(i,"outlet",6,4,8,"heat",List.of(new Segment("坏数据",10,100,.03,20,15,2)),List.of(),i.constraints())));
    }
    @Test void frictionMatchesLaminarAndColebrookResidual() {
        assertEquals(.064,PipelineCalculator.friction(1000,.001,"colebrook"),1e-12);
        double f=PipelineCalculator.friction(1e6,.0003,"colebrook");
        assertEquals(0,1/Math.sqrt(f)+2*Math.log10(.0003/3.7+2.51/(1e6*Math.sqrt(f))),1e-8);
    }
    @Test void waterAvailabilityDoesNotAlterTheSinglePhaseHydraulicSolution() {
        Input input=example();
        Result expected=calculator.calculate(input);
        for(var water:List.of(new Constraints("available"),new Constraints("unknown"))) {
            var current=modify(input,"outlet",6,4,8,"isothermal",input.segments(),input.equipment(),water);
            assertEquals(expected,calculator.calculate(current));
        }
    }
    @Test void fixedPropertiesKeepTheFlowModulesHundredMpaCeilingAtCompressorOutlets() {
        Input input=example();
        var pipe=List.of(new Segment("短管",40,500,.03,0,15,2));
        var allowed=modify(input,"outlet",70,4,8,"isothermal",pipe,List.of(),null);
        assertTrue(calculator.calculate(allowed).outletMpa()>69);
        var compressor=new Equipment("压缩机","compressor",0,0,1.5,.9,120,100000);
        var error=assertThrows(BusinessException.class,()->calculator.calculate(
                modify(input,"outlet",70,4,8,"isothermal",pipe,List.of(compressor),null)));
        assertTrue(error.getMessage().contains("100 MPa"));
        assertTrue(assertThrows(BusinessException.class,()->calculator.calculate(
                modify(input,"outlet",101,4,8,"isothermal",pipe,List.of(),null))).getMessage().contains("100 MPa"));
    }
    @Test void unknownOutletMayBeEmptyButRequiredInletMustBeProvided() {
        Input i=example();
        var unknown=new Input(i.target(),i.thermalMode(),i.frictionMethod(),i.inletMpa(),null,i.rate10k(),i.inletC(),i.gasGravity(),i.z(),i.viscosityMpaS(),i.cpJkgK(),i.jtKmpa(),i.standardPressurePa(),i.standardTemperatureK(),i.standardZ(),i.segments(),i.equipment(),i.constraints());
        assertEquals(calculator.calculate(i).outletMpa(),calculator.calculate(unknown).outletMpa(),1e-10);
        var missing=new Input(i.target(),i.thermalMode(),i.frictionMethod(),null,null,i.rate10k(),i.inletC(),i.gasGravity(),i.z(),i.viscosityMpaS(),i.cpJkgK(),i.jtKmpa(),i.standardPressurePa(),i.standardTemperatureK(),i.standardZ(),i.segments(),i.equipment(),i.constraints());
        assertThrows(BusinessException.class,()->calculator.calculate(missing));
    }
}
