package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EclipseDataInspectionValidatorTests {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsEveryFrozenUnitSystemIncludingPvtM() {
        for (String unitSystem : List.of("METRIC", "FIELD", "LAB", "PVT-M")) {
            ObjectNode inspection = inspection();
            inspection.put("unitSystem", unitSystem);
            assertThat(validate(inspection)).contains("\"unitSystem\":\"" + unitSystem + "\"");
        }
    }

    @Test
    void rejectsUnknownUnitSystem() {
        ObjectNode inspection = inspection();
        inspection.put("unitSystem", "SI");
        assertInvalid(inspection);
    }

    @Test
    void acceptsMaxDimensionBoundInclusively() {
        ObjectNode inspection = inspection();
        inspection.set("dimensions", objectMapper.readTree("{\"nx\":1000000,\"ny\":1000000,\"nz\":1000000}"));
        assertThat(validate(inspection)).contains("\"nx\":1000000", "\"ny\":1000000", "\"nz\":1000000");
    }

    @Test
    void rejectsDimensionAboveBoundOrNonPositive() {
        ObjectNode above = inspection();
        above.set("dimensions", objectMapper.readTree("{\"nx\":1000001,\"ny\":1,\"nz\":1}"));
        assertInvalid(above);
        ObjectNode zero = inspection();
        zero.set("dimensions", objectMapper.readTree("{\"nx\":0,\"ny\":1,\"nz\":1}"));
        assertInvalid(zero);
    }

    @Test
    void acceptsEmptySections() {
        ObjectNode inspection = inspection();
        inspection.set("sections", objectMapper.createArrayNode());
        assertThat(validate(inspection)).contains("\"sections\":[]");
    }

    @Test
    void acceptsBasenameCaseNameWithoutLeadingAsciiOrDataSuffixRequirement() {
        for (String caseName : List.of("CASE.DATA", "_CASE.DATA", "-CASE.DATA", "MODEL", "模型.DATA")) {
            ObjectNode inspection = inspection();
            inspection.put("caseName", caseName);
            assertThat(validate(inspection)).contains("\"caseName\":\"" + caseName + "\"");
        }
    }

    @Test
    void rejectsCaseNameWithPathSeparators() {
        for (String caseName : List.of("C:\\private\\CASE.DATA", "/tmp/CASE.DATA", "nested/CASE.DATA")) {
            ObjectNode inspection = inspection();
            inspection.put("caseName", caseName);
            assertInvalid(inspection);
        }
    }

    @Test
    void rejectsEmptyOrOversizedCaseName() {
        ObjectNode empty = inspection();
        empty.put("caseName", "");
        assertInvalid(empty);
        ObjectNode oversized = inspection();
        oversized.put("caseName", "A".repeat(256));
        assertInvalid(oversized);
        ObjectNode boundary = inspection();
        boundary.put("caseName", "A".repeat(255));
        assertThat(validate(boundary)).isNotNull();
    }

    private ObjectNode inspection() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schemaVersion", "eclipse-data-inspection/1");
        node.put("caseName", "CASE.DATA");
        node.set("sections", objectMapper.createArrayNode());
        node.putNull("unitSystem");
        node.set("phases", objectMapper.createArrayNode());
        node.putNull("dimensions");
        return node;
    }

    private String validate(JsonNode value) {
        return EclipseDataInspectionValidator.validateAndSerialize(value, objectMapper);
    }

    private void assertInvalid(JsonNode value) {
        assertThatThrownBy(() -> validate(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
