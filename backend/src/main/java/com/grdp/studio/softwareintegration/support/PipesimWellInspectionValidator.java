package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

public final class PipesimWellInspectionValidator {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PipesimWellInspectionValidator() {}

    public static boolean supports(String modelKind) {
        return "black_oil_liquid".equals(modelKind) || "basic_gas".equals(modelKind) || "legacy_well".equals(modelKind);
    }

    public static JsonNode validate(JsonNode input) {
        if (input == null || !input.isObject() || !input.path("schemaVersion").isTextual()
                || !Set.of("pipesim-well-inspection/1", "pipesim-well-inspection/2", "pipesim-well-inspection/3").contains(input.path("schemaVersion").asText())
                || !input.has("reservoirPressure")) return null;
        boolean v2 = "pipesim-well-inspection/2".equals(input.path("schemaVersion").asText());
        boolean v3 = "pipesim-well-inspection/3".equals(input.path("schemaVersion").asText());
        Set<String> fields = v3
                ? Set.of("schemaVersion", "reservoirPressure", "packageFiles", "well")
                : v2 ? Set.of("schemaVersion", "reservoirPressure", "packageFiles") : Set.of("schemaVersion", "reservoirPressure");
        if (input.size() != (v3 ? 4 : v2 ? 3 : 2) || !hasOnly(input, fields)) return null;
        JsonNode packageFiles = input.get("packageFiles");
        if ((v2 || v3) && packageFiles == null) return null;
        if (v3 && (!input.path("well").isTextual() || input.path("well").asText().isBlank())) return null;
        JsonNode pressure = input.get("reservoirPressure");
        if (v2 || v3) {
            try { PipesimPackageManifestValidator.validate(packageFiles, MAPPER); }
            catch (IllegalArgumentException exception) { return null; }
        }
        if (pressure.isNull()) return input;
        if (!pressure.isObject() || pressure.size() != 2
                || !pressure.path("unit").isTextual() || !"psia".equals(pressure.path("unit").asText())
                || !pressure.path("value").isNumber()) return null;
        double value = pressure.path("value").asDouble();
        if (!Double.isFinite(value) || value <= 0 || value == 1.2345e25 || value == -1e31) return null;
        return input;
    }

    private static boolean hasOnly(JsonNode input, Set<String> fields) {
        for (String name : input.propertyNames()) if (!fields.contains(name)) return false;
        return true;
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
