package com.grdp.studio.softwareintegration.execution;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public class PipesimWellTrajectoryResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "producer", "units", "points");
    private static final Set<String> EDITED_ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "producer", "units", "points", "application", "profileComparison");
    private static final Set<String> UNIT_FIELDS = Set.of(
            "measuredDepth", "trueVerticalDepth", "inclination", "azimuth", "maxDogLegSeverity");
    private static final Set<String> POINT_FIELDS = Set.of(
            "measuredDepth", "trueVerticalDepth", "inclination", "azimuth", "maxDogLegSeverity");
    private static final Set<String> APPLICATION_FIELDS = Set.of(
            "applied", "isolatedCopy", "sourceModelUnchanged", "artifactName", "stationIndex",
            "originalInclinationDeg", "targetInclinationDeg", "maxTvdBeforeFt", "maxTvdAfterFt");
    private static final Set<String> COMPARISON_FIELDS = Set.of(
            "method", "baselinePointCount", "targetPointCount", "matchedPointCount", "baseline", "target",
            "matchedPoints", "maxAbsPressureDelta", "maxAbsTemperatureDelta");

    public PipesimWellResultValidator.ValidatedResult validate(JsonNode result) {
        require(result != null && result.isObject(), "trajectory result must be an object");
        String schema = result.path("schemaVersion").asText();
        boolean edited = "pipesim-well-trajectory-result/2".equals(schema);
        require("pipesim-well-trajectory-result/1".equals(schema) || edited, "Invalid trajectory result schema");
        requireObject(result, edited ? EDITED_ROOT_FIELDS : ROOT_FIELDS, "trajectory result");
        requireText(result, "schemaVersion", schema);
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
        if (edited) {
            validateApplication(result.path("application"), points);
            validateProfileComparison(result.path("profileComparison"));
        }
        return new PipesimWellResultValidator.ValidatedResult(
                SoftwareIntegrationRunStatus.SUCCEEDED, "VALID_FULL", result);
    }

    private static void validateApplication(JsonNode application, JsonNode points) {
        requireObject(application, APPLICATION_FIELDS, "trajectory application");
        require(application.path("applied").isBoolean() && application.path("applied").asBoolean()
                        && application.path("isolatedCopy").isBoolean() && application.path("isolatedCopy").asBoolean()
                        && application.path("sourceModelUnchanged").isBoolean() && application.path("sourceModelUnchanged").asBoolean(),
                "Trajectory application must confirm isolated save and unchanged source");
        require(application.path("artifactName").isTextual()
                        && "pipesim-trajectory-applied.pips".equals(application.path("artifactName").asText()),
                "Invalid trajectory artifact name");
        require(application.path("stationIndex").isIntegralNumber()
                        && application.path("stationIndex").asInt() >= 1 && application.path("stationIndex").asInt() <= 4094
                        && application.path("stationIndex").asInt() < points.size() - 1,
                "Invalid edited station index");
        requireNumber(application.path("originalInclinationDeg"), 0, 90, "originalInclinationDeg");
        requireNumber(application.path("targetInclinationDeg"), 0, 90, "targetInclinationDeg");
        require(Math.abs(application.path("originalInclinationDeg").asDouble()
                - application.path("targetInclinationDeg").asDouble()) > 1e-9, "Trajectory edit did not change inclination");
        requireNumber(application.path("maxTvdBeforeFt"), 0, 1_000_000, "maxTvdBeforeFt");
        requireNumber(application.path("maxTvdAfterFt"), 0, 1_000_000, "maxTvdAfterFt");
        require(Math.abs(points.get(application.path("stationIndex").asInt()).path("inclination").asDouble()
                        - application.path("targetInclinationDeg").asDouble()) <= 1e-6
                        && Math.abs(points.get(points.size() - 1).path("trueVerticalDepth").asDouble()
                        - application.path("maxTvdAfterFt").asDouble()) <= 1e-6,
                "Trajectory application does not match the returned points");
    }

    private static void validateProfileComparison(JsonNode comparison) {
        requireObject(comparison, COMPARISON_FIELDS, "trajectory profile comparison");
        requireText(comparison, "method", "exact-depth-match-no-interpolation");
        JsonNode baseline = comparison.path("baseline");
        JsonNode target = comparison.path("target");
        JsonNode matched = comparison.path("matchedPoints");
        require(baseline.isArray() && baseline.size() >= 1 && baseline.size() <= 4096, "Invalid baseline profile curve");
        require(target.isArray() && target.size() >= 1 && target.size() <= 4096, "Invalid target profile curve");
        require(matched.isArray() && matched.size() <= 4096, "Invalid matched profile points");
        require(comparison.path("baselinePointCount").isIntegralNumber()
                        && comparison.path("baselinePointCount").asInt() == baseline.size(), "Invalid baseline profile point count");
        require(comparison.path("targetPointCount").isIntegralNumber()
                        && comparison.path("targetPointCount").asInt() == target.size(), "Invalid target profile point count");
        require(comparison.path("matchedPointCount").isIntegralNumber()
                        && comparison.path("matchedPointCount").asInt() == matched.size(), "Invalid matched profile point count");

        Map<Long, JsonNode> baselineByDepth = profileMap(baseline, "baseline profile");
        Map<Long, JsonNode> targetByDepth = profileMap(target, "target profile");
        double previousDepth = Double.NEGATIVE_INFINITY;
        double maxPressureDelta = 0;
        double maxTemperatureDelta = 0;
        int expectedCount = 0;
        for (Long key : baselineByDepth.keySet()) if (targetByDepth.containsKey(key)) expectedCount++;
        require(expectedCount == matched.size(), "Matched profile points do not match the exact-depth intersection");
        for (JsonNode point : matched) {
            Set<String> fields = Set.of("depth", "baselinePressure", "targetPressure", "pressureDelta",
                    "baselineTemperature", "targetTemperature", "temperatureDelta");
            requireObject(point, fields, "matched profile point");
            requireNumber(point.path("depth"), -1_000_000, 1_000_000, "matched depth");
            double depth = point.path("depth").asDouble();
            require(depth > previousDepth, "Matched profile depths must be unique and increasing");
            previousDepth = depth;
            JsonNode first = baselineByDepth.get(Math.round(depth * 1_000_000));
            JsonNode second = targetByDepth.get(Math.round(depth * 1_000_000));
            require(first != null && second != null, "Matched point is not present in both exact-depth curves");
            requireMatchedValue(point, "baselinePressure", first.path("pressure"));
            requireMatchedValue(point, "targetPressure", second.path("pressure"));
            requireMatchedValue(point, "baselineTemperature", first.path("temperature"));
            requireMatchedValue(point, "targetTemperature", second.path("temperature"));
            double pressureDelta = second.path("pressure").asDouble() - first.path("pressure").asDouble();
            double temperatureDelta = second.path("temperature").asDouble() - first.path("temperature").asDouble();
            requireNumber(point.path("pressureDelta"), -100_000_000, 100_000_000, "pressureDelta");
            requireNumber(point.path("temperatureDelta"), -200_000, 200_000, "temperatureDelta");
            require(Math.abs(point.path("pressureDelta").asDouble() - pressureDelta) <= 1e-6
                            && Math.abs(point.path("temperatureDelta").asDouble() - temperatureDelta) <= 1e-6,
                    "Invalid matched profile delta");
            maxPressureDelta = Math.max(maxPressureDelta, Math.abs(pressureDelta));
            maxTemperatureDelta = Math.max(maxTemperatureDelta, Math.abs(temperatureDelta));
        }
        validateMaxDelta(comparison.path("maxAbsPressureDelta"), maxPressureDelta, matched.size(), "maxAbsPressureDelta");
        validateMaxDelta(comparison.path("maxAbsTemperatureDelta"), maxTemperatureDelta, matched.size(), "maxAbsTemperatureDelta");
    }

    private static Map<Long, JsonNode> profileMap(JsonNode curve, String name) {
        Map<Long, JsonNode> values = new HashMap<>();
        Set<Long> ambiguousDepths = new HashSet<>();
        for (JsonNode point : curve) {
            Set<String> fields = Set.of("depth", "pressure", "temperature");
            requireObject(point, fields, name + " point");
            requireNumber(point.path("depth"), -1_000_000, 1_000_000, name + " depth");
            requireNumber(point.path("pressure"), -1_000_000, 100_000_000, name + " pressure");
            requireNumber(point.path("temperature"), -100_000, 100_000, name + " temperature");
            long key = Math.round(point.path("depth").asDouble() * 1_000_000);
            if (values.putIfAbsent(key, point) != null) ambiguousDepths.add(key);
        }
        ambiguousDepths.forEach(values::remove);
        return values;
    }

    private static void requireMatchedValue(JsonNode matched, String field, JsonNode source) {
        requireNumber(matched.path(field), -1_000_000, 100_000_000, field);
        require(Math.abs(matched.path(field).asDouble() - source.asDouble()) <= 1e-6, "Matched point does not equal its source curve");
    }

    private static void validateMaxDelta(JsonNode value, double expected, int matchedCount, String name) {
        if (matchedCount == 0) {
            require(value != null && value.isNull(), name + " must be null when there are no matched points");
            return;
        }
        requireNumber(value, 0, 100_000_000, name);
        require(Math.abs(value.asDouble() - expected) <= 1e-6, "Invalid " + name);
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
