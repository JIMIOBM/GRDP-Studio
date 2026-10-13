package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

/** Strict input contract for the first PIPESIM Well Performance Curves slice. */
public final class NetworkWellPerformanceParameters {
    private NetworkWellPerformanceParameters() {}

    public static boolean valid(JsonNode value, JsonNode inspection) {
        if (value == null || !value.isObject() || value.size() != 5
                || !"pipesim-network-well-performance-parameters/1".equals(value.path("schemaVersion").asText())
                || !validContext(value.path("producerContext"))
                || !validContext(value.path("branchTerminator"))
                || !"in".equals(value.path("sensitivityUnit").asText())
                || !validValues(value.path("sensitivityValues"))) return false;
        JsonNode normalizedInspection = PipesimNetworkInspectionValidator.validateNode(inspection);
        if (normalizedInspection == null || !Set.of("pipesim-network-inspection/4", "pipesim-network-inspection/5").contains(normalizedInspection.path("schemaVersion").asText())) return false;
        return contains(normalizedInspection.path("wellContexts"), value.path("producerContext").asText())
                && contains(normalizedInspection.path("flowlineContexts"), value.path("branchTerminator").asText());
    }

    private static boolean validValues(JsonNode values) {
        if (!values.isArray() || values.size() < 2 || values.size() > 8) return false;
        double previous = Double.NEGATIVE_INFINITY;
        for (JsonNode item : values) {
            if (!item.isNumber() || !Double.isFinite(item.asDouble()) || item.asDouble() <= 0
                    || item.asDouble() > 1000 || item.asDouble() <= previous) return false;
            previous = item.asDouble();
        }
        return true;
    }

    private static boolean validContext(JsonNode value) {
        if (value == null || !value.isTextual()) return false;
        String context = value.asText();
        return !context.isBlank() && context.length() <= 255 && !context.contains("..")
                && context.indexOf('/') < 0 && context.indexOf('\\') < 0
                && context.chars().noneMatch(Character::isISOControl);
    }

    private static boolean contains(JsonNode values, String expected) {
        if (!values.isArray()) return false;
        Set<String> names = new HashSet<>();
        for (JsonNode value : values) if (value.isTextual()) names.add(value.asText());
        return names.contains(expected);
    }
}
