package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

import java.util.Set;

/** Strict first-release contract for the official PIPESIM VFP Tables workflow. */
public final class VfpTablesParameters {
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "producer", "reservoirSimulator", "tableNumber", "includeTemperature",
            "bottomHoleDatumDepth", "liquidRatesStbPerDay", "outletPressuresPsi", "waterCutFraction",
            "gorMscfPerStb", "artificialLiftInjectionDpPsi");

    private VfpTablesParameters() {}

    public static boolean valid(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != FIELDS.size()
                || !FIELDS.stream().allMatch(value::has)
                || !"pipesim-vfp-tables-parameters/1".equals(value.path("schemaVersion").asText())
                || !safeName(value.path("producer").asText())
                || !"ECLIPSE".equals(value.path("reservoirSimulator").asText())
                || !integer(value.path("tableNumber"), 1, 100000)
                || !value.path("includeTemperature").isBoolean()
                || !number(value.path("bottomHoleDatumDepth"), 0, 100000)) return false;
        return increasing(value.path("liquidRatesStbPerDay"), 1, 16, 0, 1000000)
                && increasing(value.path("outletPressuresPsi"), 1, 16, 0, 1000000)
                && increasing(value.path("waterCutFraction"), 1, 16, 0, 1)
                && increasing(value.path("gorMscfPerStb"), 1, 16, 0, 1000000)
                && increasing(value.path("artificialLiftInjectionDpPsi"), 1, 16, 0, 1000000);
    }

    public static boolean matchesInspection(JsonNode value, JsonNode inspection) {
        return valid(value) && inspection != null && inspection.path("well").isTextual()
                && value.path("producer").asText().equals(inspection.path("well").asText());
    }

    private static boolean increasing(JsonNode value, int minSize, int maxSize, double lowerInclusive, double upperInclusive) {
        if (value == null || !value.isArray() || value.size() < minSize || value.size() > maxSize) return false;
        double previous = Double.NEGATIVE_INFINITY;
        for (JsonNode item : value) {
            if (!number(item, lowerInclusive, upperInclusive) || item.asDouble() <= previous) return false;
            previous = item.asDouble();
        }
        return true;
    }

    private static boolean integer(JsonNode value, int lowerInclusive, int upperInclusive) {
        return value != null && value.isIntegralNumber() && value.asInt() >= lowerInclusive && value.asInt() <= upperInclusive;
    }

    private static boolean number(JsonNode value, double lowerInclusive, double upperInclusive) {
        return value != null && value.isNumber() && Double.isFinite(value.asDouble())
                && value.asDouble() >= lowerInclusive && value.asDouble() <= upperInclusive;
    }

    private static boolean safeName(String value) {
        return value != null && !value.isBlank() && value.length() <= 255
                && value.indexOf('\u0000') < 0 && value.indexOf('/') < 0
                && value.indexOf('\\') < 0 && !value.contains("..");
    }
}
