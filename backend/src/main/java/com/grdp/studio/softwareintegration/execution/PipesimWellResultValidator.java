package com.grdp.studio.softwareintegration.execution;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

@Component
public class PipesimWellResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "units", "ipr", "vlp", "profile");
    private static final Set<String> UNIT_FIELDS = Set.of("flow", "pressure", "depth", "temperature");

    public ValidatedResult validate(String expectedRunTask, JsonNode result) {
        requireObject(result, ROOT_FIELDS, "result");
        requireText(result, "schemaVersion", "pipesim-well-result/1");
        String modelKind = requireEnum(result, "model_kind", Set.of("black_oil_liquid", "basic_gas"));
        requireText(result, "runTask", expectedRunTask);
        String contract = requireEnum(result, "resultContract", Set.of(
                "VALID_FULL", "VALID_PARTIAL", "INVALID_EMPTY_NODAL", "INVALID_EMPTY_PROFILE"));
        validateUnits(result.path("units"), modelKind);
        int ipr = validatePoints(result.path("ipr"), false, "ipr");
        int vlp = validatePoints(result.path("vlp"), false, "vlp");
        int profile = validatePoints(result.path("profile"), true, "profile");

        SoftwareIntegrationRunStatus terminal;
        switch (expectedRunTask) {
            case "nodal" -> {
                require(contract.equals("VALID_FULL") && ipr > 0 && vlp > 0,
                        "Nodal result contract is invalid");
                terminal = SoftwareIntegrationRunStatus.SUCCEEDED;
            }
            case "profile" -> {
                require(contract.equals("VALID_FULL") && profile > 0,
                        "Profile result contract is invalid");
                terminal = SoftwareIntegrationRunStatus.SUCCEEDED;
            }
            case "combined" -> {
                if (contract.equals("VALID_FULL")) {
                    require(ipr > 0 && vlp > 0 && profile > 0, "Combined full result is invalid");
                    terminal = SoftwareIntegrationRunStatus.SUCCEEDED;
                } else {
                    require(contract.equals("VALID_PARTIAL") && ipr > 0 && vlp > 0 && profile == 0,
                            "Combined partial result is invalid");
                    terminal = SoftwareIntegrationRunStatus.PARTIAL_SUCCEEDED;
                }
            }
            default -> throw new ResultValidationException("Unsupported runTask");
        }
        return new ValidatedResult(terminal, contract, result);
    }

    private void validateUnits(JsonNode units, String modelKind) {
        requireObject(units, UNIT_FIELDS, "units");
        if (modelKind.equals("basic_gas")) {
            validateUnit(units.path("flow"), "mmscf/d", "standard_gas_volume_rate");
        } else {
            validateUnit(units.path("flow"), null, "unspecified");
        }
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

    private int validatePoints(JsonNode array, boolean profile, String field) {
        require(array != null && array.isArray(), field + " must be an array");
        Set<String> fields = profile ? Set.of("depth", "pressure", "temperature") : Set.of("flow", "pressure");
        for (JsonNode point : array) {
            requireObject(point, fields, field + " point");
            for (String name : fields) {
                JsonNode value = point.get(name);
                require(value != null && value.isNumber() && Double.isFinite(value.doubleValue()), field + " contains a non-finite number");
                if (name.equals("depth")) require(value.doubleValue() >= 0, "Profile depth must be non-negative");
            }
        }
        return array.size();
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
        if (!condition) throw new ResultValidationException(message);
    }

    public record ValidatedResult(SoftwareIntegrationRunStatus terminalStatus, String contract, JsonNode result) {}

    public static class ResultValidationException extends RuntimeException {
        public ResultValidationException(String message) { super(message); }
    }
}
