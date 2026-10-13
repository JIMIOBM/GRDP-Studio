package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.PipesimEspParameterResultValidator;
import com.grdp.studio.softwareintegration.execution.PipesimWellResultValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipesimEspParameterResultValidatorTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PipesimEspParameterResultValidator validator = new PipesimEspParameterResultValidator();

    @Test
    void acceptsBaselineAndTargetCalculationCases() throws Exception {
        JsonNode result = mapper.readTree("""
                {
                  "schemaVersion":"pipesim-esp-parameter-result/1","model_kind":"black_oil_liquid","runTask":"esp-parameter","resultContract":"VALID_FULL","producer":"Well_1","espContext":"Well_1:ESP","parameter":"operatingFrequency","unit":"Hz","originalValue":60,"targetValue":70,"appliedValue":70,"sourceModelUnchanged":true,"isolatedCopy":true,
                  "baseline":{"frequencyHz":60,"ipr":[{"flow":100,"pressure":1200}],"vlp":[{"flow":100,"pressure":1300}],"profile":[{"depth":0,"pressure":1500,"temperature":80}]},
                  "target":{"frequencyHz":70,"ipr":[{"flow":100,"pressure":1250}],"vlp":[{"flow":100,"pressure":1350}],"profile":[{"depth":0,"pressure":1550,"temperature":82}]},
                  "comparison":{"maxIprPressureDelta":null,"maxVlpPressureDelta":50,"maxProfilePressureDelta":50,"maxProfileTemperatureDelta":2}
                }
                """);

        PipesimWellResultValidator.ValidatedResult validated = validator.validate(result);

        assertThat(validated.contract()).isEqualTo("VALID_FULL");
        assertThat(validated.terminalStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(validated.result().path("target").path("frequencyHz").asDouble()).isEqualTo(70D);
    }

    @Test
    void rejectsTargetThatWasNotApplied() throws Exception {
        JsonNode result = mapper.readTree("""
                {
                  "schemaVersion":"pipesim-esp-parameter-result/1","model_kind":"black_oil_liquid","runTask":"esp-parameter","resultContract":"VALID_FULL","producer":"Well_1","espContext":"Well_1:ESP","parameter":"operatingFrequency","unit":"Hz","originalValue":60,"targetValue":70,"appliedValue":65,"sourceModelUnchanged":true,"isolatedCopy":true,
                  "baseline":{"frequencyHz":60,"ipr":[{"flow":100,"pressure":1200}],"vlp":[{"flow":100,"pressure":1300}],"profile":[{"depth":0,"pressure":1500,"temperature":80}]},
                  "target":{"frequencyHz":65,"ipr":[{"flow":100,"pressure":1250}],"vlp":[{"flow":100,"pressure":1350}],"profile":[{"depth":0,"pressure":1550,"temperature":82}]},
                  "comparison":{"maxIprPressureDelta":50,"maxVlpPressureDelta":50,"maxProfilePressureDelta":50,"maxProfileTemperatureDelta":2}
                }
                """);

        assertThatThrownBy(() -> validator.validate(result))
                .isInstanceOf(PipesimWellResultValidator.ResultValidationException.class);
    }

    @Test
    void acceptsDimensionlessHeadFactorResultV2() throws Exception {
        JsonNode result = mapper.readTree("""
                {
                  "schemaVersion":"pipesim-esp-parameter-result/2","model_kind":"legacy_well","runTask":"esp-parameter","resultContract":"VALID_FULL","producer":"Well_1","espContext":"Well_1:ESP","parameter":"headFactor","unit":"1","originalValue":2.5,"targetValue":0.9,"appliedValue":0.9,"sourceModelUnchanged":true,"isolatedCopy":true,
                  "baseline":{"parameterValue":2.5,"ipr":[{"flow":100,"pressure":1200}],"vlp":[{"flow":100,"pressure":1300}],"profile":[{"depth":0,"pressure":1500,"temperature":80}]},
                  "target":{"parameterValue":0.9,"ipr":[{"flow":100,"pressure":1201}],"vlp":[{"flow":100,"pressure":1301}],"profile":[{"depth":0,"pressure":1500,"temperature":80}]},
                  "comparison":{"maxIprPressureDelta":1,"maxVlpPressureDelta":1,"maxProfilePressureDelta":null,"maxProfileTemperatureDelta":null}
                }
                """);

        PipesimWellResultValidator.ValidatedResult validated = validator.validate(result);

        assertThat(validated.contract()).isEqualTo("VALID_FULL");
        assertThat(validated.result().path("target").path("parameterValue").asDouble()).isEqualTo(0.9D);
    }
}
