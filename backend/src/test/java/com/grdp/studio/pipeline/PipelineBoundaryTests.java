package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.grdp.studio.pipeline.PipelineDtos.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineBoundaryTests {
    private static final int REVISION=3;

    private static PipelineTopology.Node node(String id,String type,String name) {
        return new PipelineTopology.Node(id,type,name,Map.of("elevationM",0.0),0,0);
    }
    private static PipelineTopology.Edge edge(String id,String source,String target,String name) {
        return new PipelineTopology.Edge(id,source,target,name,Map.of("lengthM",1000.0,"diameterMm",100.0,"roughnessMm",.03));
    }
    private static PipelineTopology.Graph graph() {
        return new PipelineTopology.Graph(List.of(node("source","well","井口"),node("middle","junction","中间节点"),node("end","station","终点")),
                List.of(edge("a","source","middle","A"),edge("b","middle","end","B")),Map.of(),Map.of());
    }
    private static BoundaryNode boundaryNode(String id,Double supply,Double withdrawal,Double pressure,Double temperature) {
        return new BoundaryNode(id,supply,withdrawal,pressure,temperature);
    }
    private static Boundary single(int revision,String operatingAt,List<BoundaryNode> nodes) {
        return new Boundary(revision,"case-1",List.of(new BoundaryCase("case-1",operatingAt,nodes)));
    }
    private static Boundary boundary(BoundaryNode... nodes) {return single(REVISION,"2026-09-08T12:00",List.of(nodes));}
    private static Input input(Boundary boundary) {
        return new Input("outlet","isothermal","colebrook",9.0,2.0,19.0,47.0,.65,.9,.012,2300.0,3.0,
                101325.0,293.15,1.0,List.of(new Segment("A",1000,100,.03,0,15,2),new Segment("B",1000,100,.03,0,15,2)),
                List.of(),null,null,boundary);
    }
    private static Input resolve(BoundaryNode... nodes) {return PipelineBoundary.resolve(input(boundary(nodes)),graph(),REVISION);}
    private static void rejects(BoundaryNode... nodes) {
        assertThrows(BusinessException.class,()->resolve(nodes));
    }

    @Test void standaloneThermalInputWithoutNodeBoundariesKeepsItsScalarOperatingPoint() {
        Input original=input(null);
        assertEquals(original,PipelineBoundary.resolve(original,graph(),REVISION));
        assertThrows(BusinessException.class,()->PipelineBoundary.validate(null,graph(),REVISION));
        Result result=result();
        assertEquals(result,PipelineBoundary.assess(original,graph(),result));
    }
    @Test void sourcePressureAndSupplySelectOutletAndUseSourceTemperature() {
        Boundary b=boundary(boundaryNode("source",7.0,null,8.0,38.0),
                boundaryNode("end",null,null,3.0,-20.0));
        Input actual=PipelineBoundary.resolve(input(b),graph(),REVISION);
        assertEquals("outlet",actual.target());
        assertEquals(8.0,actual.inletMpa());assertEquals(7.0,actual.rate10k());assertEquals(38.0,actual.inletC());
        assertNull(actual.outletMpa(),"The observed terminal pressure stays in the node data, not the solved outlet scalar");
        assertEquals(b,actual.boundary());
        assertEquals(input(b).segments(),actual.segments());assertEquals(input(b).gasGravity(),actual.gasGravity());
        assertEquals(input(b).standardPressurePa(),actual.standardPressurePa());
    }
    @Test void missingSourcePressureAndSupplyUseTerminalPressureAndWithdrawalToSolveInlet() {
        Input actual=resolve(boundaryNode("source",null,null,null,47.0),
                boundaryNode("end",null,7.0,5.0,-20.0));
        assertEquals("inlet",actual.target());assertEquals(5.0,actual.outletMpa());assertEquals(7.0,actual.rate10k());
        assertNull(actual.inletMpa());
        assertEquals(47.0,actual.inletC(),"Non-source temperature must not replace the inlet temperature");
        Input withSupply=resolve(boundaryNode("source",6.0,null,null,47.0),boundaryNode("end",null,7.0,5.0,null));
        assertEquals("inlet",withSupply.target());assertEquals(6.0,withSupply.rate10k());
        assertTrue(PipelineBoundary.assess(withSupply,graph(),result()).notes().stream().anyMatch(n->n.contains("末端压力和井口供气量")));
    }
    @Test void missingBothEndpointFlowsUsesBothPressuresToSolveRate() {
        Input actual=resolve(boundaryNode("source",null,null,8.0,47.0),
                boundaryNode("end",null,null,5.0,null));
        assertEquals("rate",actual.target());assertEquals(8.0,actual.inletMpa());assertEquals(5.0,actual.outletMpa());
        assertEquals(47.0,actual.inletC());assertNull(actual.rate10k());
        assertTrue(PipelineBoundary.assess(actual,graph(),result()).notes().stream().anyMatch(n->n.contains("采用两端压力计算输量")));
    }
    @Test void equalEndpointMeasurementsProduceOneConservedRateWithoutAMismatchNote() {
        Input actual=resolve(boundaryNode("source",7.0,null,8.0,47.0),
                boundaryNode("end",null,7.0,null,null));
        assertEquals("outlet",actual.target());assertEquals(7.0,actual.rate10k());assertEquals(47.0,actual.inletC());
        assertTrue(PipelineBoundary.assess(actual,graph(),result()).notes().stream().noneMatch(n->n.contains("偏差")));
    }
    @Test void allMeasurementsCanBeFilledAndSourceSupplyHasPriorityOverDifferentTerminalWithdrawal() {
        var observed=boundary(boundaryNode("source",7.0,0.0,8.0,47.0),boundaryNode("middle",0.0,0.0,500.0,12.0),
                boundaryNode("end",0.0,8.0,9.0,35.0));
        Input actual=PipelineBoundary.resolve(input(observed),graph(),REVISION);
        assertEquals("outlet",actual.target());assertEquals(8.0,actual.inletMpa());assertEquals(7.0,actual.rate10k());
        assertNull(actual.outletMpa());assertEquals(observed,actual.boundary());
        var assessed=PipelineBoundary.assess(actual,graph(),result());
        assertTrue(assessed.notes().stream().anyMatch(n->n.contains("偏差")&&n.contains("-1.0")));
        assertEquals(result().assessments(),assessed.assessments());
    }
    @Test void missingSupplyFallsBackToTerminalWithdrawalEvenWhenBothPressuresAreMeasured() {
        Input actual=resolve(boundaryNode("source",null,null,8.0,47.0),
                boundaryNode("end",null,7.0,5.5,null));
        assertEquals("outlet",actual.target());assertEquals(8.0,actual.inletMpa());assertEquals(7.0,actual.rate10k());assertEquals(47.0,actual.inletC());
        assertNull(actual.outletMpa());
        assertTrue(PipelineBoundary.assess(actual,graph(),result()).notes().stream().anyMatch(n->n.contains("入口压力和末端取气量")));
    }
    @Test void internalSupplyAndOfftakeCannotBeSilentlyIgnoredButInternalMeasuredPressureIsAllowed() {
        BoundaryNode source=boundaryNode("source",7.0,null,8.0,47.0);
        rejects(source,boundaryNode("middle",null,1.0,null,null));
        rejects(source,boundaryNode("middle",1.0,null,null,null));
        Input actual=resolve(source,boundaryNode("middle",null,0.0,6.0,-20.0));
        assertEquals("outlet",actual.target());assertEquals(7.0,actual.rate10k());assertEquals(47.0,actual.inletC());
    }
    @Test void reverseEndpointInjectionAndExplicitZeroFlowAreRejected() {
        rejects(boundaryNode("source",7.0,1.0,8.0,47.0));
        rejects(boundaryNode("source",7.0,null,8.0,47.0),
                boundaryNode("end",1.0,null,null,null));
        rejects(boundaryNode("source",0.0,null,8.0,47.0));
        rejects(boundaryNode("source",null,null,null,47.0),
                boundaryNode("end",null,0.0,5.0,null));
        rejects(boundaryNode("source",0.0,null,8.0,47.0),boundaryNode("end",null,7.0,5.0,null));
        Input positiveSupply=resolve(boundaryNode("source",7.0,null,8.0,47.0),boundaryNode("end",null,0.0,5.0,null));
        assertEquals(7.0,positiveSupply.rate10k(),"An observed zero terminal withdrawal does not replace positive measured supply");
        assertEquals(0.0,positiveSupply.boundary().nodes().getLast().withdrawalRate10k());
        assertTrue(PipelineBoundary.assess(positiveSupply,graph(),result()).notes().stream().anyMatch(n->n.contains("偏差")));
    }
    @Test void missingSourceTemperatureCannotFallBackToLegacyScalarOrTerminalTemperature() {
        Input incomplete=input(boundary(boundaryNode("source",7.0,null,8.0,null),
                boundaryNode("end",null,null,null,38.0)));
        assertEquals(47.0,incomplete.inletC(),"The legacy scalar is present to detect an unintended fallback");
        var error=assertThrows(BusinessException.class,()->PipelineBoundary.resolve(incomplete,graph(),REVISION));
        assertTrue(error.getMessage().contains("温度"));
    }
    @Test void validationAcceptsIncompleteDraftsAndBranchedTopologies() {
        Boundary incomplete=single(REVISION,null,List.of(boundaryNode("source",null,null,null,null)));
        assertDoesNotThrow(()->PipelineBoundary.validate(incomplete,graph(),REVISION));
        assertDoesNotThrow(()->PipelineBoundary.validate(single(REVISION,null,List.of()),graph(),REVISION));
        var branched=new PipelineTopology.Graph(List.of(node("source","well","井口"),node("left","station","左终点"),node("right","station","右终点")),
                List.of(edge("a","source","left","A"),edge("b","source","right","B")),Map.of(),Map.of());
        assertDoesNotThrow(()->PipelineBoundary.validate(incomplete,branched,REVISION));
        assertThrows(BusinessException.class,()->PipelineBoundary.resolve(input(incomplete),graph(),REVISION));
    }
    @Test void validationRejectsStaleTopologyForeignNodesAndDuplicateRows() {
        BoundaryNode source=boundaryNode("source",null,null,null,null);
        assertThrows(BusinessException.class,()->PipelineBoundary.validate(single(REVISION-1,null,List.of(source)),graph(),REVISION));
        assertThrows(BusinessException.class,()->PipelineBoundary.validate(boundary(boundaryNode("other-well",null,null,null,null)),graph(),REVISION));
        assertThrows(BusinessException.class,()->PipelineBoundary.validate(boundary(source,source),graph(),REVISION));
    }
    @Test void operatingTimeAcceptsAnyMinuteAndCompatibleFormatsButOnlyRealLocalDates() {
        for(String time:List.of("2026-09-08T00:00","2024-02-29T23:59","2026-09-08T12:30",
                "2026-09-08T12:37:00","2026-09-08 12:37:00","2026/09/08 12:37","2024/02/29 23:59"))
            assertDoesNotThrow(()->PipelineBoundary.validate(single(REVISION,time,List.of()),graph(),REVISION));
        for(String time:List.of("2026-02-29T12:00","2026-09-08T24:00","2026/09/08 12:60","2026/02/29 12:37",
                "2026-09-08T12:37:01","2026-09-08T12:37:00.000","2026-09-08T12:37Z","2026/9/08 12:37",
                "2026/09/08 2:37","2026/09/08 12:37 ","0000/09/08 12:37","2026-09-08","wrong",""))
            assertThrows(BusinessException.class,()->PipelineBoundary.validate(single(REVISION,time,List.of()),graph(),REVISION),time);
    }
    @Test void differentlyFormattedDatesCollideByTheirActualMinuteAndSavesHaveCanonicalTimestamps() {
        var first=new BoundaryCase("first","2026/09/08 12:37",List.of());
        for(String duplicate:List.of("2026-09-08T12:37","2026-09-08 12:37:00","2026/09/08 12:37")) {
            var boundary=new Boundary(REVISION,"first",List.of(first,new BoundaryCase("second",duplicate,List.of())));
            assertTrue(assertThrows(BusinessException.class,()->PipelineBoundary.validate(boundary,graph(),REVISION)).getMessage().contains("时间重复"));
        }
        var valid=new Boundary(REVISION,"first",List.of(first,new BoundaryCase("second","2026-09-08T12:38:00",List.of())));
        assertDoesNotThrow(()->PipelineBoundary.validateForSave(valid,graph(),REVISION));
        var saved=PipelineBoundary.forSave(valid);
        assertEquals(List.of("2026-09-08T12:37","2026-09-08T12:38"),saved.cases().stream().map(BoundaryCase::operatingAt).toList());
        assertEquals("first",saved.activeCaseId());assertEquals("2026/09/08 12:37",valid.cases().getFirst().operatingAt());
    }
    @Test void everyRecordedNumericFieldIsFiniteAndPhysical() {
        for(Double value:List.of(-1.0,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
            assertInvalid(boundaryNode("source",value,null,null,null));
            assertInvalid(boundaryNode("source",null,value,null,null));
        }
        for(Double value:List.of(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY))
            assertInvalid(boundaryNode("source",null,null,value,null));
        for(Double value:List.of(-273.15,-300.0,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY))
            assertInvalid(boundaryNode("source",null,null,null,value));
        assertDoesNotThrow(()->PipelineBoundary.validate(boundary(boundaryNode("source",0.0,0.0,.000001,-273.14)),graph(),REVISION));
    }
    @Test void oldRootNodeJsonIsNotMigratedAndOnlyCanonicalCasesCanBeValidated() {
        // Even a permissive JSON reader cannot convert a removed single-case API shape into valid boundaries.
        var json=JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        Boundary oldShape=json.readValue("""
                {"topologyRevision":3,"operatingAt":"2026-09-08T12:00","nodes":[
                  {"nodeId":"source","supplyRate10k":7,"flowUse":"reference","pressureMpa":8,"pressureUse":"minimum","temperatureC":38}]}
                """,Boundary.class);
        assertNull(oldShape.cases());assertNull(oldShape.activeCaseId());assertNull(oldShape.nodes());
        assertThrows(BusinessException.class,()->PipelineBoundary.validate(oldShape,graph(),REVISION));
        assertThrows(BusinessException.class,()->PipelineBoundary.resolve(input(oldShape),graph(),REVISION));
        var standard=boundary(boundaryNode("source",7.0,null,8.0,38.0));
        var canonical=json.valueToTree(standard);
        assertEquals(3,canonical.size());assertFalse(canonical.has("nodes"));assertFalse(canonical.has("operatingAt"));
        assertEquals("case-1",canonical.path("activeCaseId").asText());
        assertFalse(canonical.toString().contains("flowUse"));assertFalse(canonical.toString().contains("pressureUse"));
        assertEquals(5,canonical.path("cases").get(0).path("nodes").get(0).size());
        assertEquals(standard,json.treeToValue(canonical,Boundary.class));
        assertEquals(7.0,PipelineBoundary.resolve(input(standard),graph(),REVISION).rate10k());
    }
    @Test void onlyTheSelectedCaseIsResolvedAndAssessedWhileAllCasesRemainInTheInput() {
        var first=new BoundaryCase("first","2026-09-08T12:00",List.of(
                boundaryNode("source",null,null,null,null),boundaryNode("end",null,null,4.0,null)));
        var second=new BoundaryCase("second","2026-09-08T13:00",List.of(
                boundaryNode("source",7.0,null,8.0,38.0),boundaryNode("end",null,null,5.5,null)));
        var boundary=new Boundary(REVISION,"second",List.of(first,second));
        Input actual=PipelineBoundary.resolve(input(boundary),graph(),REVISION);
        assertEquals("outlet",actual.target());assertEquals(7.0,actual.rate10k());assertEquals(38.0,actual.inletC());
        assertEquals(boundary,actual.boundary());assertEquals(2,actual.boundary().cases().size());
        Result assessed=PipelineBoundary.assess(actual,graph(),result());
        assertEquals(result().assessments(),assessed.assessments());
        assertEquals(5.5,actual.boundary().nodes().getLast().pressureMpa());
        var unready=new Boundary(REVISION,"first",boundary.cases());
        assertTrue(assertThrows(BusinessException.class,()->PipelineBoundary.resolve(input(unready),graph(),REVISION)).getMessage().contains("边界不完整"));
    }
    @Test void canonicalCasesNeverDefaultToTheFirstCaseWhenSelectionIsMissingOrInvalid() {
        var row=new BoundaryCase("ready","2026-09-08T12:00",List.of(boundaryNode("source",7.0,null,8.0,38.0)));
        var unselected=new Boundary(REVISION,null,List.of(row));
        assertDoesNotThrow(()->PipelineBoundary.validate(unselected,graph(),REVISION));
        assertNull(unselected.nodes());assertNull(unselected.activeCase());
        assertTrue(assertThrows(BusinessException.class,()->PipelineBoundary.resolve(input(unselected),graph(),REVISION)).getMessage().contains("选择"));
        assertThrows(BusinessException.class,()->PipelineBoundary.assess(input(unselected),graph(),result()));
        var missing=new Boundary(REVISION,"removed",List.of(row));
        assertNull(missing.activeCase());
        assertThrows(BusinessException.class,()->PipelineBoundary.validate(missing,graph(),REVISION));
        assertThrows(BusinessException.class,()->PipelineBoundary.resolve(input(missing),graph(),REVISION));
        var json=JsonMapper.builder().build();
        var loaded=json.treeToValue(json.valueToTree(unselected),Boundary.class);
        assertEquals(unselected,loaded);assertNull(loaded.activeCaseId());
    }
    @Test void everyCaseMustHaveAUniqueIdentityAndMultipleCasesNeedDistinctRealMinuteTimes() {
        var row=new BoundaryCase("first","2026-09-08T12:00",List.of());
        for(var invalid:List.of(new BoundaryCase("first","2026-09-08T13:00",List.of()),
                new BoundaryCase("other","2026-09-08T12:00",List.of()),new BoundaryCase("other",null,List.of()),
                new BoundaryCase("other","2026-09-08T12:60",List.of()),new BoundaryCase("other","2026-02-29T12:00",List.of()),
                new BoundaryCase(" ","2026-09-08T13:00",List.of()),new BoundaryCase("other","2026-09-08T13:00",null)))
            assertThrows(BusinessException.class,()->PipelineBoundary.validate(new Boundary(REVISION,"first",List.of(row,invalid)),graph(),REVISION));
        var oneDraft=new Boundary(REVISION,null,List.of(new BoundaryCase("draft",null,List.of())));
        assertDoesNotThrow(()->PipelineBoundary.validate(oneDraft,graph(),REVISION));
        assertThrows(BusinessException.class,()->PipelineBoundary.validateForSave(oneDraft,graph(),REVISION));
        assertThrows(BusinessException.class,()->PipelineBoundary.forSave(oneDraft));
    }
    @Test void unselectedCasesStillValidateNodeOwnershipDuplicatesAndNumbers() {
        var selected=new BoundaryCase("selected","2026-09-08T12:00",List.of());
        BoundaryNode source=boundaryNode("source",null,null,null,null);
        for(var invalid:List.of(List.of(boundaryNode("foreign",null,null,null,null)),List.of(source,source),
                List.of(boundaryNode("source",Double.NaN,null,null,null)),
                List.of(boundaryNode("source",-1.0,null,null,null)),
                List.of(boundaryNode("source",null,null,0.0,null)))) {
            var boundary=new Boundary(REVISION,"selected",List.of(selected,new BoundaryCase("unselected","2026-09-08T13:00",invalid)));
            var error=assertThrows(BusinessException.class,()->PipelineBoundary.validate(boundary,graph(),REVISION));
            assertTrue(error.getMessage().contains("第 2 行"));
        }
    }
    @Test void oneMonthOfHourlyCasesIsAcceptedWithoutRequiringContinuousTimesAndLargerImportsAreRejected() {
        var rows=new ArrayList<BoundaryCase>();var start=LocalDateTime.of(2026,9,1,0,0);
        for(int i=0;i<744;i++)rows.add(new BoundaryCase("row-"+i,start.plusHours(i*2L).format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")),List.of()));
        assertDoesNotThrow(()->PipelineBoundary.validate(new Boundary(REVISION,"row-743",rows),graph(),REVISION));
        rows.add(new BoundaryCase("extra","2026-12-01T00:00",List.of()));
        assertTrue(assertThrows(BusinessException.class,()->PipelineBoundary.validate(new Boundary(REVISION,"row-743",rows),graph(),REVISION)).getMessage().contains("744"));
    }
    private static void assertInvalid(BoundaryNode node) {
        assertThrows(BusinessException.class,()->PipelineBoundary.validate(boundary(node),graph(),REVISION));
    }
    private static Point point(int segment,String name,double distance,double pressure) {
        return new Point(segment,name,distance,pressure,35,2,50,100000,.02,.9,2300.0,null);
    }
    private static Result result() {
        var existing=new Assessment("equipment","设备",1000,"pass",4.0,10.0,6.0,"MPa","原设备评估");
        return new Result("boundary-test",8,5,7,35,3,2,35,0,1,0,
                List.of(point(0,"A 起点",0,8),point(0,"A",1000,6),point(1,"B 起点",1000,6),point(1,"B",2000,5)),
                List.of(),List.of(existing),List.of("保留原说明"));
    }
    @Test void measuredPressuresAndTemperaturesOnlyAddExplanationsAndPreserveEquipmentAssessments() {
        Result before=result();
        Input input=resolve(boundaryNode("source",7.0,null,8.0,47.0),boundaryNode("middle",null,null,6.5,50.0),
                boundaryNode("end",null,null,9.0,60.0));
        Result after=PipelineBoundary.assess(input,graph(),before);
        assertEquals(before.assessments(),after.assessments());
        assertEquals(before.inletMpa(),after.inletMpa());assertEquals(before.outletMpa(),after.outletMpa());assertEquals(before.rate10k(),after.rate10k());
        assertEquals(before.points(),after.points());assertEquals(before.equipment(),after.equipment());
        assertTrue(after.notes().containsAll(before.notes()));
        assertTrue(after.notes().stream().anyMatch(n->n.contains("末端实测压力保留用于结果对照")));
        assertTrue(after.notes().stream().anyMatch(n->n.contains("内部实测压力")));
        assertTrue(after.notes().stream().noneMatch(n->n.contains("minimum")||n.contains("given")||n.contains("最低压力")));
    }
}
