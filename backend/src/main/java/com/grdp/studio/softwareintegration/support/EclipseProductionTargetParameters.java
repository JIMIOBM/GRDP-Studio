package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.util.Set;
import java.util.regex.Pattern;

/** Initial literal OPEN ORAT edit; unit/source/record and original value are bound to inspection. */
public final class EclipseProductionTargetParameters {
    public static final String SCHEMA = "eclipse-production-target-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "well", "phase", "sourceFile", "lineNumber", "unit", "originalOilRate", "targetOilRate");
    private static final Pattern WELL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$");
    private static final Pattern FILE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$");
    private EclipseProductionTargetParameters() {}
    public static boolean valid(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != FIELDS.size()) return false;
        var names = new java.util.HashSet<String>();
        value.properties().forEach(p -> names.add(p.getKey()));
        return names.equals(FIELDS) && SCHEMA.equals(value.path("schemaVersion").asText())
            && "INITIAL".equals(value.path("phase").asText()) && "STB/day".equals(value.path("unit").asText())
            && value.path("baselineRunId").isIntegralNumber() && value.path("baselineRunId").canConvertToLong() && value.path("baselineRunId").asLong() > 0
            && value.path("lineNumber").isIntegralNumber() && value.path("lineNumber").canConvertToInt() && value.path("lineNumber").asInt() > 0
            && value.path("well").isTextual() && WELL.matcher(value.path("well").asText()).matches()
            && value.path("sourceFile").isTextual() && FILE.matcher(value.path("sourceFile").asText()).matches()
            && rate(value.path("originalOilRate")) && rate(value.path("targetOilRate"))
            && value.path("originalOilRate").asDouble() != value.path("targetOilRate").asDouble();
    }
    private static boolean rate(JsonNode value) {
        return value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() > 0 && value.asDouble() <= 100_000_000;
    }
    public static boolean matchesInspection(JsonNode value, JsonNode inspection) {
        if (!valid(value) || inspection == null || !Set.of("eclipse-data-inspection/4", "eclipse-data-inspection/5").contains(inspection.path("schemaVersion").asText())
            || !"FIELD".equals(inspection.path("unitSystem").asText()) || inspection.path("packageFiles").size() != 1) return false;
        String source = value.path("sourceFile").asText(), well = value.path("well").asText();
        if (!source.equals(inspection.path("caseName").asText()) || !source.equals(inspection.path("packageFiles").get(0).path("relativePath").asText())) return false;
        boolean known = false;
        for (JsonNode item : inspection.path("scheduleMetadata").path("wells")) if (well.equals(item.path("name").asText())) known = true;
        if (!known) return false;
        int end = Integer.MAX_VALUE;
        for (JsonNode event : inspection.path("scheduleTimeline")) {
            if (!source.equals(event.path("sourceFile").asText()) || !event.path("lineNumber").isIntegralNumber()) return false;
            end = Math.min(end, event.path("lineNumber").asInt());
        }
        int matches = 0;
        for (JsonNode record : inspection.path("scheduleMetadata").path("records")) {
            if (!source.equals(record.path("sourceFile").asText()) || record.path("lineNumber").asInt() >= end) continue;
            String keyword = record.path("keyword").asText();
            JsonNode values = record.path("values");
            if (!Set.of("WCONPROD", "WCONINJE", "WCONHIST", "WELOPEN").contains(keyword)) continue;
            if (!values.isArray() || values.isEmpty()) return false;
            String name = values.get(0).asText();
            if (name.contains("*") || name.contains("?")) return false;
            if (!well.equals(name)) continue;
            if (!"WCONPROD".equals(keyword) || values.size() < 4 || !"OPEN".equals(values.get(1).asText()) || !"ORAT".equals(values.get(2).asText())
                || record.path("lineNumber").asInt() != value.path("lineNumber").asInt()) return false;
            try {
                double original = Double.parseDouble(values.get(3).asText());
                if (!Double.isFinite(original) || original != value.path("originalOilRate").asDouble()) return false;
            } catch (NumberFormatException exception) { return false; }
            matches++;
        }
        return matches == 1;
    }
}
