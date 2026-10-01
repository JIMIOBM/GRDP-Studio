package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the immutable, relative-path manifest attached to a PIPESIM model version. */
final class PipesimPackageManifestValidator {
    private static final Set<String> FILE_FIELDS = Set.of("relativePath", "sizeBytes", "sha256");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private PipesimPackageManifestValidator() {}

    static ArrayNode validate(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > 4096) fail();
        Set<String> paths = new HashSet<>();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode file : value) {
            if (file == null || !file.isObject() || file.size() != FILE_FIELDS.size() || !hasOnly(file, FILE_FIELDS)) fail();
            String relativePath = text(file, "relativePath");
            JsonNode size = file.get("sizeBytes");
            String sha256 = text(file, "sha256");
            if (!validRelativePath(relativePath) || !paths.add(relativePath)
                    || size == null || !size.isIntegralNumber() || !size.canConvertToLong()
                    || size.longValue() < 0 || size.longValue() > 512L * 1024 * 1024
                    || !SHA256.matcher(sha256).matches()) fail();
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put("relativePath", relativePath);
            normalized.put("sizeBytes", size.longValue());
            normalized.put("sha256", sha256);
            result.add(normalized);
        }
        return result;
    }

    private static boolean hasOnly(JsonNode value, Set<String> fields) {
        for (String name : value.propertyNames()) if (!fields.contains(name)) return false;
        return true;
    }

    private static String text(JsonNode value, String field) {
        JsonNode node = value.get(field);
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private static boolean validRelativePath(String path) {
        if (path.isBlank() || path.length() > 512 || path.startsWith("/") || path.contains("\\") ||
                path.contains("//") || path.contains(":") || path.contains("..")) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment) ||
                    segment.chars().anyMatch(Character::isISOControl)) return false;
        }
        return true;
    }

    private static void fail() { throw new IllegalArgumentException("Invalid PIPESIM package manifest"); }
}
