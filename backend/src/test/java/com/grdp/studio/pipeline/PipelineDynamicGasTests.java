package com.grdp.studio.pipeline;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.grdp.studio.pipeline.PipelineDtos.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineDynamicGasTests {
    private final PipelineCalculator calculator=new PipelineCalculator();
    private final PipelineGasProperties gas=new PipelineGasProperties();
    private Input input(String method,String target,double pOut,double q,int segments) {
        var composition=List.of(new PipelineGasProperties.Fraction("CH4",.92),new PipelineGasProperties.Fraction("C2H6",.05),new PipelineGasProperties.Fraction("N2",.03));
        var model=new PipelineGasModel.Snapshot(1,10,"scientific-test-composition-v1",method,composition);
        var pipes=new java.util.ArrayList<Segment>();
        for(int i=0;i<segments;i++)pipes.add(new Segment("段"+i,4000.0/segments,100,.03,20.0/segments,15,4));
        return new Input(target,"heat","colebrook",6.0,pOut,q,45.0,.65,.9,.012,2300.0,3.0,101325.0,293.15,1.0,pipes,List.of(),null,model);
    }
    @Test void allThreeModelsUpdateEveryPointAndConserveMass() {
        for(String method:List.of("PR","SRK","BWRS")) {
            var input=input(method,"outlet",4,8,1);var result=calculator.calculate(input);
            var eos=gas.prepare(method,input.gasModel().composition());
            double mass=8.0/8.64*eos.at(.101325,20).densityKgM3(),area=Math.PI*.1*.1/4;
            assertTrue(result.outletMpa()<6);assertTrue(result.outletC()<45);
            for(var point:result.points()) {
                var expected=eos.at(point.pressureMpa(),point.temperatureC());
                assertEquals(expected.z(),point.z(),1e-10);
                assertEquals(expected.cpJkgK(),point.cpJkgK(),1e-7);
                assertEquals(mass,point.densityKgM3()*point.velocityMs()*area,1e-9);
                double viscosity=eos.standingViscosity(point.pressureMpa(),point.temperatureC()).viscosityMpaS();
                assertEquals(viscosity,point.viscosityMpaS(),1e-14);
                double reynolds=mass*.1/(area*viscosity*.001);
                assertEquals(reynolds,point.reynolds(),reynolds*1e-12);
                assertEquals(PipelineCalculator.friction(reynolds,.03/100,"colebrook"),point.frictionFactor(),1e-12);
            }
            assertTrue(Math.abs(result.points().getFirst().z()-result.points().getLast().z())>1e-5);
            assertTrue(Math.abs(result.points().getFirst().cpJkgK()-result.points().getLast().cpJkgK())>1);
            assertNotEquals(result.points().getFirst().reynolds(),result.points().getLast().reynolds());
        }
    }
    @Test void inverseTargetsRecoverKnownBoundaryAndFlow() {
        for(String method:List.of("PR","SRK","BWRS")) {
            var forward=calculator.calculate(input(method,"outlet",4,8,1));
            var inverse=calculator.calculate(input(method,"inlet",forward.outletMpa(),8,1));
            assertEquals(6,inverse.inletMpa(),2e-6);
            var rate=calculator.calculate(input(method,"rate",forward.outletMpa(),8,1));
            assertEquals(8,rate.rate10k(),2e-4);
        }
    }
    @Test void numericalRefinementPreservesPressureAndTemperature() {
        var whole=calculator.calculate(input("PR","outlet",4,8,1));
        var refined=calculator.calculate(input("PR","outlet",4,8,4));
        assertEquals(whole.outletMpa(),refined.outletMpa(),5e-5);
        assertEquals(whole.outletC(),refined.outletC(),.003);
    }
    @Test void inversePressureCanBracketACompressorWithinThePipelinePressureLimit() {
        var i=input("PR","outlet",4,1,1);
        var pipes=List.of(new Segment("短管",100,100,.03,0,15,0));
        var equipment=List.of(new Equipment("压缩机","compressor",0,0,1.4,1,100,10000));
        var known=new Input("outlet","heat","colebrook",60.0,null,1.0,45.0,i.gasGravity(),i.z(),i.viscosityMpaS(),i.cpJkgK(),0.0,i.standardPressurePa(),i.standardTemperatureK(),i.standardZ(),pipes,equipment,null,i.gasModel());
        var result=calculator.calculate(known);
        var inverse=new Input("inlet","heat","colebrook",null,result.outletMpa(),1.0,45.0,i.gasGravity(),i.z(),i.viscosityMpaS(),i.cpJkgK(),0.0,i.standardPressurePa(),i.standardTemperatureK(),i.standardZ(),pipes,equipment,i.constraints(),i.gasModel());
        assertTrue(result.outletMpa()>50&&result.outletMpa()<100);
        assertEquals(60,calculator.calculate(inverse).inletMpa(),2e-6);
    }
    @Test void allThreeEquationsRemainAvailableAtSeventyMpaDuringPipeIntegration() {
        for(String method:List.of("PR","SRK","BWRS")) {
            var i=input(method,"outlet",4,1,1);
            var pipes=List.of(new Segment("高压短管",100,100,.03,0,15,0));
            var highPressure=new Input("outlet","isothermal","colebrook",70.0,null,1.0,45.0,
                    i.gasGravity(),i.z(),i.viscosityMpaS(),i.cpJkgK(),0.0,
                    i.standardPressurePa(),i.standardTemperatureK(),i.standardZ(),pipes,List.of(),null,i.gasModel());
            var result=calculator.calculate(highPressure);
            assertTrue(result.outletMpa()>50&&result.outletMpa()<70,method);
            for(var point:result.points()) {
                var expected=gas.calculate(method,i.gasModel().composition(),point.pressureMpa(),point.temperatureC());
                assertEquals(expected.z(),point.z(),1e-10);
                assertEquals(expected.cpJkgK(),point.cpJkgK(),1e-7);
            }
        }
    }
}
