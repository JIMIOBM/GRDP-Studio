package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

public final class NetworkScenarioParameters {
    private static final Set<String> BOUNDARY_FIELDS = Set.of(
            "node", "pressure", "temperature", "flowRateType", "gasFlowRate", "liquidFlowRate", "massFlowRate");

    private NetworkScenarioParameters() {}

    public static boolean valid(JsonNode value) {
        if (value == null || value.isNull()) return true;
        if (!value.isObject() || value.size() != 2
                || !"pipesim-network-parameters/1".equals(value.path("schemaVersion").asText())
                || !value.path("boundaries").isArray()
                || value.path("boundaries").size() < 1 || value.path("boundaries").size() > 64) return false;
        Set<String> nodes = new HashSet<>();
        for (JsonNode boundary : value.path("boundaries")) {
            if (!boundary.isObject() || boundary.propertyNames().isEmpty()) return false;
            for (String field : boundary.propertyNames()) if (!BOUNDARY_FIELDS.contains(field)) return false;
            String node = boundary.path("node").isTextual() ? boundary.path("node").asText() : null;
            if (node == null || node.isBlank() || node.length() > 255 || node.contains("..")
                    || node.indexOf('/') >= 0 || node.indexOf('\\') >= 0 || !nodes.add(node)) return false;
            if (!number(boundary, "pressure", 0, 100000)
                    || !number(boundary, "temperature", -1000, 100000)
                    || !number(boundary, "gasFlowRate", 0, 1000000000)
                    || !number(boundary, "liquidFlowRate", 0, 1000000000)
                    || !number(boundary, "massFlowRate", 0, 1000000000)) return false;
            if (boundary.has("flowRateType")) {
                String type = boundary.path("flowRateType").isTextual() ? boundary.path("flowRateType").asText() : null;
                String rateField = switch (type == null ? "" : type) {
                    case "GasFlowRate" -> "gasFlowRate";
                    case "LiquidFlowRate" -> "liquidFlowRate";
                    case "MassFlowRate" -> "massFlowRate";
                    default -> null;
                };
                if (rateField == null || !boundary.has(rateField)) return false;
            }
        }
        return true;
    }

    private static boolean number(JsonNode object, String field, double lowerExclusive, double upperInclusive) {
        if (!object.has(field)) return true;
        JsonNode value = object.get(field);
        double number = value.isNumber() ? value.asDouble() : Double.NaN;
        return Double.isFinite(number) && number > lowerExclusive && number <= upperInclusive;
    }
}
