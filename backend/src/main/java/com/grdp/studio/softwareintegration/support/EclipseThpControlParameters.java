package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.util.Set;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** Existing initial FIELD producer THP bound to a complete VFPPROD table. */
public final class EclipseThpControlParameters {
    public static final String SCHEMA = "eclipse-thp-control-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "well", "phase", "sourceFile", "lineNumber", "unit", "originalThp", "targetThp", "vfpTableNumber", "vfpTableSha256", "originalAlq");
    private EclipseThpControlParameters() {}
    public static boolean valid(JsonNode p) {
        if (p == null || !p.isObject() || p.size() != FIELDS.size()) return false;
        var names = new java.util.HashSet<String>(); p.properties().forEach(f -> names.add(f.getKey()));
        return names.equals(FIELDS) && SCHEMA.equals(p.path("schemaVersion").asText()) && "INITIAL".equals(p.path("phase").asText()) && "psia".equals(p.path("unit").asText())
            && integer(p.path("baselineRunId"), Long.MAX_VALUE) && integer(p.path("lineNumber"), Integer.MAX_VALUE) && integer(p.path("vfpTableNumber"), 100000)
            && pattern(p.path("well"), "^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$")
            && pattern(p.path("sourceFile"), "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$")
            && pattern(p.path("vfpTableSha256"), "^[a-f0-9]{64}$")
            && number(p.path("originalThp"), false) && number(p.path("targetThp"), false) && number(p.path("originalAlq"), true)
            && p.path("originalThp").asDouble() != p.path("targetThp").asDouble();
    }
    private static boolean integer(JsonNode v, long max) { return v.isIntegralNumber() && v.canConvertToLong() && v.asLong() > 0 && v.asLong() <= max; }
    private static boolean number(JsonNode v, boolean zero) { return v.isNumber() && Double.isFinite(v.asDouble()) && (zero ? v.asDouble() >= 0 : v.asDouble() > 0) && v.asDouble() <= 1e8; }
    private static boolean pattern(JsonNode v, String regex) { return v.isTextual() && Pattern.matches(regex, v.asText()); }
    private static boolean inside(JsonNode axis, double v) { return axis.isArray() && !axis.isEmpty() && v >= axis.get(0).asDouble() && v <= axis.get(axis.size()-1).asDouble(); }
    public static boolean matchesInspection(JsonNode p, JsonNode i) {
        if (!valid(p) || i == null || !"eclipse-data-inspection/5".equals(i.path("schemaVersion").asText()) || !"FIELD".equals(i.path("unitSystem").asText())
            || !i.path("packageFiles").isArray() || i.path("packageFiles").size() != 1) return false;
        var source = p.path("sourceFile").asText(); var well = p.path("well").asText();
        if (!source.equals(i.path("caseName").asText()) || !source.equals(i.path("packageFiles").get(0).path("relativePath").asText())) return false;
        boolean tableFound = false, wellFound = false;
        for (var t : i.path("vfpTables")) {
            if (t.path("number").asInt() != p.path("vfpTableNumber").asInt()) continue;
            if (tableFound || !source.equals(t.path("sourceFile").asText()) || !p.path("vfpTableSha256").asText().equals(t.path("sha256").asText())
                || !"LIQ".equals(t.path("flowType").asText()) || !"WCT".equals(t.path("waterFractionType").asText()) || !"GOR".equals(t.path("gasFractionType").asText())
                || !"".equals(t.path("alqType").asText()) || !inside(t.path("thpAxis"), p.path("targetThp").asDouble())
                || !inside(t.path("thpAxis"), p.path("originalThp").asDouble()) || !inside(t.path("alqAxis"), p.path("originalAlq").asDouble())) return false;
            tableFound = true;
        }
        for (var w : i.path("scheduleMetadata").path("wells")) if (well.equals(w.path("name").asText())) wellFound = true;
        if (!tableFound || !wellFound || !i.path("scheduleTimeline").isArray()) return false;
        int end = Integer.MAX_VALUE;
        for (var e : i.path("scheduleTimeline")) {
            if (!source.equals(e.path("sourceFile").asText()) || !integer(e.path("lineNumber"), Integer.MAX_VALUE)) return false;
            end = Math.min(end, e.path("lineNumber").asInt());
        }
        int matches = 0;
        for (var r : i.path("scheduleMetadata").path("records")) {
            if (!source.equals(r.path("sourceFile").asText()) || r.path("lineNumber").asInt() >= end || !Set.of("WCONPROD", "WCONINJE", "WCONHIST", "WELOPEN").contains(r.path("keyword").asText())) continue;
            var values = new ArrayList<String>();
            for (var item : r.path("values")) {
                if (!item.isTextual()) return false;
                var repeat = Pattern.compile("^(\\d+)\\*(.*)$").matcher(item.asText()); int count = 1; String v = item.asText();
                if (!values.isEmpty() && repeat.matches()) {
                    try { count = Integer.parseInt(repeat.group(1)); } catch (NumberFormatException e) { return false; }
                    v = repeat.group(2).isEmpty() ? "" : "REPEATED";
                }
                if (count < 1 || count > 64 || values.size()+count > 64) return false;
                for (int j=0; j<count; j++) values.add(v);
            }
            if (values.isEmpty() || values.getFirst().contains("*") || values.getFirst().contains("?")) return false;
            if (!values.getFirst().equals(well)) continue;
            if (!"WCONPROD".equals(r.path("keyword").asText()) || values.size() < 11 || !"OPEN".equals(values.get(1)) || !"THP".equals(values.get(2)) || r.path("lineNumber").asInt() != p.path("lineNumber").asInt()) return false;
            try {
                double alq = values.size() > 11 && !values.get(11).isEmpty() ? Double.parseDouble(values.get(11)) : 0;
                if (Double.parseDouble(values.get(9)) != p.path("originalThp").asDouble() || Integer.parseInt(values.get(10)) != p.path("vfpTableNumber").asInt() || alq != p.path("originalAlq").asDouble()) return false;
            } catch (NumberFormatException e) { return false; }
            matches++;
        }
        return matches == 1;
    }
}
