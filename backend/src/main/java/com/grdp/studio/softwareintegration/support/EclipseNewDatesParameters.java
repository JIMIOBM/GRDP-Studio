package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Set;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** Inserts two new midnight dates inside adjacent existing single-record DATES in one DATA. */
public final class EclipseNewDatesParameters {
    public static final String SCHEMA = "eclipse-new-dates-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "well", "sourceFile", "beforeLineNumber", "beforeDate", "afterLineNumber", "afterDate", "shutDate", "openDate");
    private EclipseNewDatesParameters() {}
    public static boolean valid(JsonNode p) {
        if (p == null || !p.isObject() || p.size() != FIELDS.size()) return false;
        var keys = new java.util.HashSet<String>(); p.properties().forEach(f -> keys.add(f.getKey()));
        if (!keys.equals(FIELDS) || !SCHEMA.equals(p.path("schemaVersion").asText())
            || !integer(p.path("baselineRunId"), Long.MAX_VALUE) || !integer(p.path("beforeLineNumber"), Integer.MAX_VALUE) || !integer(p.path("afterLineNumber"), Integer.MAX_VALUE)
            || p.path("beforeLineNumber").asInt() >= p.path("afterLineNumber").asInt()
            || !pattern(p.path("well"), "^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$") || !pattern(p.path("sourceFile"), "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$")) return false;
        for (var field : new String[] {"beforeDate", "afterDate", "shutDate", "openDate"}) if (date(p.path(field)) == null) return false;
        return p.path("beforeDate").asText().compareTo(p.path("shutDate").asText()) < 0 && p.path("shutDate").asText().compareTo(p.path("openDate").asText()) < 0
            && p.path("openDate").asText().compareTo(p.path("afterDate").asText()) < 0;
    }
    private static LocalDate date(JsonNode v) { if (!pattern(v, "^[0-9]{4}-[0-9]{2}-[0-9]{2}$") || v.asText().startsWith("0000")) return null; try { return LocalDate.parse(v.asText()); } catch (RuntimeException e) { return null; } }
    private static boolean integer(JsonNode v, long max) { return v.isIntegralNumber() && v.canConvertToLong() && v.asLong() > 0 && v.asLong() <= max; }
    private static boolean pattern(JsonNode v, String regex) { return v.isTextual() && Pattern.matches(regex, v.asText()); }
    private static LocalDate recordDate(JsonNode r) {
        try {
            int month = switch(r.path("month").asText().toUpperCase(java.util.Locale.ROOT)) { case "JAN" -> 1; case "FEB" -> 2; case "MAR" -> 3; case "APR" -> 4; case "MAY" -> 5; case "JUN" -> 6; case "JUL", "JLY" -> 7; case "AUG" -> 8; case "SEP" -> 9; case "OCT" -> 10; case "NOV" -> 11; case "DEC" -> 12; default -> 0; };
            if (!r.path("time").isMissingNode() && !r.path("time").isNull()) return null;
            return LocalDate.of(Integer.parseInt(r.path("year").asText()), month, Integer.parseInt(r.path("day").asText()));
        } catch(RuntimeException e) { return null; }
    }
    public static boolean matchesInspection(JsonNode p, JsonNode i) {
        if (!valid(p) || i == null || !Set.of("eclipse-data-inspection/4", "eclipse-data-inspection/5").contains(i.path("schemaVersion").asText())
            || i.path("packageFiles").size() != 1 || !p.path("sourceFile").asText().equals(i.path("caseName").asText())
            || !p.path("sourceFile").asText().equals(i.path("packageFiles").get(0).path("relativePath").asText()) || !i.path("scheduleTimeline").isArray()) return false;
        var timeline = new ArrayList<JsonNode>(); LocalDate previous = null; int previousLine = 0; boolean window = false;
        for (var e : i.path("scheduleTimeline")) {
            if (!"DATES".equals(e.path("kind").asText()) || e.path("records").size() != 1 || !p.path("sourceFile").asText().equals(e.path("sourceFile").asText())
                || !integer(e.path("lineNumber"), Integer.MAX_VALUE)) return false;
            var d = recordDate(e.path("records").get(0)); int line = e.path("lineNumber").asInt();
            if (d == null || previous != null && (!d.isAfter(previous) || line <= previousLine)) return false;
            if (previousLine == p.path("beforeLineNumber").asInt() && line == p.path("afterLineNumber").asInt()
                && previous.toString().equals(p.path("beforeDate").asText()) && d.toString().equals(p.path("afterDate").asText())) window = true;
            timeline.add(e); previous = d; previousLine = line;
        }
        if (!window || timeline.size() < 2) return false;
        var source = p.path("sourceFile").asText(); var well = p.path("well").asText(); int end = p.path("afterLineNumber").asInt();
        boolean defined = false, completion = false; JsonNode last = null;
        for (var w : i.path("scheduleMetadata").path("wells")) if (source.equals(w.path("sourceFile").asText()) && well.equals(w.path("name").asText()) && w.path("lineNumber").asInt() < end) defined = true;
        for (var c : i.path("scheduleMetadata").path("completions")) if (source.equals(c.path("sourceFile").asText()) && well.equals(c.path("well").asText()) && c.path("lineNumber").asInt() < end && "OPEN".equals(c.path("status").asText())) completion = true;
        for (var r : i.path("scheduleMetadata").path("records")) {
            if (!source.equals(r.path("sourceFile").asText()) || r.path("lineNumber").asInt() >= end) continue;
            var keyword = r.path("keyword").asText(); var values = r.path("values");
            if (!Set.of("WCONPROD", "WCONHIST", "WCONINJE", "WELOPEN").contains(keyword)) continue;
            if (values.isEmpty() || values.get(0).asText().contains("*") || values.get(0).asText().contains("?")) return false;
            if (!well.equals(values.get(0).asText())) continue;
            if (Set.of("WELOPEN", "WCONINJE").contains(keyword)) return false;
            if (last == null || r.path("lineNumber").asInt() > last.path("lineNumber").asInt()) last = r;
        }
        return defined && completion && last != null && "OPEN".equals(last.path("values").path(1).asText());
    }
}
