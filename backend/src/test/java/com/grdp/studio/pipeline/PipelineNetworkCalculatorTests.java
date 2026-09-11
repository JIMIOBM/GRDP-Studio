package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineDtos.*;
import static com.grdp.studio.pipeline.PipelineTopology.*;
import static com.grdp.studio.pipeline.PipelineTemperatureCalculator.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineNetworkCalculatorTests {
    private final PipelineGasProperties gas=new PipelineGasProperties();
    // withSnapshot is a pure EOS adapter: a database must never be needed by the network kernel.
    private final PipelineGasModel model=new PipelineGasModel(null,null,null,null,gas);
    private final PipelineNetworkCalculator network=new PipelineNetworkCalculator(new PipelineCalculator(),new PipelineTemperatureCalculator(),gas,model);
    private final PipelineGasModel.Snapshot pvt=new PipelineGasModel.Snapshot(1,10,"test-full-composition","PR",List.of(
            new PipelineGasProperties.Fraction("CH4",.97),new PipelineGasProperties.Fraction("CO2",.02),new PipelineGasProperties.Fraction("N2",.01)));

    @Test void yBranchUsesDownstreamWithdrawalsAndPropagatesActualJunctionState() {
        Graph graph=yGraph(false);
        var result=calculate(graph,false,condition(8.0,6.0,45,0.0,3.0,5.0));
        var trunk=pipe(result,"trunk");var left=pipe(result,"left");var right=pipe(result,"right");
        assertEquals(8,trunk.rate10k());assertEquals(3,left.rate10k());assertEquals(5,right.rate10k());
        assertEquals(trunk.rate10k(),left.rate10k()+right.rate10k(),1e-12);
        assertEquals(trunk.outletMpa(),left.inletMpa(),1e-12);assertEquals(left.inletMpa(),right.inletMpa(),1e-12);
        assertTrue(trunk.outletMpa()<trunk.inletMpa());assertTrue(right.outletMpa()<left.outletMpa());
        assertEquals(45,left.outletC(),1e-10);
    }

    @Test void internalOfftakeAndMeasuredSourceMismatchAreRetainedWithoutBreakingConservation() {
        var result=calculate(yGraph(false),false,condition(8.0,6.0,45,2.0,3.0,5.0));
        assertEquals(10,pipe(result,"trunk").rate10k());
        assertEquals(2,pipe(result,"trunk").rate10k()-pipe(result,"left").rate10k()-pipe(result,"right").rate10k(),1e-12);
        assertTrue(result.notes().stream().anyMatch(n->n.contains("存在偏差")&&n.contains("-2.0")));
    }

    @Test void exactlyOneUnknownTerminalCanBeResolvedBySourceRemainderIncludingInternalOfftake() {
        var result=calculate(yGraph(false),false,condition(10.0,6.0,45,2.0,3.0,null));
        assertEquals(10,pipe(result,"trunk").rate10k());assertEquals(5,pipe(result,"right").rate10k());
        assertTrue(result.notes().stream().anyMatch(n->n.contains("余额")));
    }

    @Test void completeTerminalMeasurementsDoNotRequireOrObeyTheSourceMeasurement() {
        var absent=calculate(yGraph(false),false,condition(null,6.0,45,null,3.0,5.0));
        var zero=calculate(yGraph(false),false,condition(0.0,6.0,45,null,3.0,5.0));
        assertEquals(absent.pipes(),zero.pipes());
        assertTrue(zero.notes().stream().anyMatch(n->n.contains("存在偏差")));
    }

    @Test void missingOrImpossibleSplitsAreNeverAssignedArbitraryEqualFlows() {
        Graph graph=yGraph(false);
        assertError("多个末端",()->calculate(graph,false,condition(8.0,6.0,45,0.0,null,null)));
        assertError("井口供气量",()->calculate(graph,false,condition(null,6.0,45,0.0,3.0,null)));
        assertError("余额为负",()->calculate(graph,false,condition(2.0,6.0,45,0.0,3.0,null)));
        assertError("零流量",()->calculate(graph,false,condition(8.0,6.0,45,0.0,0.0,8.0)));
        assertError("零流量",()->calculate(graph,false,condition(3.0,6.0,45,0.0,3.0,null)));
        assertError("井口入口压力",()->calculate(graph,false,condition(8.0,null,45,0.0,3.0,5.0)));
    }

    @Test void internalMeasuredPressureAndTemperatureAreComparisonDataOnly() {
        var ordinary=condition(8.0,6.0,45,0.0,3.0,5.0);
        var nodes=new ArrayList<>(ordinary.nodes());nodes.set(1,new BoundaryNode("junction",null,0.0,99.0,150.0));
        var measured=new BoundaryCase("test",ordinary.operatingAt(),nodes);
        assertEquals(calculate(yGraph(false),false,ordinary).pipes(),calculate(yGraph(false),false,measured).pipes());
    }

    @Test void compressorStateFeedsAllBranchesButPipeOutletRemainsBeforeCompressor() {
        var result=calculate(yGraph(true),true,condition(8.0,6.0,45,0.0,3.0,5.0));
        var trunk=pipe(result,"trunk");var left=pipe(result,"left");var right=pipe(result,"right");
        assertTrue(trunk.outletMpa()<6);assertEquals(trunk.outletMpa()*1.2,left.inletMpa(),1e-9);
        assertEquals(left.inletMpa(),right.inletMpa(),1e-12);assertTrue(left.inletC()>trunk.outletC());
        assertEquals(left.inletC(),right.inletC(),1e-12);
        var device=result.equipment().getFirst();
        assertEquals("junction:0",device.id());assertEquals("junction",device.nodeId());assertEquals("trunk",device.edgeId());
        assertEquals(trunk.outletMpa(),device.inletMpa(),1e-12);assertEquals(trunk.outletC(),device.inletC(),1e-12);
        assertEquals(left.inletMpa(),device.outletMpa(),1e-12);assertEquals(left.inletC(),device.outletC(),1e-12);
        assertEquals(8,device.rate10k());assertTrue(device.powerKw()>0);assertEquals("pass",device.status());
        assertEquals(10-device.outletMpa(),device.pressureMarginMpa(),1e-12);
        assertEquals(500-device.powerKw(),device.powerMarginKw(),1e-12);
    }

    @Test void orderedDevicesKeepIndividualStatesStableIdentitiesAndLimitsWithoutFailingHydraulics() {
        Graph graph=yGraph(true);var nodes=new ArrayList<>(graph.nodes());var c=nodes.get(1);
        var parameters=new HashMap<>(c.parameters());parameters.put("maxPressureMpa",5.0);parameters.put("maxPowerKw",1.0);
        parameters.put("extraEquipment",List.of(Map.of("name","压缩机","type","valve","lossK",100.0,"maxPressureMpa",10.0)));
        nodes.set(1,new Node(c.id(),c.type(),c.name(),parameters,0,0));
        var updated=new Graph(nodes,graph.edges(),Map.of(),Map.of());
        var result=calculate(updated,true,condition(8.0,6.0,45,0.0,3.0,5.0));
        assertEquals(3,result.pipes().size());assertEquals(2,result.equipment().size());
        var compressor=result.equipment().getFirst();var valve=result.equipment().getLast();
        assertEquals("fail",compressor.status());assertTrue(compressor.pressureMarginMpa()<0);assertTrue(compressor.powerMarginKw()<0);
        assertEquals("junction:1",valve.id());assertEquals(2,valve.sequence());assertEquals("pass",valve.status());
        assertEquals(compressor.outletMpa(),valve.inletMpa(),1e-12);assertEquals(compressor.outletC(),valve.inletC(),1e-12);
        assertTrue(valve.outletMpa()<valve.inletMpa());assertTrue(valve.outletC()<valve.inletC());
        assertNull(valve.maxPowerKw());assertNull(valve.powerMarginKw());assertEquals(0,valve.powerKw());
        assertEquals(valve.outletMpa(),pipe(result,"left").inletMpa(),1e-12);
        assertEquals(valve.outletC(),pipe(result,"right").inletC(),1e-12);
        var shuffled=new Graph(nodes,List.of(graph.edges().get(2),graph.edges().get(1),graph.edges().getFirst()),Map.of(),Map.of());
        assertEquals(result.equipment(),calculate(shuffled,true,condition(8.0,6.0,45,0.0,3.0,5.0)).equipment());
    }

    @Test void inverseSeriesResultsKeepEveryDeviceAttachedToItsRealPipeAndState() {
        Graph graph=lineGraph();var nodes=new ArrayList<>(graph.nodes());
        nodes.set(1,new Node("middle","compressor","同名设备",Map.of("elevationM",0.0,"pressureRatio",1.2,"efficiency",.8,
                "maxPressureMpa",10.0,"maxPowerKw",500.0),0,0));
        nodes.set(2,new Node("end","valve","同名设备",Map.of("elevationM",0.0,"lossK",20.0,"maxPressureMpa",10.0),0,0));
        var withDevices=new Graph(nodes,graph.edges(),Map.of(),Map.of());
        var forward=calculate(withDevices,true,lineCondition(6.0,null,8.0,8.0));
        double downstream=forward.equipment().getLast().outletMpa();
        var inverse=calculate(withDevices,true,lineCondition(null,downstream,8.0,null));
        assertEquals(List.of("middle:0","end:0"),inverse.equipment().stream().map(PipelineNetworkCalculator.DeviceResult::id).toList());
        for(int i=0;i<2;i++) {
            var expected=forward.equipment().get(i);var actual=inverse.equipment().get(i);
            assertEquals(expected.edgeId(),actual.edgeId());assertEquals(expected.inletMpa(),actual.inletMpa(),.00001);
            assertEquals(expected.outletMpa(),actual.outletMpa(),.00001);assertEquals(expected.inletC(),actual.inletC(),.001);
            assertEquals(expected.outletC(),actual.outletC(),.001);assertEquals(expected.powerKw(),actual.powerKw(),.001);
        }
    }

    @Test void physicalPipePointSelectionDoesNotDependOnEquipmentNames() {
        Graph graph=yGraph(true);var nodes=new ArrayList<>(graph.nodes());var c=nodes.get(1);
        nodes.set(1,new Node(c.id(),c.type(),"干线",c.parameters(),0,0));
        var renamed=new Graph(nodes,graph.edges(),Map.of(),Map.of());
        var baseline=calculate(graph,true,condition(8.0,6.0,45,0.0,3.0,5.0));
        var result=calculate(renamed,true,condition(8.0,6.0,45,0.0,3.0,5.0));
        assertEquals(baseline.pipes(),result.pipes());
    }

    @Test void heatUsesSavedGeometryAndLambdaAndCurrentFlowAndPtRatherThanTrialProperties() {
        Graph graph=yGraph(false);var cool=condition(8.0,6.0,35,0.0,3.0,5.0);var warm=condition(12.0,5.0,65,0.0,5.0,7.0);
        var first=calculate(graph,true,cool);var second=calculate(graph,true,warm);
        assertTrue(pipe(first,"trunk").outletC()<35);assertTrue(pipe(second,"trunk").outletC()<65);
        assertNotEquals(pipe(first,"trunk").heatTransferWm2K(),pipe(second,"trunk").heatTransferWm2K(),1e-7);
        assertNotEquals(pipe(second,"left").heatTransferWm2K(),pipe(second,"right").heatTransferWm2K(),1e-7);
        var configs=thermal(graph).settings().segments().stream().map(c->new Config(c.edgeId(),c.externalMethod(),c.ambientC(),c.burialDepthM(),
                c.soilConductivityWmK(),c.surfaceCoefficientWm2K(),-1.0,-1.0,-1.0,-1.0,c.gasConductivityWmK(),c.pvtId(),999.0,-999.0,
                "伪造历史物性",c.layers(),999.0)).toList();
        var forged=new PipelineTemperature.Snapshot(1,1,new PipelineTemperature.FlowSettings(configs,.0001,40));
        assertEquals(first.pipes(),network.calculate(graph,base(true),forged,pvt,cool,1).pipes());
        var lowerLambda=configs.stream().map(c->config(c.edgeId(),.015,10L)).toList();
        var changed=new PipelineTemperature.Snapshot(1,1,new PipelineTemperature.FlowSettings(lowerLambda,.0001,40));
        assertTrue(pipe(network.calculate(graph,base(true),changed,pvt,cool,1),"trunk").heatTransferWm2K()<pipe(first,"trunk").heatTransferWm2K());
    }

    @Test void authoritativeNewGasSnapshotIsUsedWithoutAnyCheckpointOrLegacyScalarFallback() {
        Graph graph=yGraph(false);var condition=condition(8.0,6.0,45,0.0,3.0,5.0);
        var other=new PipelineGasModel.Snapshot(2,10,"different-composition","PR",List.of(new PipelineGasProperties.Fraction("CH4",.90),
                new PipelineGasProperties.Fraction("CO2",.08),new PipelineGasProperties.Fraction("N2",.02)));
        var original=calculate(graph,true,condition);
        var changed=network.calculate(graph,base(true),thermal(graph),other,condition,1);
        assertNotEquals(pipe(original,"trunk").outletMpa(),pipe(changed,"trunk").outletMpa(),1e-6);
        assertNotEquals(pipe(original,"trunk").heatTransferWm2K(),pipe(changed,"trunk").heatTransferWm2K(),1e-7);
        assertError("PVT模型",()->network.calculate(graph,base(false),null,null,condition,1));
    }

    @Test void convectionReceivesMeanPhysicalPipeStateAndSameEosMassFlowAsHydraulics() {
        var evaluated=new LinkedHashMap<String,Config>();
        var recordingHeat=new PipelineTemperatureCalculator() {
            @Override public Result calculate(double diameter,Config config) {
                evaluated.put(config.edgeId(),config);return super.calculate(diameter,config);
            }
        };
        var calculator=new PipelineNetworkCalculator(new PipelineCalculator(),recordingHeat,gas,model);
        Graph graph=yGraph(true);
        var result=calculator.calculate(graph,base(true),thermal(graph),pvt,condition(10.0,6.0,60,2.0,3.0,5.0),1);
        var eos=gas.prepare(pvt.method(),pvt.composition());double standardDensity=eos.at(.101325,20).densityKgM3();
        for(var pipe:result.pipes()) {
            Config used=evaluated.get(pipe.edgeId());
            assertTrue(used.propertyPressureMpa()<pipe.inletMpa()&&used.propertyPressureMpa()>pipe.outletMpa());
            assertTrue(used.propertyTemperatureC()<pipe.inletC()&&used.propertyTemperatureC()>pipe.outletC());
            var state=eos.at(used.propertyPressureMpa(),used.propertyTemperatureC());
            assertEquals(state.densityKgM3(),used.densityKgM3(),1e-12);
            assertEquals(state.cpJkgK(),used.cpJkgK(),1e-12);
            assertEquals(eos.standingViscosity(used.propertyPressureMpa(),used.propertyTemperatureC()).viscosityMpaS(),used.viscosityMpaS(),1e-12);
            assertEquals(pipe.rate10k()/8.64*standardDensity,used.actualFlowM3s()*used.densityKgM3(),1e-12);
            assertEquals(.035,used.gasConductivityWmK());
        }
    }

    @Test void thermalSourcesMustCoverCurrentGraphAndPvtWithoutDefaultingMissingData() {
        Graph graph=yGraph(false);var boundary=condition(8.0,6.0,45,0.0,3.0,5.0);var base=base(true);
        assertError("保存",()->network.calculate(graph,base,null,pvt,boundary,1));
        assertError("气体导热系数",()->network.calculate(graph,base,withConfig(graph,config("left",null,10L)),pvt,boundary,1));
        assertError("PVT 已变化",()->network.calculate(graph,base,withConfig(graph,config("left",.035,11L)),pvt,boundary,1));
        var missing=new PipelineTemperature.Snapshot(1,1,new PipelineTemperature.FlowSettings(List.of(config("trunk",.035,10L)),.0001,40));
        assertError("全部管道",()->network.calculate(graph,base,missing,pvt,boundary,1));
        var stale=new PipelineTemperature.Snapshot(1,2,thermal(graph).settings());
        assertError("拓扑版本",()->network.calculate(graph,base,stale,pvt,boundary,1));
    }

    @Test void completeSeriesKeepsTwoPressureAndReverseInletCapabilities() {
        Graph graph=lineGraph();
        for(boolean heat:List.of(false,true)) {
            var forward=calculate(graph,heat,lineCondition(6.0,null,8.0,8.0));
            double outlet=pipe(forward,"second").outletMpa();
            var rate=calculate(graph,heat,lineCondition(6.0,outlet,null,null));
            // The reused inverse kernel terminates on 1e-6 MPa, rather than a fixed flow tolerance.
            assertEquals(outlet,pipe(rate,"second").outletMpa(),.000001);
            assertEquals(8,pipe(rate,"first").rate10k(),.001);
            var inlet=calculate(graph,heat,lineCondition(null,outlet,8.0,null));
            assertEquals(6,pipe(inlet,"first").inletMpa(),.00001);
            assertEquals(pipe(forward,"second").outletC(),pipe(inlet,"second").outletC(),.0002);
        }
    }

    @Test void seriesLocalOfftakeUsesDifferentUpstreamAndDownstreamFlows() {
        Graph graph=lineGraph();var nodes=new ArrayList<>(lineCondition(6.0,null,10.0,8.0).nodes());
        nodes.set(1,new BoundaryNode("middle",null,2.0,null,null));
        var result=calculate(graph,true,new BoundaryCase("line",null,nodes));
        assertEquals(10,pipe(result,"first").rate10k());assertEquals(8,pipe(result,"second").rate10k());
        assertEquals(pipe(result,"first").outletMpa(),pipe(result,"second").inletMpa(),1e-12);
    }

    @Test void mergesCyclesDisconnectedNodesAndAdditionalSupplyAreExplicitlyRejected() {
        Graph graph=yGraph(false);var boundary=condition(8.0,6.0,45,0.0,3.0,5.0);
        var edges=new ArrayList<>(graph.edges());edges.add(edge("merge","leftEnd","rightEnd","汇合",100));
        Graph merged=new Graph(graph.nodes(),edges,Map.of(),Map.of());
        assertError("汇合",()->calculate(merged,false,boundary));
        var cycle=new ArrayList<>(graph.edges());cycle.add(edge("cycle","rightEnd","well","环",100));
        assertError("环路",()->calculate(new Graph(graph.nodes(),cycle,Map.of(),Map.of()),false,boundary));
        var nodes=new ArrayList<>(graph.nodes());nodes.add(node("unconnected","outlet","孤立"));
        assertError("未连接",()->calculate(new Graph(nodes,graph.edges(),Map.of(),Map.of()),false,boundary));
        var supplied=new ArrayList<>(boundary.nodes());supplied.set(1,new BoundaryNode("junction",1.0,null,null,null));
        assertError("其他节点供气",()->calculate(graph,false,new BoundaryCase("test",null,supplied)));
        var noTemperature=new ArrayList<>(boundary.nodes());noTemperature.set(0,new BoundaryNode("well",8.0,null,6.0,null));
        assertError("井口供气温度",()->calculate(graph,false,new BoundaryCase("test",null,noTemperature)));
    }

    @Test void graphStorageOrderDoesNotAffectPropagationAndIsPreservedInOutput() {
        Graph graph=yGraph(false);var shuffled=new Graph(graph.nodes(),List.of(graph.edges().get(2),graph.edges().get(0),graph.edges().get(1)),Map.of(),Map.of());
        var boundary=condition(8.0,6.0,45,0.0,3.0,5.0);
        var expected=calculate(graph,true,boundary);var actual=calculate(shuffled,true,boundary);
        assertEquals(List.of("right","trunk","left"),actual.pipes().stream().map(PipelineNetworkCalculator.PipeResult::edgeId).toList());
        for(var p:expected.pipes())assertEquals(p,pipe(actual,p.edgeId()));
    }

    private PipelineNetworkCalculator.NetworkResult calculate(Graph graph,boolean heat,BoundaryCase condition) {
        return network.calculate(graph,base(heat),heat?thermal(graph):null,pvt,condition,1);
    }
    private static Input base(boolean heat) {
        return new Input("outlet",heat?"heat":"isothermal","colebrook",null,null,null,null,
                null,null,null,null,heat?3.0:null,101325.0,293.15,null,List.of(),List.of(),null);
    }
    private static PipelineNetworkCalculator.PipeResult pipe(PipelineNetworkCalculator.NetworkResult result,String id) {
        return result.pipes().stream().filter(p->p.edgeId().equals(id)).findFirst().orElseThrow();
    }
    private static BoundaryCase condition(Double supply,Double pressure,double temperature,Double middle,Double left,Double right) {
        return new BoundaryCase("test","2026-09-11T01:00",List.of(new BoundaryNode("well",supply,null,pressure,temperature),
                new BoundaryNode("junction",null,middle,null,null),new BoundaryNode("leftEnd",null,left,null,null),new BoundaryNode("rightEnd",null,right,null,null)));
    }
    private static BoundaryCase lineCondition(Double pin,Double pout,Double supply,Double withdrawal) {
        return new BoundaryCase("line",null,List.of(new BoundaryNode("well",supply,null,pin,45.0),
                new BoundaryNode("middle",null,null,null,null),new BoundaryNode("end",null,withdrawal,pout,null)));
    }
    private static Graph yGraph(boolean compressor) {
        Node junction=compressor?new Node("junction","compressor","压缩机",Map.of("elevationM",0.0,"pressureRatio",1.2,"efficiency",.8,
                "maxPressureMpa",10.0,"maxPowerKw",500.0),0,0):node("junction","junction","分输点");
        return new Graph(List.of(node("well","well","井口"),junction,node("leftEnd","outlet","左末端"),node("rightEnd","outlet","右末端")),
                List.of(edge("trunk","well","junction","干线",300),edge("left","junction","leftEnd","左支线",500),edge("right","junction","rightEnd","右支线",500)),Map.of(),Map.of());
    }
    private static Graph lineGraph() {
        return new Graph(List.of(node("well","well","井口"),node("middle","junction","中间点"),node("end","outlet","末端")),
                List.of(edge("first","well","middle","一段",300),edge("second","middle","end","二段",500)),Map.of(),Map.of());
    }
    private static Node node(String id,String type,String name){return new Node(id,type,name,Map.of("elevationM",0.0),0,0);}
    private static Edge edge(String id,String source,String target,String name,double length){return new Edge(id,source,target,name,
            Map.of("lengthM",length,"diameterMm",100.0,"roughnessMm",.03));}
    private static Config config(String edge,Double lambda,Long pvtId) {
        return new Config(edge,"surface-fixed",15.0,1.2,1.5,null,null,null,null,null,lambda,pvtId,null,null,null,
                List.of(new Layer("钢管",6.0,45.0),new Layer("保温层",40.0,.035)));
    }
    private static PipelineTemperature.Snapshot thermal(Graph graph) {
        return new PipelineTemperature.Snapshot(1,1,new PipelineTemperature.FlowSettings(graph.edges().stream().map(e->config(e.id(),.035,10L)).toList(),.0001,40));
    }
    private static PipelineTemperature.Snapshot withConfig(Graph graph,Config replacement) {
        return new PipelineTemperature.Snapshot(1,1,new PipelineTemperature.FlowSettings(graph.edges().stream()
                .map(e->e.id().equals(replacement.edgeId())?replacement:config(e.id(),.035,10L)).toList(),.0001,40));
    }
    private static void assertError(String text,org.junit.jupiter.api.function.Executable calculation) {
        assertTrue(assertThrows(BusinessException.class,calculation).getMessage().contains(text));
    }
}
