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

    @Test
    void pureWellV8TubingInspectionDoesNotRequireNetworkCapability() {
        var json = mapper.readTree("""
                {"schemaVersion":"pipesim-well-inspection/8","reservoirPressure":null,
                 "packageFiles":[{"relativePath":"model.pips","sizeBytes":12,"sha256":"%s"}],
                 "well":"Well_1","espContexts":[],"wellContexts":[{"context":"Well_1","espContexts":[],
                 "completionContexts":["Well_1:Completion"],"tubingContexts":["Well_1:Tubing"],
                 "tubingComponents":[{"context":"Well_1:Tubing","innerDiameter":3.548,"unit":"in"}]}]}
                """.formatted("a".repeat(64)));
        assertThat(PipesimWellInspectionValidator.validate(json)).isNotNull();
        var explicitNull = (tools.jackson.databind.node.ObjectNode) json.deepCopy();
        explicitNull.putNull("networkCapability");
        assertThat(PipesimWellInspectionValidator.validate(explicitNull)).isNotNull();
        explicitNull.putObject("networkCapability");
        assertThat(PipesimWellInspectionValidator.validate(explicitNull)).isNull();
        var unknown = (tools.jackson.databind.node.ObjectNode) json.deepCopy();
        unknown.put("unknown", true);
        assertThat(PipesimWellInspectionValidator.validate(unknown)).isNull();
    }
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
        version.setModelKind("legacy_well");
        assertThat(SoftwareIntegrationModelVersionResponse.from(version).inspection()).isNotNull();
        for (String kind : new String[]{"network", "eclipse_100", null}) {
            version.setModelKind(kind);
            assertThat(SoftwareIntegrationModelVersionResponse.from(version).inspection()).isNull();
        }
        version.setModelKind("basic_gas");
        version.setInspectionJson(null);
        assertThat(SoftwareIntegrationModelVersionResponse.from(version).inspection()).isNull();
    }

    @Test
    void acceptsV2InspectionWithFrozenPackageManifest() throws Exception {
        String json = "{\"schemaVersion\":\"pipesim-well-inspection/2\",\"reservoirPressure\":null,\"packageFiles\":[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"" + "a".repeat(64) + "\"}]}";
        assertThat(PipesimWellInspectionValidator.parsePersisted(json)).isEqualTo(mapper.readTree(json));
    }

    @Test
    void acceptsV4InspectionWithExplicitEspContexts() throws Exception {
        String json = "{\"schemaVersion\":\"pipesim-well-inspection/4\",\"reservoirPressure\":null,\"packageFiles\":[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"" + "a".repeat(64) + "\"}],\"well\":\"Well_1\",\"espContexts\":[\"Well_1:ESP\"]}";
        assertThat(PipesimWellInspectionValidator.parsePersisted(json)).isEqualTo(mapper.readTree(json));
        for (String contexts : new String[]{"[\"Well_1:ESP\",\"Well_1:ESP\"]", "[\"../ESP\"]", "[null]", "{\"context\":\"Well_1:ESP\"}"}) {
            assertThat(PipesimWellInspectionValidator.parsePersisted(json.replace("[\"Well_1:ESP\"]", contexts))).isNull();
        }
    }

    @Test
    void acceptsV5WellToEspContextMapWithoutDefaultWell() throws Exception {
        String json = "{\"schemaVersion\":\"pipesim-well-inspection/5\",\"reservoirPressure\":null,\"packageFiles\":[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"" + "a".repeat(64) + "\"}],\"well\":null,\"espContexts\":[\"Well_1:ESP\",\"Well_2:ESP\"],\"wellContexts\":[{\"context\":\"Well_1\",\"espContexts\":[\"Well_1:ESP\"]},{\"context\":\"Well_2\",\"espContexts\":[\"Well_2:ESP\"]}]}";
        assertThat(PipesimWellInspectionValidator.parsePersisted(json)).isEqualTo(mapper.readTree(json));
        for (String malformed : new String[]{
                json.replace("\"well\":null", "\"well\":\"Well_1\""),
                json.replace("[\"Well_2:ESP\"]}]}", "[\"Well_1:ESP\"]}] }"),
                json.replace("\"context\":\"Well_2\"", "\"context\":\"../unsafe\"")}) {
            assertThat(PipesimWellInspectionValidator.parsePersisted(malformed)).isNull();
        }
    }

    @Test
    void acceptsV6WellScopedFlowObjectsAndRejectsUnsafeOrDuplicateContexts() throws Exception {
        String json = "{\"schemaVersion\":\"pipesim-well-inspection/6\",\"reservoirPressure\":null,\"packageFiles\":[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"" + "a".repeat(64) + "\"}],\"well\":\"Well_1\",\"espContexts\":[\"Well_1:ESP\"],\"wellContexts\":[{\"context\":\"Well_1\",\"espContexts\":[\"Well_1:ESP\"],\"completionContexts\":[\"Well_1:Completion\"],\"tubingContexts\":[\"Well_1:Tubing\"]}]}";
        assertThat(PipesimWellInspectionValidator.parsePersisted(json)).isEqualTo(mapper.readTree(json));
        assertThat(PipesimWellInspectionValidator.parsePersisted(json.replace("Well_1:Tubing", "../unsafe"))).isNull();
        assertThat(PipesimWellInspectionValidator.parsePersisted(json.replace("Well_1:Completion\"]", "Well_1:Completion\",\"Well_1:Completion\"]"))).isNull();
    }

    @Test
    void acceptsV7WellInspectionWithSeparatelyValidatedNetworkCapability() throws Exception {
        String manifest = "[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"" + "a".repeat(64) + "\"}]";
        String network = "{\"schemaVersion\":\"pipesim-network-inspection/3\",\"studies\":[{\"study\":\"Study 1\",\"boundaries\":[{\"node\":\"Source_1\",\"boundaryNodeType\":\"Source\",\"isActive\":true,\"isSurfaceCondition\":true,\"flowRateType\":\"GasFlowRate\",\"pressure\":120,\"temperature\":80,\"gasFlowRate\":5,\"liquidFlowRate\":null,\"massFlowRate\":null}]}],\"chokes\":[],\"packageFiles\":" + manifest + "}";
        String json = "{\"schemaVersion\":\"pipesim-well-inspection/7\",\"reservoirPressure\":null,\"packageFiles\":" + manifest
                + ",\"well\":\"Well_1\",\"espContexts\":[],\"wellContexts\":[{\"context\":\"Well_1\",\"espContexts\":[],\"completionContexts\":[\"Well_1:Completion\"],\"tubingContexts\":[\"Well_1:Tubing\"]}],"
                + "\"networkCapability\":{\"schemaVersion\":\"pipesim-network-capability/1\",\"studies\":[\"Study 1\"],\"networkInspection\":" + network + "}}";
        assertThat(PipesimWellInspectionValidator.parsePersisted(json)).isEqualTo(mapper.readTree(json));
        assertThat(PipesimWellInspectionValidator.networkInspection(mapper.readTree(json))).isNotNull();
        assertThat(PipesimWellInspectionValidator.parsePersisted(json.replace("\"studies\":[\"Study 1\"]", "\"studies\":[\"Study 2\"]"))).isNull();
        assertThat(PipesimWellInspectionValidator.parsePersisted(json.replace("networkCapability", "unexpected"))).isNull();
    }
}
