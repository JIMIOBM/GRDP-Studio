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
        if (input != null && input.isObject() && "pipesim-well-inspection/9".equals(input.path("schemaVersion").asText())) {
            // /9 is /8 plus a required scoped discovery list. Reuse the frozen
            // /8 contract rather than relaxing historical field validation.
            var legacy = (tools.jackson.databind.node.ObjectNode) input.deepCopy();
            legacy.remove("gasLiftCapabilities");
            legacy.put("schemaVersion", "pipesim-well-inspection/8");
            return validate(legacy) != null && GasLiftCapabilityValidator.valid(input.path("gasLiftCapabilities"), input.path("wellContexts"))
                    ? input : null;
        }
        if (input == null || !input.isObject() || !input.path("schemaVersion").isTextual()
                || !Set.of("pipesim-well-inspection/1", "pipesim-well-inspection/2", "pipesim-well-inspection/3", "pipesim-well-inspection/4", "pipesim-well-inspection/5", "pipesim-well-inspection/6", "pipesim-well-inspection/7", "pipesim-well-inspection/8").contains(input.path("schemaVersion").asText())
                || !input.has("reservoirPressure")) return null;
        boolean v2 = "pipesim-well-inspection/2".equals(input.path("schemaVersion").asText());
        boolean v3 = "pipesim-well-inspection/3".equals(input.path("schemaVersion").asText());
        boolean v4 = "pipesim-well-inspection/4".equals(input.path("schemaVersion").asText());
        boolean v5 = "pipesim-well-inspection/5".equals(input.path("schemaVersion").asText());
        boolean v6 = "pipesim-well-inspection/6".equals(input.path("schemaVersion").asText());
        boolean v7 = "pipesim-well-inspection/7".equals(input.path("schemaVersion").asText());
        boolean v8 = "pipesim-well-inspection/8".equals(input.path("schemaVersion").asText());
        Set<String> fields = v7 || v8
                ? Set.of("schemaVersion", "reservoirPressure", "packageFiles", "well", "espContexts", "wellContexts", "networkCapability")
                : v5
                ? Set.of("schemaVersion", "reservoirPressure", "packageFiles", "well", "espContexts", "wellContexts")
                : v6
                ? Set.of("schemaVersion", "reservoirPressure", "packageFiles", "well", "espContexts", "wellContexts")
                : v4
                ? Set.of("schemaVersion", "reservoirPressure", "packageFiles", "well", "espContexts")
                : v3
                ? Set.of("schemaVersion", "reservoirPressure", "packageFiles", "well")
                : v2 ? Set.of("schemaVersion", "reservoirPressure", "packageFiles") : Set.of("schemaVersion", "reservoirPressure");
        // /8 adds tubing details independently of optional mixed-model Network capability.
        int expectedFields = v8 && !input.has("networkCapability") ? 6
                : v7 || v8 ? 7 : v5 || v6 ? 6 : v4 ? 5 : v3 ? 4 : v2 ? 3 : 2;
        if (input.size() != expectedFields || !hasOnly(input, fields)) return null;
        JsonNode packageFiles = input.get("packageFiles");
        if ((v2 || v3 || v4 || v5 || v6 || v7 || v8) && packageFiles == null) return null;
        if ((v3 || v4) && (!input.path("well").isTextual() || input.path("well").asText().isBlank())) return null;
        if ((v4 || v5 || v6 || v7 || v8) && !validEspContexts(input.path("espContexts"))) return null;
        if (v5 && !validWellContexts(input.path("wellContexts"), input.path("espContexts"), input.get("well"))) return null;
        if ((v6 || v7) && !validWellContexts(input.path("wellContexts"), input.path("espContexts"), input.get("well"), true, false)) return null;
        if (v8 && !validWellContexts(input.path("wellContexts"), input.path("espContexts"), input.get("well"), true, true)) return null;
        if ((v7 || v8 && input.has("networkCapability") && !input.path("networkCapability").isNull())
                && !validNetworkCapability(input.path("networkCapability"))) return null;
        JsonNode pressure = input.get("reservoirPressure");
        if (v2 || v3 || v4 || v5 || v6 || v7 || v8) {
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

    private static boolean validNetworkCapability(JsonNode capability) {
        if (!capability.isObject() || capability.size() != 3
                || !hasOnly(capability, Set.of("schemaVersion", "studies", "networkInspection"))
                || !"pipesim-network-capability/1".equals(capability.path("schemaVersion").asText())) return false;
        JsonNode studies = capability.path("studies");
        if (!studies.isArray() || studies.isEmpty() || studies.size() > 64) return false;
        Set<String> names = new java.util.HashSet<>();
        for (JsonNode study : studies) {
            if (!study.isTextual() || !validContext(study.asText()) || !names.add(study.asText())) return false;
        }
        JsonNode networkInspection = PipesimNetworkInspectionValidator.validateNode(capability.get("networkInspection"));
        if (networkInspection == null || !Set.of("pipesim-network-inspection/3", "pipesim-network-inspection/4", "pipesim-network-inspection/5")
                .contains(networkInspection.path("schemaVersion").asText())) return false;
        Set<String> inspectedStudies = new java.util.HashSet<>();
        for (JsonNode study : networkInspection.path("studies")) inspectedStudies.add(study.path("study").asText());
        return names.equals(inspectedStudies);
    }

    public static JsonNode networkInspection(JsonNode validatedWellInspection) {
        if (validatedWellInspection == null || !Set.of("pipesim-well-inspection/7", "pipesim-well-inspection/8", "pipesim-well-inspection/9")
                .contains(validatedWellInspection.path("schemaVersion").asText())) return null;
        return PipesimNetworkInspectionValidator.validateNode(validatedWellInspection.path("networkCapability").path("networkInspection"));
    }

    private static boolean hasOnly(JsonNode input, Set<String> fields) {
        for (String name : input.propertyNames()) if (!fields.contains(name)) return false;
        return true;
    }

    private static boolean validEspContexts(JsonNode contexts) {
        if (!contexts.isArray() || contexts.size() > 128) return false;
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode context : contexts) {
            if (!context.isTextual()) return false;
            String value = context.asText();
            if (value.isBlank() || value.length() > 255 || value.contains("..")
                    || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                    || value.chars().anyMatch(Character::isISOControl)
                    || !seen.add(value)) return false;
        }
        return true;
    }

    private static boolean validWellContexts(JsonNode wells, JsonNode espContexts, JsonNode selectedWell) {
        return validWellContexts(wells, espContexts, selectedWell, false);
    }

    private static boolean validWellContexts(JsonNode wells, JsonNode espContexts, JsonNode selectedWell, boolean requireFlowObjects) {
        return validWellContexts(wells, espContexts, selectedWell, requireFlowObjects, false);
    }

    private static boolean validWellContexts(JsonNode wells, JsonNode espContexts, JsonNode selectedWell,
                                             boolean requireFlowObjects, boolean requireTubingDetails) {
        if (!wells.isArray() || wells.size() == 0 || wells.size() > 128 || selectedWell == null) return false;
        Set<String> seenWells = new java.util.HashSet<>();
        Set<String> scopedEspContexts = new java.util.HashSet<>();
        String onlyWell = null;
        for (JsonNode well : wells) {
            if (!well.isObject() || well.size() != (requireTubingDetails ? 5 : requireFlowObjects ? 4 : 2) || !well.path("context").isTextual()
                    || !validContext(well.path("context").asText()) || !well.path("espContexts").isArray()
                    || well.path("espContexts").size() > 128) return false;
            if (requireFlowObjects && (!validScopedObjects(well.path("completionContexts")) || !validScopedObjects(well.path("tubingContexts")))) return false;
            if (requireTubingDetails && !validTubingComponents(well.path("tubingComponents"), well.path("tubingContexts"))) return false;
            String context = well.path("context").asText();
            if (!seenWells.add(context)) return false;
            onlyWell = context;
            Set<String> localEspContexts = new java.util.HashSet<>();
            for (JsonNode esp : well.path("espContexts")) {
                if (!esp.isTextual() || !validContext(esp.asText()) || !localEspContexts.add(esp.asText())
                        || !scopedEspContexts.add(esp.asText())) return false;
            }
        }
        if (wells.size() == 1) {
            if (!selectedWell.isTextual() || !onlyWell.equals(selectedWell.asText())) return false;
        } else if (!selectedWell.isNull()) return false;
        Set<String> flattenedEspContexts = new java.util.HashSet<>();
        for (JsonNode esp : espContexts) flattenedEspContexts.add(esp.asText());
        return scopedEspContexts.equals(flattenedEspContexts);
    }

    private static boolean validScopedObjects(JsonNode contexts) {
        if (!contexts.isArray() || contexts.size() > 128) return false;
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode context : contexts) if (!context.isTextual() || !validContext(context.asText()) || !seen.add(context.asText())) return false;
        return true;
    }

    private static boolean validTubingComponents(JsonNode components, JsonNode contexts) {
        if (!components.isArray() || components.size() > 128 || !contexts.isArray()) return false;
        Set<String> names = new java.util.HashSet<>();
        Set<String> inspectedContexts = new java.util.HashSet<>();
        for (JsonNode context : contexts) if (context.isTextual()) inspectedContexts.add(context.asText());
        for (JsonNode component : components) {
            if (!component.isObject() || component.size() != 3 || !component.path("context").isTextual()
                    || !component.path("innerDiameter").isNumber() || !component.path("unit").isTextual()) return false;
            String context = component.path("context").asText();
            double diameter = component.path("innerDiameter").asDouble();
            String unit = component.path("unit").asText();
            if (!validContext(context) || !names.add(context) || !inspectedContexts.contains(context)
                    || !Double.isFinite(diameter) || diameter <= 0 || diameter > 1000
                    || unit.isBlank() || unit.length() > 32 || unit.chars().anyMatch(Character::isISOControl)) return false;
        }
        return true;
    }

    private static boolean validContext(String value) {
        return !value.isBlank() && value.length() <= 255 && !value.contains("..")
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0
                && value.chars().noneMatch(Character::isISOControl);
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
