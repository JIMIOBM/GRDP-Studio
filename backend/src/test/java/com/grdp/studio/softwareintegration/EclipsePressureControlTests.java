package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipsePressureControlParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class EclipsePressureControlTests {
    private final JsonMapper mapper = new JsonMapper();
    private static final String REQUEST = """
      {"schemaVersion":"eclipse-pressure-control-parameters/1","baselineRunId":1,"well":"P1","phase":"INITIAL",
      "sourceFile":"CASE.DATA","lineNumber":5,"keyword":"WCONPROD","originalControlMode":"ORAT",
      "originalBhp":3000,"targetBhp":3500,"unit":"psia","injectionType":"NONE"}
      """;
    private static final String INSPECTION = """
      {"schemaVersion":"eclipse-data-inspection/4","caseName":"CASE.DATA","unitSystem":"FIELD",
      "packageFiles":[{"relativePath":"CASE.DATA"}],"scheduleTimeline":[{"kind":"TSTEP","sourceFile":"CASE.DATA","lineNumber":10}],
      "scheduleMetadata":{"wells":[{"name":"P1"},{"name":"I1"}],"records":[
      {"keyword":"WCONPROD","sourceFile":"CASE.DATA","lineNumber":5,"values":["P1","OPEN","ORAT","20000","4*","3000"]},
      {"keyword":"WCONINJE","sourceFile":"CASE.DATA","lineNumber":8,"values":["I1","WATER","OPEN","RESV","1*","1000","6000"]}]}}
      """;
    @Test void pressureRecordMatchesExpandedDefaultsSourceModeAndOriginal() {
        assertTrue(EclipsePressureControlParameters.valid(mapper.readTree(REQUEST)));
        assertTrue(EclipsePressureControlParameters.matchesInspection(mapper.readTree(REQUEST), mapper.readTree(INSPECTION)));
        for (String bad : new String[]{REQUEST.replace("3000", "2900"), REQUEST.replace("\"lineNumber\":5", "\"lineNumber\":4"),
            REQUEST.replace("ORAT", "BHP"), REQUEST.replace("psia", "barsa"), REQUEST.replace("CASE.DATA", "../CASE.DATA")})
            assertFalse(EclipsePressureControlParameters.matchesInspection(mapper.readTree(bad), mapper.readTree(INSPECTION)));
        assertFalse(EclipsePressureControlParameters.matchesInspection(mapper.readTree(REQUEST), mapper.readTree(INSPECTION.replace("4*\",\"3000", "5*"))));
    }
    @Test void injectionFluidIsBoundAndProductionCannotDeclareOne() {
        String injection = REQUEST.replace("P1", "I1").replace("WCONPROD", "WCONINJE").replace("ORAT", "RESV")
            .replace("\"lineNumber\":5", "\"lineNumber\":8").replace("3000", "6000").replace("3500", "5000").replace("NONE", "WATER");
        assertTrue(EclipsePressureControlParameters.matchesInspection(mapper.readTree(injection), mapper.readTree(INSPECTION)));
        assertFalse(EclipsePressureControlParameters.matchesInspection(mapper.readTree(injection.replace("WATER", "GAS")), mapper.readTree(INSPECTION)));
        assertFalse(EclipsePressureControlParameters.valid(mapper.readTree(REQUEST.replace("NONE", "WATER"))));
        assertFalse(EclipsePressureControlParameters.valid(mapper.readTree(REQUEST.replace("\"targetBhp\":3500", "\"targetBhp\":0"))));
        assertFalse(EclipsePressureControlParameters.valid(mapper.readTree(REQUEST.replace("\"baselineRunId\":1", "\"baselineRunId\":1,\"status\":\"SHUT\""))));
    }
}
