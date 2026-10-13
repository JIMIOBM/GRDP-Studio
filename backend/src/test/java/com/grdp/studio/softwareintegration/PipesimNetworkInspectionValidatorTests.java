package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.PipesimNetworkInspectionValidator;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipesimNetworkInspectionValidatorTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void normalizesDisplaySafeStudyBoundaryMetadata() throws Exception {
        var input = mapper.readTree("{\"schemaVersion\":\"pipesim-network-inspection/1\",\"studies\":[{\"study\":\"Study 1\",\"boundaries\":[{\"node\":\"Supply_1\",\"boundaryNodeType\":\"Source\",\"isActive\":true,\"isSurfaceCondition\":true,\"flowRateType\":\"GasFlowRate\",\"pressure\":null,\"temperature\":131,\"gasFlowRate\":1306.84,\"liquidFlowRate\":null,\"massFlowRate\":null}]}]}");
        var normalized = mapper.readTree(PipesimNetworkInspectionValidator.validateAndSerialize(input, mapper));
        assertThat(normalized.path("studies").get(0).path("boundaries").get(0).path("node").asText()).isEqualTo("Supply_1");
        assertThat(normalized.path("studies").get(0).path("boundaries").get(0).path("gasFlowRate").asDouble()).isEqualTo(1306.84);
        assertThat(PipesimNetworkInspectionValidator.parsePersisted(normalized.toString())).isNotNull();
    }

    @Test void rejectsUnknownFieldsDuplicateNodesAndUnsafeNumbers() throws Exception {
        var base = "\"schemaVersion\":\"pipesim-network-inspection/1\",\"studies\":[{\"study\":\"Study 1\",\"boundaries\":[";
        var boundary = "{\"node\":\"Supply_1\",\"boundaryNodeType\":\"Source\",\"isActive\":true,\"isSurfaceCondition\":true,\"flowRateType\":null,\"pressure\":100,\"temperature\":131,\"gasFlowRate\":null,\"liquidFlowRate\":null,\"massFlowRate\":null}";
        assertThatThrownBy(() -> PipesimNetworkInspectionValidator.validateAndSerialize(mapper.readTree("{" + base + boundary + "," + boundary + "]}]}"), mapper)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PipesimNetworkInspectionValidator.validateAndSerialize(mapper.readTree("{" + base + boundary.replace("}", ",\"extra\":1}") + "]}]}"), mapper)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PipesimNetworkInspectionValidator.validateAndSerialize(mapper.readTree("{" + base + boundary.replace("\"pressure\":100", "\"pressure\":0") + "]}]}"), mapper)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void acceptsV2InspectionWithFrozenPackageManifest() throws Exception {
        var input = mapper.readTree("{\"schemaVersion\":\"pipesim-network-inspection/2\",\"studies\":[{\"study\":\"Study 1\",\"boundaries\":[{\"node\":\"Supply_1\",\"boundaryNodeType\":\"Source\",\"isActive\":true,\"isSurfaceCondition\":true,\"flowRateType\":null,\"pressure\":100,\"temperature\":131,\"gasFlowRate\":null,\"liquidFlowRate\":null,\"massFlowRate\":null}]}],\"packageFiles\":[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"" + "a".repeat(64) + "\"}]}" );
        var normalized = mapper.readTree(PipesimNetworkInspectionValidator.validateAndSerialize(input, mapper));
        assertThat(normalized.path("schemaVersion").asText()).isEqualTo("pipesim-network-inspection/2");
        assertThat(normalized.path("packageFiles").size()).isEqualTo(1);
    }
}
