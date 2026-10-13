package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.PipesimWellResultValidator;
import com.grdp.studio.softwareintegration.support.PipesimWellInspectionValidator;
import com.grdp.studio.softwareintegration.support.WellTubingDiameterParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipesimWellTubingDiameterTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void v8InspectionBindsParameterRequestToOneInspectedTubing() throws Exception {
        JsonNode inspection = PipesimWellInspectionValidator.validate(mapper.readTree(inspectionJson()));
        assertThat(inspection).isNotNull();
        assertThat(WellTubingDiameterParameters.valid(mapper.readTree(parametersJson()), inspection)).isTrue();
        assertThat(WellTubingDiameterParameters.valid(
                mapper.readTree(parametersJson().replace("\"originalInnerDiameter\":2.441", "\"originalInnerDiameter\":2.4")), inspection)).isFalse();
        assertThat(WellTubingDiameterParameters.valid(
                mapper.readTree(parametersJson().replace("\"unit\":\"in\"", "\"unit\":\"mm\"")), inspection)).isFalse();
        assertThat(WellTubingDiameterParameters.valid(
                mapper.readTree(parametersJson().replace("Well_1:Tubing", "Well_2:Tubing")), inspection)).isFalse();
        assertThat(WellTubingDiameterParameters.valid(
                mapper.readTree(parametersJson().replace("\"targetInnerDiameter\":2.3", "\"targetInnerDiameter\":2.441")), inspection)).isFalse();
    }

    @Test
    void onlyAcceptsPersistedTubingApplicationMatchingTheRequestedChange() throws Exception {
        JsonNode parameters = mapper.readTree(parametersJson());
        var validator = new PipesimWellResultValidator();
        var accepted = validator.validate("profile", mapper.readTree(resultJson()), parameters);
        assertThat(accepted.contract()).isEqualTo("VALID_FULL");

        for (String malformed : new String[]{
                resultJson().replace("\"appliedInnerDiameter\":2.3", "\"appliedInnerDiameter\":2.31"),
                resultJson().replace("\"sourceModelUnchanged\":true", "\"sourceModelUnchanged\":false"),
                resultJson().replace("pipesim-well-tubing-applied.pips", "other.pips"),
                resultJson().replace("\"unit\":\"in\"", "\"unit\":\"mm\"")}) {
            assertThatThrownBy(() -> validator.validate("profile", mapper.readTree(malformed), parameters))
                    .isInstanceOf(PipesimWellResultValidator.ResultValidationException.class);
        }
        assertThatThrownBy(() -> validator.validate("profile", mapper.readTree(resultJson()), mapper.readTree("null")))
                .isInstanceOf(PipesimWellResultValidator.ResultValidationException.class);
    }

    private static String parametersJson() {
        return """
                {"schemaVersion":"pipesim-well-tubing-diameter-parameters/1","baselineRunId":7401,"wellContext":"Well_1","tubingContext":"Well_1:Tubing","originalInnerDiameter":2.441,"unit":"in","targetInnerDiameter":2.3}
                """;
    }

    private static String resultJson() {
        return """
                {"schemaVersion":"pipesim-well-result/2","model_kind":"black_oil_liquid","runTask":"profile","resultContract":"VALID_FULL","units":{"flow":{"displayUnit":null,"semantics":"unspecified"},"pressure":{"displayUnit":null,"semantics":"unspecified"},"depth":{"displayUnit":null,"semantics":"unspecified"},"temperature":{"displayUnit":null,"semantics":"unspecified"}},"ipr":[],"vlp":[],"profile":[{"depth":0.0,"pressure":1500.0,"temperature":80.0}],"application":{"applied":true,"isolatedCopy":true,"sourceModelUnchanged":true,"artifactName":"pipesim-well-tubing-applied.pips","wellContext":"Well_1","tubingContext":"Well_1:Tubing","unit":"in","originalInnerDiameter":2.441,"targetInnerDiameter":2.3,"appliedInnerDiameter":2.3}}
                """;
    }

    private static String inspectionJson() {
        return """
                {"schemaVersion":"pipesim-well-inspection/8","reservoirPressure":null,"packageFiles":[{"relativePath":"model.pips","sizeBytes":12,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"well":"Well_1","espContexts":[],"wellContexts":[{"context":"Well_1","espContexts":[],"completionContexts":["Well_1:Completion"],"tubingContexts":["Well_1:Tubing"],"tubingComponents":[{"context":"Well_1:Tubing","innerDiameter":2.441,"unit":"in"}]}],"networkCapability":{"schemaVersion":"pipesim-network-capability/1","studies":["Study 1"],"networkInspection":{"schemaVersion":"pipesim-network-inspection/5","studies":[{"study":"Study 1","boundaries":[{"node":"Source_1","boundaryNodeType":"Source","isActive":true,"isSurfaceCondition":true,"flowRateType":"GasFlowRate","pressure":120.0,"temperature":80.0,"gasFlowRate":5.0,"liquidFlowRate":null,"massFlowRate":null}]}],"chokes":[],"packageFiles":[{"relativePath":"model.pips","sizeBytes":12,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"wellContexts":[],"flowlineContexts":[],"flowlines":[]}}}
                """;
    }
}
