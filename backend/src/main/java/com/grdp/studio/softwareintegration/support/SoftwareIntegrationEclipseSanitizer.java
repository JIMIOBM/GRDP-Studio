package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SoftwareIntegrationEclipseSanitizer {
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "cookie", "authorization", "token", "accesstoken", "refreshtoken",
            "license", "licensecontent", "licensevalue", "path", "absolutepath", "installpath",
            "eclpath", "command", "commandline", "executable", "deck", "deckcontent", "modelcontent");
    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)\\b(password|passwd|cookie|authorization|bearer|token|access[_-]?token|refresh[_-]?token|license(?:[_-]?(?:content|value|server|path))?|command(?:[_-]?line)?)\\b(\\s*[:=]\\s*)([^\\r\\n,;]+)");
    private static final Pattern UNC_PATH = Pattern.compile(
            "(?i)(?<![\\\\/])\\\\\\\\[^\\s\\\\/]+[\\\\/][^\\r\\n,;]+");
    private static final Pattern UNIX_PATH = Pattern.compile("(?<![A-Za-z0-9])/(?:[^\\s/]+/)+[^\\r\\n,;]*");

    private SoftwareIntegrationEclipseSanitizer() {}

    public static JsonNode sanitize(JsonNode value, ObjectMapper objectMapper) {
        if (value == null || value.isNull() || value.isNumber() || value.isBoolean()) return value;
        if (value.isTextual()) return objectMapper.getNodeFactory().textNode(sanitizeText(value.asText()));
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(sanitize(item, objectMapper)));
            return result;
        }
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            value.properties().forEach(field -> {
                String key = field.getKey();
                if (isSensitiveKey(key)) result.put(key, "[redacted]");
                else result.set(key, sanitize(field.getValue(), objectMapper));
            });
            return result;
        }
        return objectMapper.getNodeFactory().textNode("[redacted]");
    }

    public static String sanitizeText(String value) {
        if (value == null) return null;
        String sanitized = SoftwareIntegrationDiagnosticSanitizer.sanitize(value);
        sanitized = UNC_PATH.matcher(sanitized).replaceAll("[local path]");
        sanitized = UNIX_PATH.matcher(sanitized).replaceAll("[local path]");
        return INLINE_SECRET.matcher(sanitized).replaceAll("$1$2[redacted]");
    }

    public static boolean isSafeIdentifier(String value) {
        return value != null && value.length() <= 1000 && value.chars().noneMatch(Character::isISOControl)
                && value.equals(sanitizeText(value));
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.contains(normalized)
                || normalized.contains("password") || normalized.contains("passwd")
                || normalized.contains("cookie") || normalized.contains("authorization")
                || normalized.contains("token") || normalized.contains("license")
                || normalized.contains("commandline") || normalized.contains("executable")
                || normalized.contains("deckcontent") || normalized.contains("modelcontent")
                || normalized.endsWith("path") || normalized.endsWith("directory");
    }
}
