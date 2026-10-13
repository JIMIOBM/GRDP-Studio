package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.GasLiftCapabilityValidator;
import com.grdp.studio.softwareintegration.support.PipesimWellInspectionValidator;
import com.grdp.studio.softwareintegration.support.WellScenarioParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class GasLiftCapabilityTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private String inspection(String state, String reason, String valve, String axis) {
        return """
                {"schemaVersion":"pipesim-well-inspection/9","reservoirPressure":{"value":5000,"unit":"psia"},
                "packageFiles":[{"relativePath":"model.pips","sizeBytes":12,"sha256":"%s"}],
                "well":"Well_1","espContexts":[],"wellContexts":[{"context":"Well_1","espContexts":[],
                "completionContexts":["Well_1:Completion"],"tubingContexts":["Well_1:Tubing"],"tubingComponents":[]}],
                "gasLiftCapabilities":[{"well":"Well_1","study":"Study 2","state":"%s","reason":"%s",
                "useGasliftValve":%s,"targetInjectionRateAvailable":%s}]}
                """.formatted("a".repeat(64), state, reason, valve, axis);
    }

    @Test void versionNinePreservesOldMetadataAndBindsExactStudy() {
        var data = mapper.readTree(inspection("supported", "GAS_LIFT_VALVE_SUPPORTED", "true", "true"));
        assertThat(PipesimWellInspectionValidator.validate(data)).isEqualTo(data);
        assertThat(data.path("reservoirPressure").path("value").asDouble()).isEqualTo(5000);
        assertThat(WellScenarioParameters.valid(mapper.nullNode(), "black_oil_liquid", "profile", data)).isTrue();
        assertThat(GasLiftCapabilityValidator.rejection(data, "Well_1", "Study 2")).isNull();
        assertThat(GasLiftCapabilityValidator.rejection(data, "Well_1", "Study 1")).isNotNull();
        assertThat(GasLiftCapabilityValidator.rejection(data, "Other", "Study 2")).isNotNull();
    }

    @Test void unsupportedPortUnknownAndOldInspectionRejectGasButKeepHistoryReadable() {
        var data = mapper.readTree(inspection("unsupported", "GAS_LIFT_VALVE_REQUIRED", "false", "false"));
        assertThat(PipesimWellInspectionValidator.validate(data)).isNotNull();
        assertThat(GasLiftCapabilityValidator.rejection(data, "Well_1", "Study 2")).contains("GLR");
        var unknown = mapper.readTree(inspection("unknown", "GAS_LIFT_CAPABILITY_UNKNOWN", "null", "null"));
        assertThat(PipesimWellInspectionValidator.validate(unknown)).isNotNull();
        assertThat(GasLiftCapabilityValidator.rejection(unknown, "Well_1", "Study 2")).isNotNull();
        assertThat(GasLiftCapabilityValidator.rejection(null, "Well_1", "Study 2")).contains("重新验证");
    }

    @Test void rejectsMalformedSupportAndExtraDiscoveryFields() {
        assertThat(PipesimWellInspectionValidator.validate(mapper.readTree(inspection("supported", "GAS_LIFT_VALVE_SUPPORTED", "false", "true")))).isNull();
        assertThat(PipesimWellInspectionValidator.validate(mapper.readTree(inspection("supported", "GAS_LIFT_VALVE_SUPPORTED", "true", "true").replace("\"study\":\"Study 2\"", "\"study\":\"Study 2\",\"other\":true")))).isNull();
    }
}
