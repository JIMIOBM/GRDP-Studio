package com.grdp.studio.softwareintegration.execution;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

@Component
public class PipesimWellTrajectoryResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "producer", "units", "points");
    private static final Set<String> UNIT_FIELDS = Set.of(
            "measuredDepth", "trueVerticalDepth", "inclination", "azimuth", "maxDogLegSeverity");
    private static final Set<String> POINT_FIELDS = Set.of(
            "measuredDepth", "trueVerticalDepth", "inclination", "azimuth", "maxDogLegSeverity");

    public PipesimWellResultValidator.ValidatedResult validate(JsonNode result) {
        requireObject(result, ROOT_FIELDS, "trajectory result");
        requireText(result, "schemaVersion", "pipesim-well-trajectory-result/1");
        require(result.path("model_kind").isTextual()
                        && Set.of("black_oil_liquid", "basic_gas", "legacy_well").contains(result.path("model_kind").asText()),
                "Invalid model_kind");
        requireText(result, "runTask", "trajectory");
        requireText(result, "resultContract", "VALID_FULL");
        requireSafeText(result, "producer");
        JsonNode units = result.path("units");
        requireObject(units, UNIT_FIELDS, "trajectory units");
        for (String field : UNIT_FIELDS) requireSafeUnit(units, field);

        JsonNode points = result.path("points");
        require(points.isArray() && points.size() >= 2 && points.size() <= 4096, "Invalid trajectory point count");
        double previousDepth = -1D;
        for (JsonNode point : points) {
            requireObject(point, POINT_FIELDS, "trajectory point");
            requireNumber(point.path("measuredDepth"), 0D, 1_000_000D, "measuredDepth");
            requireNumber(point.path("trueVerticalDepth"), 0D, 1_000_000D, "trueVerticalDepth");
            requireNumber(point.path("inclination"), 0D, 180D, "inclination");
            requireNullableNumber(point.path("azimuth"), -360D, 360D, "azimuth");
            requireNullableNumber(point.path("maxDogLegSeverity"), -1_000_000D, 1_000_000D, "maxDogLegSeverity");
            require(point.path("measuredDepth").asDouble() > previousDepth, "Trajectory measured depths must increase");
            previousDepth = point.path("measuredDepth").asDouble();
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

    private static void requireSafeUnit(JsonNode node, String field) {
        String value = node.path(field).isTextual() ? node.path(field).asText() : "";
        require(!value.isBlank() && value.length() <= 1000 && value.indexOf('\u0000') < 0
                        && value.indexOf('\r') < 0 && value.indexOf('\n') < 0
                        && !value.contains("..") && value.indexOf('\\') < 0
                        && !value.contains("://") && !value.startsWith("/"),
                field + " is invalid");
    }

    private static void requireNumber(JsonNode value, double lowerInclusive, double upperInclusive, String name) {
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue())
                        && value.doubleValue() >= lowerInclusive && value.doubleValue() <= upperInclusive,
                name + " must be a finite number");
    }

    private static void requireNullableNumber(JsonNode value, double lowerInclusive, double upperInclusive, String name) {
        if (value == null || value.isNull()) return;
        requireNumber(value, lowerInclusive, upperInclusive, name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new PipesimWellResultValidator.ResultValidationException(message);
    }
}
