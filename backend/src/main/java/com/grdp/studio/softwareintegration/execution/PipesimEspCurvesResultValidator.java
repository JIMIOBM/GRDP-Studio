package com.grdp.studio.softwareintegration.execution;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

@Component
public class PipesimEspCurvesResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "producer", "pump", "nodalPump");
    private static final Set<String> PUMP_FIELDS = Set.of("pumpName", "inputs", "frequencies", "operatingEnvelope");
    private static final Set<String> INPUT_FIELDS = Set.of(
            "frequency", "frequencyUnit", "manufacturer", "model", "minFlowRate", "maxFlowRate", "stages");
    private static final Set<String> FREQUENCY_FIELDS = Set.of(
            "frequencyHz", "frequencyLabel", "flowRate", "flowRateUnit", "head", "headUnit");
    private static final Set<String> CURVE_FIELDS = Set.of("flowRate", "flowRateUnit", "head", "headUnit");
    private static final Set<String> ENVELOPE_FIELDS = Set.of("qMin", "bep", "qMax");

    public PipesimWellResultValidator.ValidatedResult validate(JsonNode result) {
        requireObject(result, ROOT_FIELDS, "ESP curves result");
        requireText(result, "schemaVersion", "pipesim-esp-curves-result/1");
        require(result.path("model_kind").isTextual()
                        && ("black_oil_liquid".equals(result.path("model_kind").asText())
                        || "basic_gas".equals(result.path("model_kind").asText())),
                "Invalid model_kind");
        requireText(result, "runTask", "esp-curves");
        requireText(result, "resultContract", "VALID_FULL");
        requireSafeText(result, "producer");
        validatePump(result.path("pump"));
        validatePump(result.path("nodalPump"));
        return new PipesimWellResultValidator.ValidatedResult(
                SoftwareIntegrationRunStatus.SUCCEEDED, "VALID_FULL", result);
    }

    private static void validatePump(JsonNode pump) {
        requireObject(pump, PUMP_FIELDS, "ESP pump");
        requireText(pump, "pumpName", "B-ESP");
        JsonNode inputs = pump.path("inputs");
        requireObject(inputs, INPUT_FIELDS, "ESP inputs");
        requireNumber(inputs.path("frequency"), 0D, 200D, "frequency");
        requireSafeUnitText(inputs, "frequencyUnit");
        requireSafeText(inputs, "manufacturer");
        requireSafeText(inputs, "model");
        requireNumber(inputs.path("minFlowRate"), 0D, 1_000_000D, "minFlowRate");
        requireNumber(inputs.path("maxFlowRate"), 0D, 1_000_000D, "maxFlowRate");
        requireNumber(inputs.path("stages"), 0D, 100_000D, "stages");
        require(inputs.path("maxFlowRate").asDouble() > inputs.path("minFlowRate").asDouble(), "maxFlowRate must exceed minFlowRate");

        JsonNode frequencies = pump.path("frequencies");
        require(frequencies.isArray() && frequencies.size() >= 1 && frequencies.size() <= 64, "Invalid ESP frequencies");
        for (JsonNode frequency : frequencies) {
            requireObject(frequency, FREQUENCY_FIELDS, "ESP frequency");
            requireNumber(frequency.path("frequencyHz"), 0D, 200D, "frequencyHz");
            requireSafeText(frequency, "frequencyLabel");
            requireSafeUnitText(frequency, "flowRateUnit");
            requireSafeUnitText(frequency, "headUnit");
            validateCurveArrays(frequency, "flowRate", "head");
        }

        JsonNode envelope = pump.path("operatingEnvelope");
        requireObject(envelope, ENVELOPE_FIELDS, "ESP operating envelope");
        validateCurve(envelope.path("qMin"));
        validateCurve(envelope.path("bep"));
        validateCurve(envelope.path("qMax"));
    }

    private static void validateCurve(JsonNode curve) {
        requireObject(curve, CURVE_FIELDS, "ESP curve");
        requireSafeUnitText(curve, "flowRateUnit");
        requireSafeUnitText(curve, "headUnit");
        validateCurveArrays(curve, "flowRate", "head");
    }

    private static void validateCurveArrays(JsonNode node, String flowField, String headField) {
        JsonNode flow = node.path(flowField);
        JsonNode head = node.path(headField);
        require(flow.isArray() && head.isArray() && flow.size() >= 1 && flow.size() <= 512 && flow.size() == head.size(),
                "ESP curve arrays have an invalid shape");
        for (JsonNode value : flow) requireNumber(value, -1_000_000D, 100_000_000D, "ESP flow rate");
        for (JsonNode value : head) requireNumber(value, -1_000_000D, 100_000_000D, "ESP head");
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

    private static void requireSafeUnitText(JsonNode node, String field) {
        String value = node.path(field).isTextual() ? node.path(field).asText() : "";
        require(!value.isBlank() && value.length() <= 100 && value.indexOf('\u0000') < 0
                        && value.indexOf('\r') < 0 && value.indexOf('\n') < 0
                        && !value.contains("..")
                        && value.chars().allMatch(character -> Character.isLetterOrDigit(character)
                        || " %/().:_[]+-^*".indexOf(character) >= 0),
                field + " is invalid");
    }

    private static void requireNumber(JsonNode value, Double lowerInclusive, Double upperInclusive, String name) {
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue())
                        && (lowerInclusive == null || value.doubleValue() >= lowerInclusive)
                        && (upperInclusive == null || value.doubleValue() <= upperInclusive),
                name + " must be a finite number");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new PipesimWellResultValidator.ResultValidationException(message);
    }
}
