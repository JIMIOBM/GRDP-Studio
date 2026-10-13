package com.grdp.studio.softwareintegration.support;
import tools.jackson.databind.JsonNode;
import java.util.Set;
import java.util.regex.Pattern;

/** Literal initial WRAT/GRAT/LRAT with source, mode, original target and unit binding. */
public final class EclipseProductionControlParameters {
    public static final String SCHEMA = "eclipse-production-control-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "well", "phase", "sourceFile", "lineNumber", "controlMode", "originalTargetRate", "targetRate", "unit");
    private static final Pattern WELL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$");
    private static final Pattern FILE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$");
    private EclipseProductionControlParameters() {}
    public static boolean valid(JsonNode p) {
        if (p == null || !p.isObject() || p.size() != FIELDS.size()) return false;
        var names = new java.util.HashSet<String>(); p.properties().forEach(f -> names.add(f.getKey()));
        String mode = p.path("controlMode").asText();
        return names.equals(FIELDS) && SCHEMA.equals(p.path("schemaVersion").asText()) && "INITIAL".equals(p.path("phase").asText())
            && Set.of("WRAT", "GRAT", "LRAT").contains(mode)
            && ("sm3/day".equals(p.path("unit").asText()) || ("GRAT".equals(mode) ? "Mscf/day" : "STB/day").equals(p.path("unit").asText()))
            && p.path("baselineRunId").isIntegralNumber() && p.path("baselineRunId").canConvertToLong() && p.path("baselineRunId").asLong() > 0
            && p.path("lineNumber").isIntegralNumber() && p.path("lineNumber").canConvertToInt() && p.path("lineNumber").asInt() > 0
            && p.path("well").isTextual() && WELL.matcher(p.path("well").asText()).matches()
            && p.path("sourceFile").isTextual() && FILE.matcher(p.path("sourceFile").asText()).matches()
            && rate(p.path("originalTargetRate")) && rate(p.path("targetRate"))
            && p.path("originalTargetRate").asDouble() != p.path("targetRate").asDouble();
    }
    private static boolean rate(JsonNode v) { return v.isNumber() && Double.isFinite(v.asDouble()) && v.asDouble() > 0 && v.asDouble() <= 100_000_000; }
    private static java.util.List<String> expanded(JsonNode values) {
        var result = new java.util.ArrayList<String>();
        for (JsonNode item : values) {
            if (!item.isTextual()) return java.util.List.of();
            String value = item.asText();
            var repeat = Pattern.compile("^(\\d+)\\*(.*)$").matcher(value);
            int count = 1;
            if (!result.isEmpty() && repeat.matches()) {
                try { count = Integer.parseInt(repeat.group(1)); } catch (NumberFormatException e) { return java.util.List.of(); }
                // Repeated literals cannot be source-bound to a single rate field.
                value = repeat.group(2).isEmpty() ? "" : "REPEATED";
            }
            if (count < 1 || count > 64 || result.size() + count > 64) return java.util.List.of();
            for (int i = 0; i < count; i++) result.add(value);
        }
        return result;
    }
    public static boolean matchesInspection(JsonNode p, JsonNode i) {
        if (!valid(p) || i == null || !Set.of("eclipse-data-inspection/4", "eclipse-data-inspection/5").contains(i.path("schemaVersion").asText())
            || !Set.of("FIELD", "METRIC").contains(i.path("unitSystem").asText()) || !i.path("packageFiles").isArray() || i.path("packageFiles").size() != 1) return false;
        String unit = "METRIC".equals(i.path("unitSystem").asText()) ? "sm3/day" : "GRAT".equals(p.path("controlMode").asText()) ? "Mscf/day" : "STB/day";
        if (!unit.equals(p.path("unit").asText())) return false;
        String source = p.path("sourceFile").asText(), well = p.path("well").asText();
        if (!source.equals(i.path("caseName").asText()) || !source.equals(i.path("packageFiles").get(0).path("relativePath").asText())) return false;
        boolean known = false;
        for (JsonNode w : i.path("scheduleMetadata").path("wells")) if (well.equals(w.path("name").asText())) known = true;
        if (!known || !i.path("scheduleTimeline").isArray()) return false;
        int end = Integer.MAX_VALUE;
        for (JsonNode e : i.path("scheduleTimeline")) {
            if (!source.equals(e.path("sourceFile").asText()) || !e.path("lineNumber").isIntegralNumber() || e.path("lineNumber").asInt() <= 0) return false;
            end = Math.min(end, e.path("lineNumber").asInt());
        }
        int matches = 0;
        for (JsonNode r : i.path("scheduleMetadata").path("records")) {
            if (!source.equals(r.path("sourceFile").asText()) || r.path("lineNumber").asInt() >= end) continue;
            String keyword = r.path("keyword").asText();
            if (!Set.of("WCONPROD", "WCONINJE", "WCONHIST", "WELOPEN").contains(keyword)) continue;
            var v = expanded(r.path("values"));
            if (v.isEmpty() || !WELL.matcher(v.getFirst()).matches()) return false;
            if (!well.equals(v.getFirst())) continue;
            int rate = "WRAT".equals(p.path("controlMode").asText()) ? 4 : "GRAT".equals(p.path("controlMode").asText()) ? 5 : 6;
            if (!"WCONPROD".equals(keyword) || v.size() <= rate || !"OPEN".equals(v.get(1))
                || !v.get(2).equals(p.path("controlMode").asText()) || r.path("lineNumber").asInt() != p.path("lineNumber").asInt()) return false;
            try { if (Double.parseDouble(v.get(rate)) != p.path("originalTargetRate").asDouble()) return false; }
            catch (NumberFormatException e) { return false; }
            matches++;
        }
        return matches == 1;
    }
}
