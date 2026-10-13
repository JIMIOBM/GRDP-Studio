package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseGroupWaterProductionParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class EclipseGroupWaterProductionTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode p() throws Exception { return (ObjectNode) mapper.readTree("""
        {"schemaVersion":"eclipse-group-water-production-parameters/1","baselineRunId":1,"group":"G","phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":17,"controlMode":"WRAT","unit":"STB/day","originalTargetRate":60000,"targetRate":30000,"members":["P1","P2"]}
        """); }
    private ObjectNode i() throws Exception { return (ObjectNode) mapper.readTree("""
        {"schemaVersion":"eclipse-data-inspection/10","caseName":"CASE.DATA","sections":["RUNSPEC","SCHEDULE"],"unitSystem":"FIELD","phases":["OIL"],"dimensions":null,"wellNames":["P1","P2"],"scheduleTimeline":[{"kind":"TSTEP","steps":["1","9"],"sourceFile":"CASE.DATA","lineNumber":20}],"packageFiles":[{"relativePath":"CASE.DATA","sizeBytes":500,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"vfpTables":[],"scheduleMetadata":{"wells":[{"name":"P1","group":"G","sourceFile":"CASE.DATA","lineNumber":5},{"name":"P2","group":"G","sourceFile":"CASE.DATA","lineNumber":6}],"groups":[],"records":[],"completions":[]},"waterProductionGroupControls":[{"group":"G","sourceFile":"CASE.DATA","lineNumber":17,"controlMode":"WRAT","unit":"STB/day","originalTargetRate":60000,"members":[{"well":"P1","bhpLimit":2500,"oilRateLimit":20000},{"well":"P2","bhpLimit":2500,"oilRateLimit":20000}]}]}
        """); }
    @Test void boundGroupAndMembersAcceptedInIndependentVersion() throws Exception {
        assertThat(EclipseGroupWaterProductionParameters.matchesInspection(p(),i())).isTrue();
        var saved = EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i(),mapper));
        assertThat(saved.path("schemaVersion").asText()).isEqualTo("eclipse-data-inspection/10");
        assertThat(saved.path("waterProductionGroupControls")).isEqualTo(i().path("waterProductionGroupControls"));
        assertThat(saved.size()).isEqualTo(12);
        assertThat(EclipseGroupWaterProductionParameters.matchesInspection(p(),saved)).isTrue();
    }
    @Test void staleSourceUnitsTargetsOrMembershipReject() throws Exception {
        for (var key : new String[]{"sourceFile","group","lineNumber","unit","originalTargetRate","members"}) {
            var p=p();
            switch(key) { case "lineNumber"->p.put(key,18); case "originalTargetRate"->p.put(key,50000); case "members"->p.set(key,mapper.readTree("[\"P1\",\"WRONG\"]")); default->p.put(key,"WRONG"); }
            assertThat(EclipseGroupWaterProductionParameters.matchesInspection(p,i())).as(key).isFalse();
        }
        var i=i();((ObjectNode)i.path("scheduleMetadata").path("wells").get(1)).put("group","OTHER");
        assertThat(EclipseGroupWaterProductionParameters.matchesInspection(p(),i)).isFalse();
        assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oldVersionDoesNotAcceptNewFieldsAndDuplicateMembersReject() throws Exception {
        var i=i(); i.put("schemaVersion","eclipse-data-inspection/4");
        assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).isInstanceOf(IllegalArgumentException.class);
        var p=p();p.set("members",mapper.readTree("[\"P1\",\"P1\"]")); assertThat(EclipseGroupWaterProductionParameters.valid(p)).isFalse();
        p=p();p.put("extra",0);assertThat(EclipseGroupWaterProductionParameters.valid(p)).isFalse();
    }
    @Test void initialIndividualTargetScopeDoesNotWidenToGroupDeck() throws Exception {
        var i=i(); ((ObjectNode)i.path("scheduleMetadata")).set("records",mapper.readTree("""
            [{"keyword":"WCONPROD","sourceFile":"CASE.DATA","lineNumber":12,"values":["P1","OPEN","ORAT","20000","4*","2500"]}]
            """));
        var p=mapper.readTree("""
            {"schemaVersion":"eclipse-production-target-parameters/1","baselineRunId":1,"well":"P1","phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":12,"unit":"STB/day","originalOilRate":20000,"targetOilRate":10000}
            """);
        assertThat(com.grdp.studio.softwareintegration.support.EclipseProductionTargetParameters.matchesInspection(p,i)).isFalse();
        i.put("schemaVersion","eclipse-data-inspection/4");
        assertThat(com.grdp.studio.softwareintegration.support.EclipseProductionTargetParameters.matchesInspection(p,i)).isTrue();
    }
    @Test void liquidModeAndMetricUnitsMustAgreeWithModeAndSource() throws Exception {
        var p=p();p.put("controlMode","WRAT");p.put("unit","STB/day");
        var i=i();var g=(ObjectNode)i.path("waterProductionGroupControls").get(0);g.put("controlMode","WRAT");g.put("unit","STB/day");
        var saved=EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));
        assertThat(EclipseGroupWaterProductionParameters.matchesInspection(p,saved)).isTrue();
        i.put("unitSystem","METRIC");g.put("unit","sm3/day");p.put("unit","sm3/day");
        saved=EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));
        assertThat(EclipseGroupWaterProductionParameters.matchesInspection(p,saved)).isTrue();
        g.put("unit","Mscf/day");
        assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void wrongModesUnitsAndOldVersionNeverBind() throws Exception {
        for(var mode:new String[]{"GRAT","LRAT","ORAT","RESV","RATE"}) {
            var p=p();p.put("controlMode",mode);assertThat(EclipseGroupWaterProductionParameters.valid(p)).as(mode).isFalse();
            var i=i();((ObjectNode)i.path("waterProductionGroupControls").get(0)).put("controlMode",mode);
            assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).isInstanceOf(IllegalArgumentException.class);
        }
        var p=p();p.put("unit","Mscf/day");assertThat(EclipseGroupWaterProductionParameters.valid(p)).isFalse();
        var i=i();i.put("schemaVersion","eclipse-data-inspection/6");
        assertThat(EclipseGroupWaterProductionParameters.matchesInspection(p(),i)).isFalse();
        assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).isInstanceOf(IllegalArgumentException.class);
    }

}
