package com.grdp.studio.softwareintegration.support;
import tools.jackson.databind.JsonNode;
import java.util.Set;
import java.util.regex.Pattern;

/** Literal initial BHP with source, unit, original mode and fluid binding. */
public final class EclipsePressureControlParameters {
    public static final String SCHEMA = "eclipse-pressure-control-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "well", "phase", "sourceFile", "lineNumber", "keyword", "originalControlMode", "originalBhp", "targetBhp", "unit", "injectionType");
    private static final Pattern WELL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$");
    private static final Pattern FILE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$");
    private EclipsePressureControlParameters() {}
    public static boolean valid(JsonNode p) {
        if (p == null || !p.isObject() || p.size() != FIELDS.size()) return false;
        var names = new java.util.HashSet<String>(); p.properties().forEach(f -> names.add(f.getKey()));
        String keyword = p.path("keyword").asText(), mode = p.path("originalControlMode").asText();
        return names.equals(FIELDS) && SCHEMA.equals(p.path("schemaVersion").asText()) && "INITIAL".equals(p.path("phase").asText())
            && "psia".equals(p.path("unit").asText()) && Set.of("WCONPROD", "WCONINJE").contains(keyword)
            && ("WCONPROD".equals(keyword) ? Set.of("ORAT", "WRAT", "GRAT", "LRAT", "BHP") : Set.of("RATE", "RESV", "BHP")).contains(mode)
            && p.path("baselineRunId").isIntegralNumber() && p.path("baselineRunId").canConvertToLong() && p.path("baselineRunId").asLong() > 0
            && p.path("lineNumber").isIntegralNumber() && p.path("lineNumber").canConvertToInt() && p.path("lineNumber").asInt() > 0
            && p.path("well").isTextual() && WELL.matcher(p.path("well").asText()).matches()
            && p.path("sourceFile").isTextual() && FILE.matcher(p.path("sourceFile").asText()).matches()
            && pressure(p.path("originalBhp")) && pressure(p.path("targetBhp"))
            && (!"BHP".equals(mode) || p.path("originalBhp").asDouble() != p.path("targetBhp").asDouble())
            && ("WCONPROD".equals(keyword) ? p.path("injectionType").isTextual() && "NONE".equals(p.path("injectionType").asText())
                : p.path("injectionType").isTextual() && Set.of("WATER", "WAT", "OIL", "GAS").contains(p.path("injectionType").asText()));
    }
    private static boolean pressure(JsonNode v) { return v.isNumber() && Double.isFinite(v.asDouble()) && v.asDouble() > 0 && v.asDouble() <= 100000; }
    private static java.util.List<String> expanded(JsonNode values) {
        var result = new java.util.ArrayList<String>();
        for (JsonNode item : values) {
            if (!item.isTextual()) return java.util.List.of();
            String value = item.asText();
            var repeat = Pattern.compile("^(\\d+)\\*(.*)$").matcher(value);
            int count = 1;
            if (!result.isEmpty() && repeat.matches()) {
                try { count = Integer.parseInt(repeat.group(1)); } catch (NumberFormatException e) { return java.util.List.of(); }
                // Repeated literals cannot be source-bound to a single pressure field.
                value = repeat.group(2).isEmpty() ? "" : "REPEATED";
            }
            if (count < 1 || count > 64 || result.size() + count > 64) return java.util.List.of();
            for (int i = 0; i < count; i++) result.add(value);
        }
        return result;
    }
    public static boolean matchesInspection(JsonNode p, JsonNode i) {
        if (!valid(p) || i == null || !Set.of("eclipse-data-inspection/4", "eclipse-data-inspection/5").contains(i.path("schemaVersion").asText())
            || !"FIELD".equals(i.path("unitSystem").asText()) || !i.path("packageFiles").isArray() || i.path("packageFiles").size() != 1) return false;
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
            boolean injection = "WCONINJE".equals(keyword);
            int mode = injection ? 3 : 2, bhp = injection ? 6 : 8, status = injection ? 2 : 1;
            if (!keyword.equals(p.path("keyword").asText()) || v.size() <= bhp || !"OPEN".equals(v.get(status))
                || !v.get(mode).equals(p.path("originalControlMode").asText()) || r.path("lineNumber").asInt() != p.path("lineNumber").asInt()
                || (injection && !v.get(1).equals(p.path("injectionType").asText()))) return false;
            try { if (Double.parseDouble(v.get(bhp)) != p.path("originalBhp").asDouble()) return false; }
            catch (NumberFormatException e) { return false; }
            matches++;
        }
        return matches == 1;
    }
}
