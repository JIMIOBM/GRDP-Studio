package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

import java.util.Set;

/** Strict first-release contract for the official PIPESIM Gas Lift Performance workflow. */
public final class GasLiftPerformanceParameters {
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "producer", "outletPressurePsi", "surfaceInjectionTemperatureF",
            "targetInjectionRateMmscfd", "reservoirPressurePsi", "gorScfPerStb", "waterCutPercent", "valuesMmscfd");

    private GasLiftPerformanceParameters() {}

    public static boolean valid(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != FIELDS.size()
                || !FIELDS.stream().allMatch(value::has)
                || !"pipesim-gas-lift-performance-parameters/1".equals(value.path("schemaVersion").asText())
                || !safeName(value.path("producer").asText())
                || !number(value, "outletPressurePsi", 0, 100000)
                || !number(value, "surfaceInjectionTemperatureF", -1000, 100000)
                || !number(value, "targetInjectionRateMmscfd", 0, 100000)
                || !number(value, "reservoirPressurePsi", 0, 100000)
                || !number(value, "gorScfPerStb", 0, 1000000)
                || !number(value, "waterCutPercent", 0, 100)) return false;
        JsonNode values = value.path("valuesMmscfd");
        if (!values.isArray() || values.size() < 2 || values.size() > 16) return false;
        double previous = Double.NEGATIVE_INFINITY;
        for (JsonNode item : values) {
            if (!item.isNumber() || !Double.isFinite(item.asDouble()) || item.asDouble() < 0
                    || item.asDouble() <= previous || item.asDouble() > 100000) return false;
            previous = item.asDouble();
        }
        return true;
    }

    public static boolean matchesInspection(JsonNode value, JsonNode inspection) {
        return valid(value) && inspection != null && inspection.path("well").isTextual()
                && value.path("producer").asText().equals(inspection.path("well").asText());
    }

    private static boolean number(JsonNode object, String field, double lowerInclusive, double upperInclusive) {
        JsonNode value = object.get(field);
        return value != null && value.isNumber() && Double.isFinite(value.asDouble())
                && value.asDouble() >= lowerInclusive && value.asDouble() <= upperInclusive;
    }

    private static boolean safeName(String value) {
        return value != null && !value.isBlank() && value.length() <= 255
                && value.indexOf('\u0000') < 0 && value.indexOf('/') < 0
                && value.indexOf('\\') < 0 && !value.contains("..");
    }
}
