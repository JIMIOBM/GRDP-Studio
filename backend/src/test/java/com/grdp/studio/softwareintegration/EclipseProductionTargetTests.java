package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseProductionTargetParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class EclipseProductionTargetTests {
    private final JsonMapper mapper = new JsonMapper();
    private static final String REQUEST = """
        {"schemaVersion":"eclipse-production-target-parameters/1","baselineRunId":1,"well":"P1",
        "phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":6,"unit":"STB/day",
        "originalOilRate":20000,"targetOilRate":10000}
        """;
    private static final String INSPECTION = """
        {"schemaVersion":"eclipse-data-inspection/4","unitSystem":"FIELD","caseName":"CASE.DATA",
         "packageFiles":[{"relativePath":"CASE.DATA"}],"scheduleTimeline":[{"kind":"TSTEP","sourceFile":"CASE.DATA","lineNumber":10}],
         "scheduleMetadata":{"wells":[{"name":"P1"}],"records":[
          {"keyword":"WCONPROD","values":["P1","OPEN","ORAT","20000","4*","2500"],"sourceFile":"CASE.DATA","lineNumber":6}]}}
        """;
    @Test void bindsOriginalValueUnitSourceLineAndInitialOpenRecord() {
        var request = mapper.readTree(REQUEST);
        assertTrue(EclipseProductionTargetParameters.valid(request));
        assertTrue(EclipseProductionTargetParameters.matchesInspection(request, mapper.readTree(INSPECTION)));
        for (String bad : new String[]{INSPECTION.replace("20000", "19000"), INSPECTION.replace("\"OPEN\"", "\"SHUT\""),
            INSPECTION.replace("\"FIELD\"", "\"METRIC\""), INSPECTION.replace("\"lineNumber\":10", "\"lineNumber\":5"),
            INSPECTION.replace("\"lineNumber\":6", "\"lineNumber\":7"), INSPECTION.replace("\"CASE.DATA\"", "\"OTHER.DATA\"")}) {
            assertFalse(EclipseProductionTargetParameters.matchesInspection(request, mapper.readTree(bad)));
        }
    }
    @Test void rejectsUnexpectedFieldsUnitsUnknownWellAndUnchangedTarget() {
        for (String bad : new String[]{REQUEST.replace("10000", "20000"), REQUEST.replace("10000", "-1"),
            REQUEST.replace("\"STB/day\"", "\"sm3/day\""), REQUEST.replace("\"CASE.DATA\"", "\"../CASE.DATA\""),
            REQUEST.replace("\"baselineRunId\":1", "\"baselineRunId\":1,\"status\":\"SHUT\"")}) {
            assertFalse(EclipseProductionTargetParameters.valid(mapper.readTree(bad)));
        }
        assertFalse(EclipseProductionTargetParameters.matchesInspection(mapper.readTree(REQUEST.replace("\"P1\"", "\"MISSING\"")), mapper.readTree(INSPECTION)));
    }
}
