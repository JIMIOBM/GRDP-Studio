package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** Binds initial package targets to physical source files and their validated hashes. */
public final class EclipsePackageProductionTargetParameters {
    public static final String SCHEMA = "eclipse-production-target-parameters/2";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "well", "phase", "sourceFile", "sourceSha256", "lineNumber", "unit", "originalOilRate", "targetOilRate");
    private static final Set<String> TARGET_FIELDS = Set.of("well", "sourceFile", "sourceSha256", "lineNumber", "originalOilRate");
    private EclipsePackageProductionTargetParameters() {}

    private static boolean fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) return false;
        var names = new HashSet<String>(); node.properties().forEach(p -> names.add(p.getKey()));
        return names.equals(expected);
    }
    private static boolean rate(JsonNode node) { return node.isNumber() && Double.isFinite(node.asDouble()) && node.asDouble() > 0 && node.asDouble() <= 100_000_000; }
    private static boolean positiveInt(JsonNode node) { return node.isIntegralNumber() && node.canConvertToInt() && node.asInt() > 0; }
    private static boolean well(JsonNode node) { return node.isTextual() && node.asText().matches("^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$"); }
    private static boolean path(JsonNode node) {
        if (!node.isTextual() || node.asText().isEmpty() || node.asText().length() > 512) return false;
        for (var segment : node.asText().split("/", -1))
            if (segment.equals(".") || segment.equals("..") || !segment.matches("^[A-Za-z0-9][A-Za-z0-9._ -]*$")) return false;
        return true;
    }
    private static boolean hash(JsonNode node) { return node.isTextual() && node.asText().matches("^[a-f0-9]{64}$"); }

    public static boolean valid(JsonNode value) {
        return fields(value, FIELDS) && SCHEMA.equals(value.path("schemaVersion").asText())
            && "INITIAL".equals(value.path("phase").asText()) && "STB/day".equals(value.path("unit").asText())
            && value.path("baselineRunId").isIntegralNumber() && value.path("baselineRunId").canConvertToLong() && value.path("baselineRunId").asLong() > 0
            && well(value.path("well")) && path(value.path("sourceFile")) && hash(value.path("sourceSha256"))
            && positiveInt(value.path("lineNumber")) && rate(value.path("originalOilRate")) && rate(value.path("targetOilRate"))
            && value.path("originalOilRate").asDouble() != value.path("targetOilRate").asDouble();
    }

    public static boolean validContext(JsonNode context, JsonNode inspection) {
        if (!fields(context, Set.of("schemaVersion", "targets"))
            || !"eclipse-package-production-target-context/1".equals(context.path("schemaVersion").asText())
            || inspection == null || !"FIELD".equals(inspection.path("unitSystem").asText())
            || !"eclipse-data-inspection/4".equals(inspection.path("schemaVersion").asText())
            || !inspection.path("packageFiles").isArray() || inspection.path("packageFiles").size() < 2
            || !context.path("targets").isArray() || context.path("targets").isEmpty() || context.path("targets").size() > 1000) return false;
        var wells = new HashSet<String>();
        for (var target : context.path("targets")) {
            if (!fields(target, TARGET_FIELDS) || !well(target.path("well")) || !wells.add(target.path("well").asText())
                || !path(target.path("sourceFile")) || !hash(target.path("sourceSha256"))
                || !positiveInt(target.path("lineNumber")) || !rate(target.path("originalOilRate"))) return false;
            int files = 0, records = 0, definitions = 0;
            for (var file : inspection.path("packageFiles"))
                if (target.path("sourceFile").equals(file.path("relativePath")) && target.path("sourceSha256").equals(file.path("sha256"))) files++;
            for (var definition : inspection.path("scheduleMetadata").path("wells"))
                if (target.path("well").equals(definition.path("name"))) definitions++;
            for (var record : inspection.path("scheduleMetadata").path("records")) {
                var values = record.path("values");
                if (!"WCONPROD".equals(record.path("keyword").asText()) || !target.path("sourceFile").equals(record.path("sourceFile"))
                    || !target.path("lineNumber").equals(record.path("lineNumber")) || !values.isArray() || values.size() < 4
                    || !target.path("well").equals(values.path(0)) || !"OPEN".equals(values.path(1).asText()) || !"ORAT".equals(values.path(2).asText())) continue;
                try { if (Double.parseDouble(values.path(3).asText()) == target.path("originalOilRate").asDouble()) records++; }
                catch (NumberFormatException ignored) { return false; }
            }
            if (files != 1 || definitions != 1 || records != 1) return false;
        }
        return true;
    }

    public static boolean matchesInspection(JsonNode value, JsonNode inspection) {
        if (!valid(value) || inspection == null || !validContext(inspection.path("packageProductionTargetContext"), inspection)) return false;
        for (var target : inspection.path("packageProductionTargetContext").path("targets"))
            if (target.path("well").equals(value.path("well")) && target.path("sourceFile").equals(value.path("sourceFile"))
                && target.path("sourceSha256").equals(value.path("sourceSha256")) && target.path("lineNumber").equals(value.path("lineNumber"))
                && target.path("originalOilRate").asDouble() == value.path("originalOilRate").asDouble()) return true;
        return false;
    }
}
