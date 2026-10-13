package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

public final class EclipseThermalMetadataValidator {
    private static final Set<String> FIELDS = Set.of("solver", "modelType", "componentCount", "componentNames", "fluidModel", "summaryProgramIdentifier");
    private EclipseThermalMetadataValidator() {}
    public static boolean valid(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != FIELDS.size()) return false;
        for (var f : value.properties()) if (!FIELDS.contains(f.getKey())) return false;
        if (!value.path("solver").isIntegralNumber() || !value.path("solver").canConvertToInt() || value.path("solver").intValue() != 300 ||
                !value.path("summaryProgramIdentifier").isIntegralNumber() || !value.path("summaryProgramIdentifier").canConvertToInt() || value.path("summaryProgramIdentifier").intValue() != 500 ||
                !"THERMAL".equals(value.path("modelType").asText()) || !"LIVEOIL".equals(value.path("fluidModel").asText()) ||
                !value.path("componentCount").isIntegralNumber() || !value.path("componentCount").canConvertToInt()) return false;
        int count = value.path("componentCount").intValue();
        var names = value.path("componentNames");
        if (count < 1 || count > 100 || !names.isArray() || names.size() != count) return false;
        var unique = new HashSet<String>();
        for (var n : names) {
            if (!n.isTextual() || n.asText().length() < 1 || n.asText().length() > 8 || !unique.add(n.asText())) return false;
            for (char c : n.asText().toCharArray()) if (c < 32 || c > 126 || c == '/' || c == '\\') return false;
        }
        return true;
    }
}
