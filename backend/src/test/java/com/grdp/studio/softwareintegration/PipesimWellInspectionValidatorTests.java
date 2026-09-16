package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationModelVersionResponse;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;
import com.grdp.studio.softwareintegration.support.PipesimWellInspectionValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class PipesimWellInspectionValidatorTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private static String inspection(String pressure) {
        return "{\"schemaVersion\":\"pipesim-well-inspection/1\",\"reservoirPressure\":" + pressure + "}";
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{\"value\":4000.5,\"unit\":\"psia\"}", "{\"value\":100001,\"unit\":\"psia\"}"})
    void preservesVerifiedNumbersAndExplicitAbsence(String pressure) {
        String json = inspection(pressure);
        assertThat(PipesimWellInspectionValidator.parsePersisted(json)).isEqualTo(mapper.readTree(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "\"4000\"", "0", "-1", "1e999", "1.2345e25", "-1e31", "null"})
    void rejectsNonNumericNonPositiveAndMissingSentinels(String value) {
        assertThat(PipesimWellInspectionValidator.parsePersisted(inspection("{\"value\":" + value + ",\"unit\":\"psia\"}"))).isNull();
    }

    @Test
    void rejectsMalformedOptionalMetadataWithoutThrowing() {
        for (String json : new String[]{null, "", "broken", "null", "{}",
                inspection("{\"value\":4000,\"unit\":\"psi\"}"),
                inspection("{\"value\":4000,\"unit\":\"psia\",\"path\":\"private\"}"),
                inspection("null").replace("/1", "/2"),
                inspection("null").replace("}", ",\"extra\":true}")}) {
            assertThat(PipesimWellInspectionValidator.parsePersisted(json)).isNull();
        }
        assertThat(PipesimWellInspectionValidator.validateAndSerialize(null, mapper)).isNull();
    }

    @Test
    void responseRequiresReadySupportedVersionAndHandlesLegacyMetadata() {
        SoftwareIntegrationModelVersionEntity version = new SoftwareIntegrationModelVersionEntity();
        version.setInspectionJson(inspection("{\"value\":4000,\"unit\":\"psia\"}"));
        for (String kind : new String[]{"basic_gas", "black_oil_liquid"}) {
            version.setModelKind(kind);
            version.setStatus("READY");
            assertThat(SoftwareIntegrationModelVersionResponse.from(version).inspection()).isNotNull();
            for (String status : new String[]{"UPLOADED", "VALIDATING", "INVALID", "ENVIRONMENT_ERROR"}) {
                version.setStatus(status);
                assertThat(SoftwareIntegrationModelVersionResponse.from(version).inspection()).isNull();
            }
        }
        version.setStatus("READY");
        for (String kind : new String[]{"legacy_well", "network", "eclipse_100", null}) {
            version.setModelKind(kind);
            assertThat(SoftwareIntegrationModelVersionResponse.from(version).inspection()).isNull();
        }
        version.setModelKind("basic_gas");
        version.setInspectionJson(null);
        assertThat(SoftwareIntegrationModelVersionResponse.from(version).inspection()).isNull();
    }
}
