package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseGroupInjectionParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class EclipseGroupInjectionTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode p() throws Exception { return (ObjectNode) mapper.readTree("""
        {"schemaVersion":"eclipse-group-injection-parameters/1","baselineRunId":1,"group":"G","phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":17,"controlMode":"RATE","injectionPhase":"WATER","unit":"STB/day","originalTargetRate":60000,"targetRate":30000,"members":["P1","P2"]}
        """); }
    private ObjectNode i() throws Exception { return (ObjectNode) mapper.readTree("""
        {"schemaVersion":"eclipse-data-inspection/7","caseName":"CASE.DATA","sections":["RUNSPEC","SCHEDULE"],"unitSystem":"FIELD","phases":["WATER"],"dimensions":null,"wellNames":["P1","P2"],"scheduleTimeline":[{"kind":"TSTEP","steps":["1","9"],"sourceFile":"CASE.DATA","lineNumber":20}],"packageFiles":[{"relativePath":"CASE.DATA","sizeBytes":500,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"vfpTables":[],"scheduleMetadata":{"wells":[{"name":"P1","group":"G","sourceFile":"CASE.DATA","lineNumber":5},{"name":"P2","group":"G","sourceFile":"CASE.DATA","lineNumber":6}],"groups":[],"records":[],"completions":[]},"injectionGroupControls":[{"group":"G","sourceFile":"CASE.DATA","lineNumber":17,"controlMode":"RATE","injectionPhase":"WATER","unit":"STB/day","originalTargetRate":60000,"members":[{"well":"P1","bhpLimit":6000},{"well":"P2","bhpLimit":6000}]}]}
        """); }
    @Test void boundGroupAndMembersAcceptedInIndependentVersion() throws Exception {
        assertThat(EclipseGroupInjectionParameters.matchesInspection(p(),i())).isTrue();
        var saved = EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i(),mapper));
        assertThat(saved.path("schemaVersion").asText()).isEqualTo("eclipse-data-inspection/7");
        assertThat(saved.path("injectionGroupControls")).isEqualTo(i().path("injectionGroupControls"));
        assertThat(saved.size()).isEqualTo(12);
        assertThat(EclipseGroupInjectionParameters.matchesInspection(p(),saved)).isTrue();
    }
    @Test void staleSourceUnitsTargetsOrMembershipReject() throws Exception {
        for (var key : new String[]{"sourceFile","group","lineNumber","unit","originalTargetRate","members","injectionPhase"}) {
            var p=p();
            switch(key) { case "lineNumber"->p.put(key,18); case "originalTargetRate"->p.put(key,50000); case "members"->p.set(key,mapper.readTree("[\"P1\",\"WRONG\"]")); default->p.put(key,"WRONG"); }
            assertThat(EclipseGroupInjectionParameters.matchesInspection(p,i())).as(key).isFalse();
        }
        var i=i();((ObjectNode)i.path("scheduleMetadata").path("wells").get(1)).put("group","OTHER");
        assertThat(EclipseGroupInjectionParameters.matchesInspection(p(),i)).isFalse();
        assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oldVersionDoesNotAcceptNewFieldsAndDuplicateMembersReject() throws Exception {
        var i=i(); i.put("schemaVersion","eclipse-data-inspection/4");
        assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).isInstanceOf(IllegalArgumentException.class);
        var p=p();p.set("members",mapper.readTree("[\"P1\",\"P1\"]")); assertThat(EclipseGroupInjectionParameters.valid(p)).isFalse();
        p=p();p.put("extra",0);assertThat(EclipseGroupInjectionParameters.valid(p)).isFalse();
    }
    @Test void initialIndividualTargetScopeDoesNotWidenToGroupDeck() throws Exception {
        var i=i(); ((ObjectNode)i.path("scheduleMetadata")).set("records",mapper.readTree("""
            [{"keyword":"WCONPROD","sourceFile":"CASE.DATA","lineNumber":12,"values":["P1","OPEN","ORAT","20000","4*","6000"]}]
            """));
        var p=mapper.readTree("""
            {"schemaVersion":"eclipse-production-target-parameters/1","baselineRunId":1,"well":"P1","phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":12,"unit":"STB/day","originalOilRate":20000,"targetOilRate":10000}
            """);
        assertThat(com.grdp.studio.softwareintegration.support.EclipseProductionTargetParameters.matchesInspection(p,i)).isFalse();
        i.put("schemaVersion","eclipse-data-inspection/4");
        assertThat(com.grdp.studio.softwareintegration.support.EclipseProductionTargetParameters.matchesInspection(p,i)).isTrue();
    }
}
