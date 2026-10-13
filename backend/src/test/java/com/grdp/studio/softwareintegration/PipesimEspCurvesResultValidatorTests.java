package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.PipesimEspCurvesResultValidator;
import com.grdp.studio.softwareintegration.execution.PipesimWellResultValidator;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipesimEspCurvesResultValidatorTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PipesimEspCurvesResultValidator validator = new PipesimEspCurvesResultValidator();

    @Test
    void acceptsContextBoundLegacyWellResultAndPreservesSelectedPumpName() {
        var result = mapper.readTree(resultJson("Well_2:ESP", "ESP-2", "legacy_well"));

        var validated = validator.validate(result);

        assertThat(validated.terminalStatus()).isEqualTo(SoftwareIntegrationRunStatus.SUCCEEDED);
        assertThat(validated.result()).isSameAs(result);
    }

    @Test
    void rejectsUnboundOrUnsafeContextInContextBoundResult() {
        var missing = mapper.readTree(resultJson("Well_2:ESP", "ESP-2", "legacy_well").replace(",\"espContext\":\"Well_2:ESP\"", ""));
        var unsafe = mapper.readTree(resultJson("../ESP", "ESP-2", "legacy_well"));

        assertThatThrownBy(() -> validator.validate(missing))
                .isInstanceOf(PipesimWellResultValidator.ResultValidationException.class);
        assertThatThrownBy(() -> validator.validate(unsafe))
                .isInstanceOf(PipesimWellResultValidator.ResultValidationException.class);
    }

    private static String resultJson(String context, String pumpName, String modelKind) {
        String pump = """
                {"pumpName":"%s","inputs":{"frequency":60,"frequencyUnit":"Hz","manufacturer":"REDA","model":"J7000N","minFlowRate":100,"maxFlowRate":1000,"stages":120},
                 "frequencies":[{"frequencyHz":60,"frequencyLabel":"60 Hz","flowRate":[100,500],"flowRateUnit":"bbl/d","head":[1000,800],"headUnit":"ft"}],
                 "operatingEnvelope":{"qMin":{"flowRate":[100],"flowRateUnit":"bbl/d","head":[1000],"headUnit":"ft"},
                 "bep":{"flowRate":[500],"flowRateUnit":"bbl/d","head":[800],"headUnit":"ft"},
                 "qMax":{"flowRate":[1000],"flowRateUnit":"bbl/d","head":[600],"headUnit":"ft"}}}
                """.formatted(pumpName);
        return """
                {"schemaVersion":"pipesim-esp-curves-result/2","model_kind":"%s","runTask":"esp-curves","resultContract":"VALID_FULL",
                 "producer":"Well_2","espContext":"%s","pump":%s,"nodalPump":%s}
                """.formatted(modelKind, context, pump, pump);
    }
}
