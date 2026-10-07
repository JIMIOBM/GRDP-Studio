package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

/** Bind recovered success to the durable request, independently of Worker IDs and file hashes. */
public final class TemplateCreationSourceValidator {
    private TemplateCreationSourceValidator() { }

    public static void requireSuccess(JsonNode request, JsonNode record) {
        if (record == null || !"SUCCEEDED".equals(record.path("status").asText())) return;
        if (request == null || !request.isObject()) reject();
        JsonNode result = record.path("result");
        if (!text(result, "schemaVersion", "pipesim-template-profile-preflight/1")
                || !text(result, "template", "Simple vertical")
                || !text(result, "well", request.path("well").asText())
                || !text(result, "nativeState", "Completed")
                || !text(result, "geometryOrigin", "official-template-inherited")
                || !text(result, "fluidOrigin", "explicit-scalar-inputs-with-installed-SDK-default-correlations")
                || !result.path("calculationVerified").isBoolean() || !result.path("calculationVerified").booleanValue()
                || !result.path("platformVerified").isBoolean() || result.path("platformVerified").booleanValue()
                || !result.path("modelDiagnostics").isArray() || !result.path("modelDiagnostics").isEmpty()
                || !sameScalars(request.path("inputs"), result.path("inputs"))) reject();
        JsonNode profile = result.path("profile");
        if (!profile.isArray() || profile.isEmpty() || profile.size() > 4096) reject();
        for (JsonNode point : profile) {
            for (String key : java.util.List.of("depth", "pressure", "temperature")) {
                JsonNode value = point.path(key);
                if (!value.isNumber() || !Double.isFinite(value.doubleValue())) reject();
            }
        }
        JsonNode model = record.path("model");
        JsonNode size = model.path("sizeBytes");
        if (!model.path("sha256").isString() || !model.path("sha256").asText().matches("[a-fA-F0-9]{64}")
                || !size.isIntegralNumber() || !size.canConvertToLong() || size.longValue() < 1
                || size.longValue() > 64L * 1024 * 1024) reject();
    }

    private static boolean sameScalars(JsonNode expected, JsonNode actual) {
        if (!expected.isObject() || !actual.isObject() || expected.size() != actual.size()) return false;
        for (var entry : expected.properties()) {
            JsonNode left = entry.getValue(), right = actual.path(entry.getKey());
            if (left.isNumber()) {
                if (!right.isNumber() || !Double.isFinite(left.doubleValue()) || !Double.isFinite(right.doubleValue())
                        || left.decimalValue().compareTo(right.decimalValue()) != 0) return false;
            } else if (!left.isString() || !left.equals(right)) return false;
        }
        return true;
    }

    private static boolean text(JsonNode node, String key, String expected) {
        return node.path(key).isString() && expected.equals(node.path(key).asText());
    }

    private static void reject() { throw new IllegalStateException("Creation success source contract mismatch"); }
}
