package com.grdp.studio.softwareintegration.execution;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

@Component
public class PipesimEspParameterResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "producer", "espContext",
            "parameter", "unit", "originalValue", "targetValue", "appliedValue", "sourceModelUnchanged",
            "isolatedCopy", "baseline", "target", "comparison");
    private static final Set<String> COMPARISON_FIELDS = Set.of(
            "maxIprPressureDelta", "maxVlpPressureDelta", "maxProfilePressureDelta", "maxProfileTemperatureDelta");

    // Contract-only validation remains available for historical results without a request.
    public PipesimWellResultValidator.ValidatedResult validate(JsonNode result, JsonNode parameters) {
        PipesimWellResultValidator.ValidatedResult validated = validate(result);
        require(parameters != null && parameters.isObject(), "ESP parameter request must be an object");
        String schema = parameters.path("schemaVersion").asText();
        boolean frequencyV1 = "pipesim-esp-parameter-parameters/1".equals(schema);
        boolean headFactorV2 = "pipesim-esp-parameter-parameters/2".equals(schema);
        boolean selectedWellV3 = "pipesim-esp-parameter-parameters/3".equals(schema);
        require(frequencyV1 || headFactorV2 || selectedWellV3, "Invalid ESP parameter request schema");
        String targetField = frequencyV1 ? "targetValueHz" : "targetValue";
        requireObject(parameters, selectedWellV3
                        ? Set.of("schemaVersion", "wellContext", "espContext", "parameter", targetField)
                        : Set.of("schemaVersion", "espContext", "parameter", targetField),
                "ESP parameter request");
        String parameter = parameters.path("parameter").asText();
        require(parameters.path("parameter").isTextual()
                        && (frequencyV1 ? "operatingFrequency".equals(parameter)
                        : headFactorV2 ? "headFactor".equals(parameter)
                        : Set.of("operatingFrequency", "headFactor").contains(parameter)),
                "Invalid ESP request parameter");
        requireRequestContext(parameters, "espContext");
        requireText(result, "espContext", parameters.path("espContext").asText());
        if (selectedWellV3) {
            requireRequestContext(parameters, "wellContext");
            // The result contract reports the selected well as producer, not wellContext.
            require(parameters.path("wellContext").asText().equals(result.path("producer").asText()),
                    "ESP result producer does not match request wellContext");
        }
        requireText(result, "parameter", parameter);
        JsonNode target = parameters.path(targetField);
        requireNumber(target, 0D, "headFactor".equals(parameter) ? 2D : 200D, targetField);
        require(target.asDouble() > 0, targetField + " must be positive");
        // Only readback has a tolerance. A different requested scenario target is never equivalent.
        require(target.decimalValue().compareTo(result.path("targetValue").decimalValue()) == 0,
                "ESP result targetValue does not match request " + targetField);
        return validated;
    }

    public PipesimWellResultValidator.ValidatedResult validate(JsonNode result) {
        requireObject(result, ROOT_FIELDS, "ESP parameter result");
        String schema = result.path("schemaVersion").asText();
        String parameter = result.path("parameter").asText();
        boolean frequencyV1 = "pipesim-esp-parameter-result/1".equals(schema) && "operatingFrequency".equals(parameter);
        boolean frequencyV2 = "pipesim-esp-parameter-result/2".equals(schema) && "operatingFrequency".equals(parameter);
        boolean headFactorV2 = "pipesim-esp-parameter-result/2".equals(schema) && "headFactor".equals(parameter);
        require(frequencyV1 || frequencyV2 || headFactorV2, "Invalid ESP parameter result schema");
        require(result.path("model_kind").isTextual()
                        && Set.of("black_oil_liquid", "basic_gas", "legacy_well").contains(result.path("model_kind").asText()),
                "Invalid model_kind");
        requireText(result, "runTask", "esp-parameter");
        requireText(result, "resultContract", "VALID_FULL");
        requireSafeText(result, "producer");
        requireSafeText(result, "espContext");
        String unit = frequencyV1 || frequencyV2 ? "Hz" : "1";
        String caseParameterField = frequencyV1 ? "frequencyHz" : "parameterValue";
        double maximum = headFactorV2 ? 2D : 200D;
        requireText(result, "unit", unit);
        requireNumber(result.path("originalValue"), 0D, Double.MAX_VALUE, "originalValue");
        requireNumber(result.path("targetValue"), 0D, maximum, "targetValue");
        requireNumber(result.path("appliedValue"), 0D, maximum, "appliedValue");
        require(result.path("originalValue").asDouble() > 0 && result.path("targetValue").asDouble() > 0
                        && result.path("appliedValue").asDouble() > 0, "ESP parameter values must be positive");
        require(result.path("sourceModelUnchanged").isBoolean() && result.path("sourceModelUnchanged").asBoolean(),
                "sourceModelUnchanged must be true");
        require(result.path("isolatedCopy").isBoolean() && result.path("isolatedCopy").asBoolean(),
                "isolatedCopy must be true");
        require(Math.abs(result.path("targetValue").asDouble() - result.path("appliedValue").asDouble()) <= 1e-9,
                "Applied ESP value does not match target");
        require(Math.abs(result.path("originalValue").asDouble() - result.path("targetValue").asDouble()) > 1e-9,
                "ESP parameter must change");
        validateCase(result.path("baseline"), "baseline", caseParameterField, headFactorV2 ? Double.MAX_VALUE : maximum);
        validateCase(result.path("target"), "target", caseParameterField, maximum);
        validateComparison(result.path("comparison"));
        require(Math.abs(result.path("baseline").path(caseParameterField).asDouble() - result.path("originalValue").asDouble()) <= 1e-6,
                "Baseline ESP parameter does not match original value");
        require(Math.abs(result.path("target").path(caseParameterField).asDouble() - result.path("appliedValue").asDouble()) <= 1e-6,
                "Target ESP parameter does not match applied value");
        return new PipesimWellResultValidator.ValidatedResult(
                SoftwareIntegrationRunStatus.SUCCEEDED, "VALID_FULL", result);
    }

    private static void validateCase(JsonNode value, String name, String parameterField, double maximum) {
        Set<String> fields = Set.of(parameterField, "ipr", "vlp", "profile");
        requireObject(value, fields, name);
        requireNumber(value.path(parameterField), 0D, maximum, name + "." + parameterField);
        require(value.path(parameterField).asDouble() > 0, name + " parameter must be positive");
        validateCurve(value.path("ipr"), name + ".ipr");
        validateCurve(value.path("vlp"), name + ".vlp");
        JsonNode profile = value.path("profile");
        require(profile.isArray() && profile.size() >= 1 && profile.size() <= 4096, name + ".profile is invalid");
        for (JsonNode point : profile) {
            requireObject(point, Set.of("depth", "pressure", "temperature"), name + ".profile point");
            requireNumber(point.path("depth"), 0D, 1_000_000D, "profile.depth");
            requireNumber(point.path("pressure"), -1_000_000D, 100_000_000D, "profile.pressure");
            requireNumber(point.path("temperature"), -1_000_000D, 100_000_000D, "profile.temperature");
        }
    }

    private static void validateCurve(JsonNode value, String name) {
        require(value.isArray() && value.size() >= 1 && value.size() <= 4096, name + " is invalid");
        for (JsonNode point : value) {
            requireObject(point, Set.of("flow", "pressure"), name + " point");
            requireNumber(point.path("flow"), -1_000_000D, 100_000_000D, name + ".flow");
            requireNumber(point.path("pressure"), -1_000_000D, 100_000_000D, name + ".pressure");
        }
    }

    private static void validateComparison(JsonNode value) {
        requireObject(value, COMPARISON_FIELDS, "comparison");
        for (String field : COMPARISON_FIELDS) {
            JsonNode delta = value.path(field);
            if (!delta.isNull()) requireNumber(delta, 0D, 100_000_000D, field);
        }
    }

    private static void requireObject(JsonNode node, Set<String> fields, String name) {
        require(node != null && node.isObject() && node.size() == fields.size(), name + " has an invalid shape");
        for (String field : fields) require(node.has(field), name + " is missing " + field);
    }

    private static void requireText(JsonNode node, String field, String expected) {
        require(node.path(field).isTextual() && expected.equals(node.path(field).asText()), "Invalid " + field);
    }

    private static void requireSafeText(JsonNode node, String field) {
        String value = node.path(field).isTextual() ? node.path(field).asText() : "";
        require(!value.isBlank() && value.length() <= 1000 && value.indexOf('\u0000') < 0
                        && value.indexOf('\r') < 0 && value.indexOf('\n') < 0
                        && !value.contains("..") && value.indexOf('/') < 0 && value.indexOf('\\') < 0,
                field + " is invalid");
    }

    private static void requireRequestContext(JsonNode node, String field) {
        requireSafeText(node, field);
        String value = node.path(field).asText();
        require(value.length() <= 255 && value.chars().noneMatch(Character::isISOControl)
                        && !"AUTO".equalsIgnoreCase(value),
                "Invalid ESP request " + field);
    }

    private static void requireNumber(JsonNode value, Double lowerInclusive, Double upperInclusive, String name) {
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue())
                        && value.doubleValue() >= lowerInclusive && value.doubleValue() <= upperInclusive,
                name + " must be a finite number");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new PipesimWellResultValidator.ResultValidationException(message);
    }
}
