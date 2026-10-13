package com.grdp.studio.softwareintegration.execution;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

@Component
public class PipesimWellResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "units", "ipr", "vlp", "profile");
    private static final Set<String> TUBING_EDIT_ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "units", "ipr", "vlp", "profile", "application");
    private static final Set<String> UNIT_FIELDS = Set.of("flow", "pressure", "depth", "temperature");
    private static final Set<String> TUBING_APPLICATION_FIELDS = Set.of(
            "applied", "isolatedCopy", "sourceModelUnchanged", "artifactName", "wellContext", "tubingContext", "unit",
            "originalInnerDiameter", "targetInnerDiameter", "appliedInnerDiameter");

    public ValidatedResult validate(String expectedRunTask, JsonNode result) {
        return validate(expectedRunTask, result, null);
    }

    public ValidatedResult validate(String expectedRunTask, JsonNode result, JsonNode parameters) {
        boolean tubingEdit = result != null && "pipesim-well-result/2".equals(result.path("schemaVersion").asText());
        requireObject(result, tubingEdit ? TUBING_EDIT_ROOT_FIELDS : ROOT_FIELDS, "result");
        requireText(result, "schemaVersion", tubingEdit ? "pipesim-well-result/2" : "pipesim-well-result/1");
        if (tubingEdit) validateTubingApplication(result.path("application"), expectedRunTask, parameters);
        else require(parameters == null || parameters.isNull()
                        || !"pipesim-well-tubing-diameter-parameters/1".equals(parameters.path("schemaVersion").asText()),
                "Tubing edit did not return its applied-parameter result contract");
        String modelKind = requireEnum(result, "model_kind", Set.of("black_oil_liquid", "basic_gas", "legacy_well"));
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

    private void validateTubingApplication(JsonNode application, String expectedRunTask, JsonNode parameters) {
        require("profile".equals(expectedRunTask), "Tubing edit result is only valid for Profile");
        require(parameters != null && parameters.isObject() &&
                        "pipesim-well-tubing-diameter-parameters/1".equals(parameters.path("schemaVersion").asText()) &&
                        parameters.size() == 7,
                "Tubing edit result has no matching parameter request");
        requireObject(application, TUBING_APPLICATION_FIELDS, "Tubing application");
        require(application.path("applied").isBoolean() && application.path("applied").asBoolean()
                        && application.path("isolatedCopy").isBoolean() && application.path("isolatedCopy").asBoolean()
                        && application.path("sourceModelUnchanged").isBoolean() && application.path("sourceModelUnchanged").asBoolean(),
                "Tubing application must confirm isolated persistence and unchanged source");
        requireText(application, "artifactName", "pipesim-well-tubing-applied.pips");
        require(safeText(application.path("wellContext")) && safeText(application.path("tubingContext"))
                        && safeText(application.path("unit")), "Tubing application contains unsafe context or unit text");
        require(application.path("wellContext").asText().equals(parameters.path("wellContext").asText())
                        && application.path("tubingContext").asText().equals(parameters.path("tubingContext").asText())
                        && application.path("unit").asText().equals(parameters.path("unit").asText()),
                "Tubing application does not match the requested inspected component");
        double original = requireNumber(application.path("originalInnerDiameter"), "originalInnerDiameter");
        double target = requireNumber(application.path("targetInnerDiameter"), "targetInnerDiameter");
        double applied = requireNumber(application.path("appliedInnerDiameter"), "appliedInnerDiameter");
        require(close(original, parameters.path("originalInnerDiameter").asDouble())
                        && close(target, parameters.path("targetInnerDiameter").asDouble())
                        && close(applied, target)
                        && !close(original, target),
                "Tubing application readback does not match the requested diameter change");
    }

    private static double requireNumber(JsonNode value, String name) {
        require(value != null && value.isNumber() && Double.isFinite(value.asDouble())
                        && value.asDouble() > 0 && value.asDouble() <= 1000,
                "Invalid Tubing " + name);
        return value.asDouble();
    }

    private static boolean safeText(JsonNode value) {
        if (value == null || !value.isTextual()) return false;
        String text = value.asText();
        return !text.isBlank() && text.length() <= 255 && !text.contains("..")
                && text.indexOf('/') < 0 && text.indexOf('\\') < 0
                && text.chars().noneMatch(Character::isISOControl);
    }

    private static boolean close(double first, double second) {
        return Math.abs(first - second) <= Math.max(1e-9, Math.max(Math.abs(first), Math.abs(second)) * 1e-9);
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
