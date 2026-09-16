package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

public final class WellScenarioParameters {
    private WellScenarioParameters() {}

    public static boolean valid(JsonNode value, String modelKind, String runType) {
        if (value == null || value.isNull()) return true;
        if (!"nodal".equals(runType) || !("basic_gas".equals(modelKind) || "black_oil_liquid".equals(modelKind))) return false;
        if (!value.isObject() || value.size() != 2 || !value.has("schemaVersion") || !value.has("reservoirPressurePsi")) return false;
        JsonNode pressure = value.get("reservoirPressurePsi");
        return "pipesim-well-parameters/1".equals(value.path("schemaVersion").asText())
                && pressure.isNumber() && Double.isFinite(pressure.asDouble())
                && pressure.asDouble() > 0 && pressure.asDouble() <= 100000;
    }
}
