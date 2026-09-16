package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class PipesimWellInspectionValidator {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PipesimWellInspectionValidator() {}

    public static boolean supports(String modelKind) {
        return "black_oil_liquid".equals(modelKind) || "basic_gas".equals(modelKind);
    }

    public static JsonNode validate(JsonNode input) {
        if (input == null || !input.isObject() || input.size() != 2
                || !input.path("schemaVersion").isTextual()
                || !"pipesim-well-inspection/1".equals(input.path("schemaVersion").asText())
                || !input.has("reservoirPressure")) return null;
        JsonNode pressure = input.get("reservoirPressure");
        if (pressure.isNull()) return input;
        if (!pressure.isObject() || pressure.size() != 2
                || !pressure.path("unit").isTextual() || !"psia".equals(pressure.path("unit").asText())
                || !pressure.path("value").isNumber()) return null;
        double value = pressure.path("value").asDouble();
        if (!Double.isFinite(value) || value <= 0 || value == 1.2345e25 || value == -1e31) return null;
        return input;
    }

    public static String validateAndSerialize(JsonNode input, ObjectMapper mapper) {
        JsonNode validated = validate(input);
        return validated == null ? null : mapper.writeValueAsString(validated);
    }

    public static JsonNode parsePersisted(String value) {
        if (value == null || value.isBlank()) return null;
        try { return validate(MAPPER.readTree(value)); }
        catch (Exception exception) { return null; }
    }
}
