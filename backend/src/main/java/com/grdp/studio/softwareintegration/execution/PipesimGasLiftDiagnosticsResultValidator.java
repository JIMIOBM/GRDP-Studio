package com.grdp.studio.softwareintegration.execution;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

@Component
public class PipesimGasLiftDiagnosticsResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "producer",
            "outletPressurePsi", "surfaceInjectionTemperatureF", "targetInjectionRateMmscfd",
            "reservoirPressurePsi", "gorScfPerStb", "waterCutPercent", "diagnosticType",
            "throttling", "usePhaseRatio", "injectionUnit", "liquidRateUnit", "cases");
    private static final Set<String> CASE_FIELDS = Set.of("caseName", "injectionRateMmscfd", "liquidRateStbPerDay", "valves");
    private static final Set<String> VALVE_FIELDS = Set.of(
            "valveName", "positionStatus", "status", "gasRateNoThrottlingMmscfd", "portDiameterIn",
            "domeTemperatureF", "closingPressurePsi", "openingPressurePsi", "ptroPsi",
            "dischargeCoefficient", "portToBellowArea", "operationMode", "portType");

    public PipesimWellResultValidator.ValidatedResult validate(JsonNode result) {
        requireObject(result, ROOT_FIELDS, "gas lift diagnostics result");
        requireText(result, "schemaVersion", "pipesim-gas-lift-diagnostics-result/1");
        requireText(result, "model_kind", "black_oil_liquid");
        requireText(result, "runTask", "gas-lift-diagnostics");
        requireText(result, "resultContract", "VALID_FULL");
        requireSafeText(result, "producer");
        requireNumber(result.path("outletPressurePsi"), 0, 100000, "outletPressurePsi");
        requireNumber(result.path("surfaceInjectionTemperatureF"), -1000, 100000, "surfaceInjectionTemperatureF");
        requireNumber(result.path("targetInjectionRateMmscfd"), 0, 100000, "targetInjectionRateMmscfd");
        requireNumber(result.path("reservoirPressurePsi"), 0, 100000, "reservoirPressurePsi");
        requireNumber(result.path("gorScfPerStb"), 0, 1000000, "gorScfPerStb");
        requireNumber(result.path("waterCutPercent"), 0, 100, "waterCutPercent");
        requireText(result, "diagnosticType", "FIXEDINJECTION");
        requireText(result, "throttling", "ON");
        require(result.path("usePhaseRatio").isBoolean() && result.path("usePhaseRatio").booleanValue(),
                "usePhaseRatio must be true");
        requireText(result, "injectionUnit", "mmscf/d");
        requireText(result, "liquidRateUnit", "STB/d");

        JsonNode cases = result.path("cases");
        require(cases.isArray() && cases.size() >= 1 && cases.size() <= 32, "cases has an invalid shape");
        double previous = Double.NEGATIVE_INFINITY;
        int expectedValveCount = -1;
        for (JsonNode item : cases) {
            requireObject(item, CASE_FIELDS, "gas lift diagnostics case");
            requireSafeCaseName(item, "caseName");
            requireNumber(item.path("injectionRateMmscfd"), 0, 100000, "injectionRateMmscfd");
            require(item.path("injectionRateMmscfd").doubleValue() > previous,
                    "Gas lift diagnostic injection rates must be strictly increasing");
            previous = item.path("injectionRateMmscfd").doubleValue();
            requireNumber(item.path("liquidRateStbPerDay"), null, null, "liquidRateStbPerDay");
            JsonNode valves = item.path("valves");
            require(valves.isArray() && valves.size() >= 1 && valves.size() <= 64, "valves has an invalid shape");
            if (expectedValveCount < 0) expectedValveCount = valves.size();
            require(expectedValveCount == valves.size(), "Valve count must be stable across cases");
            for (JsonNode valve : valves) validateValve(valve);
        }
        return new PipesimWellResultValidator.ValidatedResult(
                SoftwareIntegrationRunStatus.SUCCEEDED, "VALID_FULL", result);
    }

    private static void validateValve(JsonNode valve) {
        requireObject(valve, VALVE_FIELDS, "gas lift valve");
        requireSafeText(valve, "valveName");
        requireTextValue(valve.path("positionStatus"), "positionStatus");
        requireOptionalText(valve.path("status"), "status");
        requireOptionalNumber(valve.path("gasRateNoThrottlingMmscfd"), "gasRateNoThrottlingMmscfd");
        requireOptionalNumber(valve.path("portDiameterIn"), "portDiameterIn");
        requireOptionalNumber(valve.path("domeTemperatureF"), "domeTemperatureF");
        requireOptionalNumber(valve.path("closingPressurePsi"), "closingPressurePsi");
        requireOptionalNumber(valve.path("openingPressurePsi"), "openingPressurePsi");
        requireOptionalNumber(valve.path("ptroPsi"), "ptroPsi");
        requireOptionalNumber(valve.path("dischargeCoefficient"), "dischargeCoefficient");
        requireOptionalNumber(valve.path("portToBellowArea"), "portToBellowArea");
        requireOptionalText(valve.path("operationMode"), "operationMode");
        requireOptionalText(valve.path("portType"), "portType");
    }

    private static void requireObject(JsonNode node, Set<String> fields, String name) {
        require(node != null && node.isObject() && node.size() == fields.size(), name + " has an invalid shape");
        for (String field : fields) require(node.has(field), name + " is missing " + field);
    }

    private static void requireText(JsonNode node, String field, String expected) {
        require(node.path(field).isTextual() && expected.equals(node.path(field).asText()), "Invalid " + field);
    }

    private static void requireSafeText(JsonNode node, String field) {
        requireTextValue(node.path(field), field);
    }

    private static void requireTextValue(JsonNode value, String field) {
        String text = value != null && value.isTextual() ? value.asText() : "";
        require(!text.isBlank() && text.length() <= 1000 && text.indexOf('\u0000') < 0
                        && text.indexOf('\r') < 0 && text.indexOf('\n') < 0 && !text.contains(".."),
                field + " is invalid");
    }

    private static void requireOptionalText(JsonNode value, String field) {
        require(value != null && (value.isNull() || (value.isTextual() && !value.asText().isBlank()
                        && value.asText().length() <= 1000 && value.asText().indexOf('\u0000') < 0
                        && value.asText().indexOf('\r') < 0 && value.asText().indexOf('\n') < 0)),
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
                        && value.doubleValue() >= lowerInclusive && value.doubleValue() <= upperInclusive,
                name + " must be a finite number");
    }

    private static void requireNumber(JsonNode value, Double lowerInclusive, Double upperInclusive, String name) {
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue())
                        && (lowerInclusive == null || value.doubleValue() >= lowerInclusive)
                        && (upperInclusive == null || value.doubleValue() <= upperInclusive),
                name + " must be a finite number");
    }

    private static void requireOptionalNumber(JsonNode value, String name) {
        require(value != null && (value.isNull() || (value.isNumber() && Double.isFinite(value.doubleValue()))),
                name + " must be null or a finite number");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new PipesimWellResultValidator.ResultValidationException(message);
    }
}
