package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Complete dated target chains bound to their physical INCLUDE sources. */
public final class EclipsePackageControlPlanParameters {
    public static final String SCHEMA = "eclipse-control-plan-parameters/2";
    private static final Set<String> EDIT = Set.of("well", "sourceFile", "sourceSha256", "lineNumber", "date", "timeDays", "unit", "originalOilRate", "targetOilRate");
    private static final Set<String> RECORD = Set.of("well", "sourceFile", "sourceSha256", "lineNumber", "date", "timeDays", "unit", "originalOilRate", "bhpLimit");
    private EclipsePackageControlPlanParameters() {}
    private static boolean shape(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || value.size() != fields.size()) return false;
        var names = new HashSet<String>(); value.properties().forEach(p -> names.add(p.getKey())); return names.equals(fields);
    }
    private static boolean integer(JsonNode n, long min, long max) { return n.isIntegralNumber() && n.canConvertToLong() && n.asLong() >= min && n.asLong() <= max; }
    private static boolean positive(JsonNode n) { return n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble() > 0 && n.asDouble() <= 100_000_000; }
    private static boolean hash(JsonNode n) { return n.isTextual() && n.asText().matches("[a-f0-9]{64}"); }
    private static boolean well(JsonNode n) { return n.isTextual() && n.asText().matches("[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}"); }
    private static boolean path(JsonNode n) {
        if (!n.isTextual() || n.asText().isEmpty() || n.asText().length() > 512) return false;
        for (var part : n.asText().split("/", -1)) if (part.equals(".") || part.equals("..") || !part.matches("[A-Za-z0-9][A-Za-z0-9._ -]*")) return false;
        return true;
    }
    private static LocalDate date(JsonNode n) {
        if (!n.isTextual() || !n.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return null;
        try { return LocalDate.parse(n.asText()); } catch (RuntimeException e) { return null; }
    }
    public static boolean valid(JsonNode value) {
        if (!shape(value, Set.of("schemaVersion", "baselineRunId", "edits")) || !SCHEMA.equals(value.path("schemaVersion").asText())
            || !integer(value.path("baselineRunId"), 1, Long.MAX_VALUE) || !value.path("edits").isArray() || value.path("edits").isEmpty() || value.path("edits").size() > 24) return false;
        int previous = -1; var identities = new HashSet<String>(); var physicalRows = new HashSet<String>();
        for (var e : value.path("edits")) {
            if (!shape(e, EDIT) || !well(e.path("well")) || !path(e.path("sourceFile")) || !hash(e.path("sourceSha256"))
                || !integer(e.path("lineNumber"), 1, Integer.MAX_VALUE) || !integer(e.path("timeDays"), 0, 3_000_000) || date(e.path("date")) == null
                || !Set.of("STB/day", "sm3/day").contains(e.path("unit").asText()) || !positive(e.path("originalOilRate")) || !positive(e.path("targetOilRate"))
                || e.path("originalOilRate").asDouble() == e.path("targetOilRate").asDouble() || e.path("timeDays").asInt() < previous
                || !identities.add(e.path("well").asText() + ":" + e.path("timeDays").asInt())
                || !physicalRows.add(e.path("sourceFile").asText() + ":" + e.path("lineNumber").asInt())) return false;
            previous = e.path("timeDays").asInt();
        }
        return true;
    }
    private static List<String> expand(JsonNode values) {
        if (!values.isArray()) return null; var result = new ArrayList<String>();
        for (var value : values) {
            if (!value.isTextual()) return null; String text = value.asText(); int count = 1;
            if (text.matches("[0-9]+\\*.*")) {
                int split = text.indexOf('*'); try { count = Integer.parseInt(text.substring(0, split)); } catch (RuntimeException e) { return null; }
                text = text.substring(split + 1);
            }
            if (count < 1 || count > 128 || result.size() + count > 128) return null;
            for (int n = 0; n < count; n++) result.add(text);
        }
        return result;
    }
    private static boolean number(String text, double expected) {
        try { return Double.isFinite(Double.parseDouble(text)) && Double.parseDouble(text) == expected; } catch (RuntimeException e) { return false; }
    }
    public static boolean validContext(JsonNode context, JsonNode inspection) {
        if (!shape(context, Set.of("schemaVersion", "startDate", "endTimeDays", "records"))
            || !"eclipse-package-production-control-plan-context/1".equals(context.path("schemaVersion").asText())
            || inspection == null || !"eclipse-data-inspection/4".equals(inspection.path("schemaVersion").asText())
            || !Set.of("FIELD", "METRIC").contains(inspection.path("unitSystem").asText())
            || !inspection.path("packageFiles").isArray() || inspection.path("packageFiles").size() < 2
            || date(context.path("startDate")) == null || !integer(context.path("endTimeDays"), 1, 3_000_000)
            || !context.path("records").isArray() || context.path("records").isEmpty() || context.path("records").size() > 1000) return false;
        var start = date(context.path("startDate")); var previousDate = start; var reportTimes = new HashSet<Long>();
        var timeline = inspection.path("scheduleTimeline"); if (!timeline.isArray() || timeline.size() < 2) return false;
        for (var event : timeline) {
            if (!"DATES".equals(event.path("kind").asText()) || !event.path("records").isArray() || event.path("records").isEmpty()) return false;
            for (var r : event.path("records")) {
                if (!r.path("time").isNull() && !"00:00:00".equals(r.path("time").asText())) return false;
                int month = List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC").indexOf(r.path("month").asText()) + 1;
                LocalDate current;
                try { current = LocalDate.of(Integer.parseInt(r.path("year").asText()), month, Integer.parseInt(r.path("day").asText())); } catch (RuntimeException e) { return false; }
                if (!current.isAfter(previousDate)) return false; previousDate = current; reportTimes.add(ChronoUnit.DAYS.between(start, current));
            }
        }
        if (ChronoUnit.DAYS.between(start, previousDate) != context.path("endTimeDays").asInt()) return false;
        String unit = "FIELD".equals(inspection.path("unitSystem").asText()) ? "STB/day" : "sm3/day";
        var metadata = inspection.path("scheduleMetadata");
        if (!metadata.path("records").isArray() || !metadata.path("wells").isArray() || !metadata.path("completions").isArray()
            || !metadata.path("groups").isArray() || !metadata.path("groups").isEmpty()) return false;
        int controls = 0;
        for (var r : metadata.path("records")) {
            if (!Set.of("WCONPROD", "WCONINJE", "COMPDAT").contains(r.path("keyword").asText())) return false;
            if ("WCONPROD".equals(r.path("keyword").asText())) controls++;
        }
        if (controls != context.path("records").size()) return false;
        var identities = new HashSet<String>(); var physical = new HashSet<String>(); var initial = new HashSet<String>(); var phases = new HashSet<Integer>(); int previousTime = -1;
        for (var c : context.path("records")) {
            int time = c.path("timeDays").asInt(); var current = date(c.path("date"));
            if (!shape(c, RECORD) || !well(c.path("well")) || !path(c.path("sourceFile")) || !hash(c.path("sourceSha256"))
                || !integer(c.path("lineNumber"), 1, Integer.MAX_VALUE) || !integer(c.path("timeDays"), 0, context.path("endTimeDays").asInt() - 1)
                || current == null || ChronoUnit.DAYS.between(start, current) != time || time < previousTime || time > 0 && !reportTimes.contains((long)time)
                || !unit.equals(c.path("unit").asText()) || !positive(c.path("originalOilRate")) || !positive(c.path("bhpLimit"))
                || !identities.add(c.path("well").asText() + ":" + time) || !physical.add(c.path("sourceFile").asText() + ":" + c.path("lineNumber").asInt())) return false;
            if (time == 0) initial.add(c.path("well").asText()); else { if (!initial.contains(c.path("well").asText())) return false; phases.add(time); }
            int files = 0, definitions = 0, records = 0, completions = 0;
            for (var file : inspection.path("packageFiles")) if (file.path("relativePath").equals(c.path("sourceFile")) && file.path("sha256").equals(c.path("sourceSha256"))) files++;
            for (var definition : metadata.path("wells")) if (definition.path("name").equals(c.path("well"))) definitions++;
            for (var completion : metadata.path("completions")) if (completion.path("well").equals(c.path("well"))) { if (!"OPEN".equals(completion.path("status").asText())) return false; completions++; }
            for (var r : metadata.path("records")) {
                if ("WCONINJE".equals(r.path("keyword").asText()) && r.path("values").path(0).equals(c.path("well"))) return false;
                if (!"WCONPROD".equals(r.path("keyword").asText()) || !r.path("sourceFile").equals(c.path("sourceFile")) || !r.path("lineNumber").equals(c.path("lineNumber"))) continue;
                var v = expand(r.path("values"));
                if (v == null || v.size() < 9 || v.size() > 12 || !v.get(0).equals(c.path("well").asText()) || !v.get(1).equals("OPEN") || !v.get(2).equals("ORAT")
                    || !number(v.get(3), c.path("originalOilRate").asDouble()) || v.subList(4, 8).stream().anyMatch(s -> !s.isEmpty())
                    || !number(v.get(8), c.path("bhpLimit").asDouble()) || v.subList(9, v.size()).stream().anyMatch(s -> !s.isEmpty() && !s.equals("0"))) return false;
                records++;
            }
            if (files != 1 || definitions != 1 || records != 1 || completions == 0) return false;
            previousTime = time;
        }
        return !initial.isEmpty() && phases.size() >= 2;
    }
    public static boolean matchesInspection(JsonNode value, JsonNode inspection) {
        if (!valid(value) || inspection == null || !validContext(inspection.path("packageProductionControlPlanContext"), inspection)) return false;
        int previous = -1;
        for (var edit : value.path("edits")) {
            int index = 0, found = -1;
            for (var record : inspection.path("packageProductionControlPlanContext").path("records")) {
                if (EDIT.stream().filter(k -> !k.equals("targetOilRate")).allMatch(k -> k.equals("originalOilRate") ? edit.path(k).asDouble() == record.path(k).asDouble() : edit.path(k).equals(record.path(k)))) {
                    if (found >= 0) return false; found = index;
                }
                index++;
            }
            if (found <= previous) return false; previous = found;
        }
        return true;
    }
}
