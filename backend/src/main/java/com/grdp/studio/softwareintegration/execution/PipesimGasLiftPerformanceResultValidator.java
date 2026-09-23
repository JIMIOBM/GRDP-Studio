package com.grdp.studio.softwareintegration.execution;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

@Component
public class PipesimGasLiftPerformanceResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "producer",
            "outletPressurePsi", "surfaceInjectionTemperatureF", "targetInjectionRateMmscfd",
            "reservoirPressurePsi", "gorScfPerStb", "waterCutPercent", "scanVariable", "scanUnit",
            "productionUnit", "cases");

    public PipesimWellResultValidator.ValidatedResult validate(JsonNode result) {
        requireObject(result, ROOT_FIELDS, "gas lift performance result");
        requireText(result, "schemaVersion", "pipesim-gas-lift-performance-result/1");
        requireText(result, "model_kind", "black_oil_liquid");
        requireText(result, "runTask", "gas-lift-performance");
        requireText(result, "resultContract", "VALID_FULL");
        requireSafeText(result, "producer");
        requireNumber(result.path("outletPressurePsi"), 0, 100000, "outletPressurePsi");
        requireNumber(result.path("surfaceInjectionTemperatureF"), -1000, 100000, "surfaceInjectionTemperatureF");
        requireNumber(result.path("targetInjectionRateMmscfd"), 0, 100000, "targetInjectionRateMmscfd");
        requireNumber(result.path("reservoirPressurePsi"), 0, 100000, "reservoirPressurePsi");
        requireNumber(result.path("gorScfPerStb"), 0, 1000000, "gorScfPerStb");
        requireNumber(result.path("waterCutPercent"), 0, 100, "waterCutPercent");
        requireText(result, "scanVariable", "gasLiftInjectionRate");
        requireText(result, "scanUnit", "mmscf/d");
        requireText(result, "productionUnit", "STB/d");

        JsonNode cases = result.path("cases");
        require(cases.isArray() && cases.size() >= 2 && cases.size() <= 16, "cases has an invalid shape");
        double previous = Double.NEGATIVE_INFINITY;
        for (JsonNode item : cases) {
            requireObject(item, Set.of("caseName", "injectionRateMmscfd", "liquidRateStbPerDay"), "gas lift case");
            requireSafeCaseName(item, "caseName");
            requireNumber(item.path("injectionRateMmscfd"), 0, 100000, "injectionRateMmscfd");
            require(item.path("injectionRateMmscfd").doubleValue() > previous,
                    "Gas lift injection rates must be strictly increasing");
            previous = item.path("injectionRateMmscfd").doubleValue();
            requireNumber(item.path("liquidRateStbPerDay"), null, null, "liquidRateStbPerDay");
        }
        return new PipesimWellResultValidator.ValidatedResult(
                SoftwareIntegrationRunStatus.SUCCEEDED, "VALID_FULL", result);
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

    private static void requireSafeCaseName(JsonNode node, String field) {
        String value = node.path(field).isTextual() ? node.path(field).asText() : "";
        require(!value.isBlank() && value.length() <= 1000 && value.indexOf('\u0000') < 0
                        && value.indexOf('\r') < 0 && value.indexOf('\n') < 0 && !value.contains(".."),
                field + " is invalid");
    }

    private static void requireNumber(JsonNode value, double lowerInclusive, double upperInclusive, String name) {
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue())
                        && (Double.isInfinite(lowerInclusive) || value.doubleValue() >= lowerInclusive)
                        && (Double.isInfinite(upperInclusive) || value.doubleValue() <= upperInclusive),
                name + " must be a finite number");
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
