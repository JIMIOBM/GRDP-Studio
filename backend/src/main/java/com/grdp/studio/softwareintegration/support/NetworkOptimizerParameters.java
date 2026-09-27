package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

public final class NetworkOptimizerParameters {
    private NetworkOptimizerParameters() {}

    public static boolean valid(JsonNode value) {
        return value != null && value.isObject() && value.size() == 2
                && ("pipesim-network-optimizer-parameters/1".equals(value.path("schemaVersion").asText())
                    && value.path("applyResults").isBoolean() && !value.path("applyResults").asBoolean()
                    || "pipesim-network-optimizer-parameters/2".equals(value.path("schemaVersion").asText())
                    && value.path("applyResults").isBoolean() && value.path("applyResults").asBoolean())
                ;
    }
}
