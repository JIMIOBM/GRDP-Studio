package com.grdp.studio.softwareintegration.execution;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

@Component
public class PipesimSensitivityResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "targetVariable", "units", "cases");
    private static final Set<String> UNIT_FIELDS = Set.of("flow", "pressure", "depth", "temperature");

    public PipesimWellResultValidator.ValidatedResult validate(String expectedRunTask, JsonNode result) {
        requireObject(result, ROOT_FIELDS, "sensitivity result");
        requireText(result, "schemaVersion", "pipesim-well-sensitivity-result/1");
        String modelKind = requireEnum(result, "model_kind", Set.of("black_oil_liquid", "basic_gas"));
        requireText(result, "runTask", expectedRunTask);
        requireText(result, "resultContract", "VALID_FULL");
        String target = requireEnum(result, "targetVariable",
                Set.of("reservoirPressure", "waterCut", "gor", "tubingInnerDiameter"));
        if (modelKind.equals("basic_gas")) require(target.equals("reservoirPressure") || target.equals("tubingInnerDiameter"),
                "Unsupported sensitivity target for basic gas");
        validateUnits(result.path("units"), modelKind);
        JsonNode cases = result.path("cases");
        require(cases.isArray() && cases.size() >= 2 && cases.size() <= 12, "cases has an invalid shape");
        double previous = Double.NEGATIVE_INFINITY;
        for (JsonNode item : cases) {
            requireObject(item, Set.of("value", "ipr", "vlp"), "sensitivity case");
            JsonNode value = item.get("value");
            require(value != null && value.isNumber() && Double.isFinite(value.doubleValue()) && value.doubleValue() > previous,
                    "Sensitivity values must be finite and strictly increasing");
            previous = value.doubleValue();
            requirePoints(item.path("ipr"), "ipr");
            requirePoints(item.path("vlp"), "vlp");
        }
        return new PipesimWellResultValidator.ValidatedResult(
                SoftwareIntegrationRunStatus.SUCCEEDED, "VALID_FULL", result);
    }

    private void validateUnits(JsonNode units, String modelKind) {
        requireObject(units, UNIT_FIELDS, "units");
        if (modelKind.equals("basic_gas")) validateUnit(units.path("flow"), "mmscf/d", "standard_gas_volume_rate");
        else validateUnit(units.path("flow"), null, "unspecified");
        validateUnit(units.path("pressure"), null, "unspecified");
        validateUnit(units.path("depth"), null, "unspecified");
        validateUnit(units.path("temperature"), null, "unspecified");
    }

    private void validateUnit(JsonNode unit, String displayUnit, String semantics) {
        requireObject(unit, Set.of("displayUnit", "semantics"), "unit");
        JsonNode display = unit.get("displayUnit");
        if (displayUnit == null) require(display != null && display.isNull(), "Unit displayUnit must be null");
        else require(display != null && display.isTextual() && displayUnit.equals(display.asText()), "Unexpected display unit");
        requireText(unit, "semantics", semantics);
    }

    private void requirePoints(JsonNode points, String name) {
        require(points.isArray() && !points.isEmpty(), name + " must contain points");
        for (JsonNode point : points) {
            requireObject(point, Set.of("flow", "pressure"), name + " point");
            require(point.path("flow").isNumber() && Double.isFinite(point.path("flow").doubleValue()), name + " flow is invalid");
            require(point.path("pressure").isNumber() && Double.isFinite(point.path("pressure").doubleValue()), name + " pressure is invalid");
        }
    }

    private static void requireObject(JsonNode node, Set<String> fields, String name) {
        require(node != null && node.isObject() && node.size() == fields.size(), name + " has an invalid shape");
        for (String field : fields) require(node.has(field), name + " is missing " + field);
    }

    private static String requireEnum(JsonNode node, String field, Set<String> values) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual() && values.contains(value.asText()), "Invalid " + field);
        return value.asText();
    }

    private static void requireText(JsonNode node, String field, String expected) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual() && expected.equals(value.asText()), "Invalid " + field);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new PipesimWellResultValidator.ResultValidationException(message);
    }
}
