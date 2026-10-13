package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseCompletionFactorParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EclipseCompletionFactorParametersTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsChangedPositiveFactorBoundToInspectedCompdatRecord() throws Exception {
        JsonNode parameters = mapper.readTree("""
                {"schemaVersion":"eclipse-completion-factor-parameters/1","baselineRunId":74,"well":"G1","sourceFile":"BASE.SCH","lineNumber":116,"i":"14","j":"2","k1":"1","k2":"1","originalConnectionFactor":3.028,"targetConnectionFactor":1.5}
                """);
        JsonNode inspection = mapper.readTree("""
                {"schemaVersion":"eclipse-data-inspection/4","scheduleMetadata":{"wells":[],"groups":[],"records":[{"keyword":"COMPDAT","values":["G1","14","2","1","1","OPEN","1*","3.028","0.656"],"sourceFile":"BASE.SCH","lineNumber":116}],"completions":[{"keyword":"COMPDAT","well":"G1","i":"14","j":"2","k1":"1","k2":"1","status":"OPEN","sourceFile":"BASE.SCH","lineNumber":116}]}}
                """);

        assertThat(EclipseCompletionFactorParameters.valid(parameters)).isTrue();
        assertThat(EclipseCompletionFactorParameters.matchesInspection(parameters, inspection)).isTrue();
    }

    @Test
    void rejectsUnchangedOrUninspectedFactor() throws Exception {
        JsonNode same = mapper.readTree("""
                {"schemaVersion":"eclipse-completion-factor-parameters/1","baselineRunId":74,"well":"G1","sourceFile":"BASE.SCH","lineNumber":116,"i":"14","j":"2","k1":"1","k2":"1","originalConnectionFactor":3.028,"targetConnectionFactor":3.028}
                """);
        assertThat(EclipseCompletionFactorParameters.valid(same)).isFalse();
        assertThat(EclipseCompletionFactorParameters.matchesInspection(same, null)).isFalse();
    }

    @Test
    void rejectsCoercedNumericStringsAndNumericCoordinatesBeforeCreatingRun() throws Exception {
        String request = """
                {"schemaVersion":"eclipse-completion-factor-parameters/1","baselineRunId":74,"well":"G1","sourceFile":"BASE.SCH","lineNumber":116,"i":"14","j":"2","k1":"1","k2":"1","originalConnectionFactor":3.028,"targetConnectionFactor":1.5}
                """;
        for (String invalid : new String[]{request.replace("3.028", "\"3.028\""), request.replace("1.5", "\"1.5\""),
                request.replace("\"i\":\"14\"", "\"i\":14"), request.replace("\"G1\"", "1"), request.replace("\"BASE.SCH\"", "12")}) {
            assertThat(EclipseCompletionFactorParameters.valid(mapper.readTree(invalid))).isFalse();
        }
    }
}
