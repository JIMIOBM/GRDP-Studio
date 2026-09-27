package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

public final class SystemAnalysisParameters {
    private SystemAnalysisParameters() {}

    public static boolean valid(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 6
                || !"pipesim-system-analysis-parameters/1".equals(value.path("schemaVersion").asText())
                || !"Well".equals(value.path("producer").asText())
                || !"liquidFlowRate".equals(value.path("scanVariable").asText())
                || !value.path("branchTerminator").isTextual()
                || !safeName(value.path("branchTerminator").asText())
                || !number(value, "outletPressurePsi", 0, 100000)
                || !value.path("values").isArray()
                || value.path("values").size() < 2 || value.path("values").size() > 8) return false;
        double previous = Double.NEGATIVE_INFINITY;
        for (JsonNode item : value.path("values")) {
            if (!item.isNumber() || !Double.isFinite(item.asDouble()) || item.asDouble() <= 0
                    || item.asDouble() <= previous || item.asDouble() > 1000000000) return false;
            previous = item.asDouble();
        }
        return true;
    }

    private static boolean number(JsonNode object, String field, double lowerExclusive, double upperInclusive) {
        JsonNode value = object.get(field);
        return value != null && value.isNumber() && Double.isFinite(value.asDouble())
                && value.asDouble() > lowerExclusive && value.asDouble() <= upperInclusive;
    }

    private static boolean safeName(String value) {
        return value != null && !value.isBlank() && value.length() <= 255
                && value.indexOf('\u0000') < 0 && value.indexOf('/') < 0
                && value.indexOf('\\') < 0 && !value.contains("..");
    }
}
