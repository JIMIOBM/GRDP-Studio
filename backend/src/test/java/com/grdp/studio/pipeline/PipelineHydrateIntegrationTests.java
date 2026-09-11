package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineDtos.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineHydrateIntegrationTests {
    private final PipelineGasModel.Snapshot pvt=new PipelineGasModel.Snapshot(1,10,"hydrate-mixture","PR",List.of(
            new PipelineGasProperties.Fraction("CH4",.94),new PipelineGasProperties.Fraction("C2H6",.04),
            new PipelineGasProperties.Fraction("C3H8",.01),new PipelineGasProperties.Fraction("N2",.005),
            new PipelineGasProperties.Fraction("CO2",.005)));
    private final PipelineHydrateModel.Prepared model=PipelineHydrateModel.prepare(pvt);
    private final PipelineTopology.Edge pipe=new PipelineTopology.Edge("p","a","b","测试管道",Map.of());

    @Test void minimumUsesSamePointPressureAndTemperatureAndCanBeInsideThePipe() {
        var points=List.of(point(900,6,10,"管道入口"),point(1050,5,-2,"管内点"),point(1200,4,5,"管道出口"));
        var result=PipelineNetworkCalculator.screenHydrate(pipe,points,model,"available");
        assertEquals("risk",result.status());assertEquals(150,result.distanceM());assertEquals("管内点",result.pointLabel());
        assertEquals(5,result.pressureMpa());assertEquals(model.at(5).temperatureC(),result.equilibriumC());
        assertEquals(-2,result.marginC(),1e-12);assertEquals(3,result.sampledPoints());assertEquals(3,result.evaluatedPoints());
        assertEquals("conditional",PipelineNetworkCalculator.screenHydrate(pipe,points,model,"unknown").status());
    }

    @Test void deviceInletAndOutletAtTheSameDistanceRemainSeparatePressureTemperatureSamples() {
        var points=List.of(point(0,6,10,"管道入口"),point(300,5,2,"阀门入口"),point(300,4,-5,"阀门出口"));
        var result=PipelineNetworkCalculator.screenHydrate(pipe,points,model,"available");
        assertEquals("阀门出口",result.pointLabel());assertEquals(300,result.distanceM());assertEquals(4,result.pressureMpa());
        assertEquals(-5,result.marginC(),1e-12);assertEquals(3,result.evaluatedPoints());
    }

    @Test void incompleteDomainCoverageNeverClaimsAPipeMinimumOrSafeStatus() {
        var valid=point(0,6,5,"入口");var outOfRange=new Point(0,"超域点",100,.5,20,1,1,100000,.02,1.0,2000.0,.01);
        var points=List.of(valid,outOfRange,point(200,4,-2,"已评价风险点"));
        var result=PipelineNetworkCalculator.screenHydrate(pipe,points,model,"available");
        assertEquals("not_evaluated",result.status());assertEquals(100,result.distanceM());assertEquals(.5,result.pressureMpa());
        assertNull(result.equilibriumC());assertNull(result.marginC());assertEquals(3,result.sampledPoints());assertEquals(2,result.evaluatedPoints());
        assertTrue(result.reason().contains("不能给出全管最小"));
        var unsupported=PipelineHydrateModel.prepare(new PipelineGasModel.Snapshot(1,1,"light-gas","PR",List.of(new PipelineGasProperties.Fraction("CH4",1))));
        var entire=PipelineNetworkCalculator.screenHydrate(pipe,points,unsupported,"available");
        assertEquals("not_evaluated",entire.status());assertEquals(0,entire.evaluatedPoints());assertNull(entire.marginC());
    }

    @Test void equilibriumBoundaryIsDistinctAndMissingSamplesCannotBeFabricatedFromEndpoints() {
        assertEquals("equilibrium",PipelineNetworkCalculator.screenHydrate(pipe,List.of(point(0,6,0,"平衡点")),model,"available").status());
        assertEquals("equilibrium",PipelineNetworkCalculator.screenHydrate(pipe,List.of(point(0,6,0,"平衡点")),model,"unknown").status());
        assertEquals("pass",PipelineNetworkCalculator.screenHydrate(pipe,List.of(point(0,6,.001,"入口")),model,"unknown").status());
        assertThrows(BusinessException.class,()->PipelineNetworkCalculator.screenHydrate(pipe,List.of(),model,"available"));
    }

    @Test void actualNetworkScreensTheFinalSolvedSamplesIncludingEveryDeviceOutlet() {
        var captured=new ArrayList<PipelineDtos.Result>();
        var calculator=new PipelineCalculator(){
            @Override public PipelineDtos.Result calculate(Input input){var result=super.calculate(input);captured.add(result);return result;}
        };
        var gas=new PipelineGasProperties();var gasModel=new PipelineGasModel(null,null,null,null,gas);
        var network=new PipelineNetworkCalculator(calculator,new PipelineTemperatureCalculator(),gas,gasModel);
        var graph=new PipelineTopology.Graph(List.of(new PipelineTopology.Node("a","well","井口",Map.of("elevationM",0),0,0),
                new PipelineTopology.Node("b","valve","阀门",Map.of("elevationM",0,"lossK",20,"maxPressureMpa",10),0,0)),
                List.of(new PipelineTopology.Edge("p","a","b","测试管道",Map.of("lengthM",300,"diameterMm",100,"roughnessMm",.03))),Map.of(),Map.of());
        var input=new Input("outlet","isothermal","colebrook",null,null,null,null,null,null,null,null,null,101325.0,293.15,null,List.of(),List.of(),new Constraints("available"));
        var condition=new BoundaryCase("one","2026-09-01T08:17",List.of(new BoundaryNode("a",8.0,null,6.0,10.0),new BoundaryNode("b",null,8.0,null,null)));
        var result=network.calculate(graph,input,null,pvt,condition,1);
        assertEquals(1,captured.size());var actualPoints=captured.getFirst().points();
        assertTrue(actualPoints.size()>3);assertEquals("阀门 出口",actualPoints.getLast().location());
        var expected=actualPoints.stream().min(Comparator.comparingDouble(p->p.temperatureC()-model.at(p.pressureMpa()).temperatureC())).orElseThrow();
        var row=result.hydrate().getFirst();assertEquals(actualPoints.size(),row.sampledPoints());assertEquals(actualPoints.size(),row.evaluatedPoints());
        assertEquals(expected.pressureMpa(),row.pressureMpa());assertEquals(expected.temperatureC(),row.temperatureC());
        assertEquals(expected.distanceM(),row.distanceM());assertEquals("risk",row.status());
    }

    private Point point(double distance,double pressure,double margin,String label) {
        return new Point(0,label,distance,pressure,model.at(pressure).temperatureC()+margin,1,1,100000,.02,1.0,2000.0,.01);
    }
}
