package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseAdvancedInjectionParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class EclipseAdvancedInjectionTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode p(String mode) throws Exception {
        var p=(ObjectNode)mapper.readTree("""
          {"schemaVersion":"eclipse-advanced-injection-parameters/1","baselineRunId":1,"group":"I","phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":17,"controlMode":"RATE","injectionPhase":"OIL","unit":"STB/day","originalTargetRate":5000,"targetRate":2500,"members":["I1","I2"],"referenceGroup":null,"referenceMembers":[]}
          """);
        p.put("controlMode",mode);p.put("injectionPhase",mode.equals("RATE")?"OIL":"GAS");p.put("unit",EclipseAdvancedInjectionParameters.unit("FIELD",p.path("injectionPhase").asText(),mode));
        if(mode.equals("REIN")||mode.equals("VREP")){p.put("originalTargetRate",0.2);p.put("targetRate",0.1);p.put("referenceGroup","G");p.set("referenceMembers",mapper.readTree("[\"P1\",\"P2\"]"));}
        return p;
    }
    private ObjectNode i(String mode) throws Exception {
        var i=(ObjectNode)mapper.readTree("""
          {"schemaVersion":"eclipse-data-inspection/11","caseName":"CASE.DATA","sections":["RUNSPEC","SCHEDULE"],"unitSystem":"FIELD","phases":["OIL","GAS"],"dimensions":null,"wellNames":["I1","I2","P1","P2"],"scheduleTimeline":[{"kind":"TSTEP","steps":["1","9"],"sourceFile":"CASE.DATA","lineNumber":20}],"packageFiles":[{"relativePath":"CASE.DATA","sizeBytes":500,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"vfpTables":[],"scheduleMetadata":{"wells":[{"name":"I1","group":"I","sourceFile":"CASE.DATA","lineNumber":5},{"name":"I2","group":"I","sourceFile":"CASE.DATA","lineNumber":6},{"name":"P1","group":"G","sourceFile":"CASE.DATA","lineNumber":7},{"name":"P2","group":"G","sourceFile":"CASE.DATA","lineNumber":8}],"groups":[],"records":[],"completions":[]},"advancedInjectionGroupControls":[]}
          """);
        var g=p(mode);g.remove("schemaVersion");g.remove("baselineRunId");g.remove("phase");g.remove("targetRate");g.set("members",mapper.readTree("[{\"well\":\"I1\",\"bhpLimit\":4900,\"surfaceRateLimit\":50000},{\"well\":\"I2\",\"bhpLimit\":4900,\"surfaceRateLimit\":50000}]"));
        i.withArray("advancedInjectionGroupControls").add(g);return i;
    }
    @Test void fourModesPersistWithIndependentUnitsAndExplicitReferences() throws Exception {
        for(var mode:new String[]{"RATE","RESV","REIN","VREP"}) {
            var saved=EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i(mode),mapper));
            assertThat(saved.size()).isEqualTo(12);assertThat(EclipseAdvancedInjectionParameters.matchesInspection(p(mode),saved)).as(mode).isTrue();
        }
    }
    @Test void sourceReferenceUnitAndOrderMustMatchBeforeCreatingRun() throws Exception {
        for(var key:new String[]{"sourceFile","lineNumber","injectionPhase","controlMode","unit","referenceGroup","referenceMembers","members"}) {
            var p=p("REIN");switch(key){case "lineNumber"->p.put(key,18);case "referenceMembers"->p.set(key,mapper.readTree("[\"P2\",\"P1\"]"));case "members"->p.set(key,mapper.readTree("[\"I2\",\"I1\"]"));default->p.put(key,"WRONG");}
            assertThat(EclipseAdvancedInjectionParameters.matchesInspection(p,i("REIN"))).as(key).isFalse();
        }
    }
    @Test void incompleteOrIncorrectReferenceMembershipRejectsMetadataAndBinding() throws Exception {
        for(var fault:new String[]{"group","injection","missing"}) {
            var i=i("REIN");if(fault.equals("missing"))i.withArray("advancedInjectionGroupControls").get(0).withArray("referenceMembers").remove(1);
            else ((ObjectNode)i.path("scheduleMetadata").path("wells").get(3)).put("group",fault.equals("group")?"OTHER":"I");
            assertThat(EclipseAdvancedInjectionParameters.matchesInspection(p("REIN"),i)).isFalse();
            assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void missingReferencesExtraFieldsAndLegacyVersionsReject() throws Exception {
        var p=p("REIN");p.putNull("referenceGroup");assertThat(EclipseAdvancedInjectionParameters.valid(p)).isFalse();
        p=p("RATE");p.put("referenceGroup","G");assertThat(EclipseAdvancedInjectionParameters.valid(p)).isFalse();
        p=p("VREP");p.put("extra",0);assertThat(EclipseAdvancedInjectionParameters.valid(p)).isFalse();
        var i=i("RESV");i.put("schemaVersion","eclipse-data-inspection/8");assertThat(EclipseAdvancedInjectionParameters.matchesInspection(p("RESV"),i)).isFalse();
        assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unitsDistinguishReservoirSurfaceAndDimensionlessFractions() throws Exception {
        for(var mode:new String[]{"RATE","RESV","REIN","VREP"}) {
            var p=p(mode);var i=i(mode);i.put("unitSystem","METRIC");var unit=EclipseAdvancedInjectionParameters.unit("METRIC",p.path("injectionPhase").asText(),mode);p.put("unit",unit);((ObjectNode)i.path("advancedInjectionGroupControls").get(0)).put("unit",unit);
            var saved=EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));assertThat(EclipseAdvancedInjectionParameters.matchesInspection(p,saved)).isTrue();
            ((ObjectNode)i.path("advancedInjectionGroupControls").get(0)).put("unit","Mscf/day");assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void oilResvAndWaterAdvancedModesAreNotAdvertised() throws Exception {
        for(var mode:new String[]{"RESV","REIN","VREP"}){var p=p(mode);p.put("injectionPhase","OIL");assertThat(EclipseAdvancedInjectionParameters.valid(p)).isFalse();}
        var p=p("RATE");p.put("injectionPhase","GAS");assertThat(EclipseAdvancedInjectionParameters.valid(p)).isFalse();
        p.put("injectionPhase","WATER");assertThat(EclipseAdvancedInjectionParameters.valid(p)).isFalse();
    }
}
