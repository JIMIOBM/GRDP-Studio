package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Set;
import java.util.regex.Pattern;

/** The deliberately small, whole-well Schedule scenario contracts. */
public final class EclipseScheduleParameters {
    public static final String SCHEMA = "eclipse-schedule-parameters/1";
    public static final String CONTROL_SCHEMA = "eclipse-schedule-parameters/2";
    public static final String INJECTION_SCHEMA = "eclipse-schedule-parameters/3";
    public static final String PRODUCTION_SCHEMA = "eclipse-schedule-parameters/4";
    public static final String MULTI_EVENT_SCHEMA = "eclipse-schedule-parameters/5";
    public static final String MULTI_WELL_SCHEMA = "eclipse-schedule-parameters/6";
    public static final String MULTI_DATE_SCHEMA = "eclipse-schedule-parameters/7";
    private static final Set<String> MULTI_WELL_FIELDS = Set.of("schemaVersion", "baselineRunId", "wells", "events");
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "well", "date", "status");
    private static final Set<String> CONTROL_FIELDS = Set.of("schemaVersion", "baselineRunId", "well", "date", "status", "controlMode", "targetOilRate");
    private static final Set<String> INJECTION_FIELDS = Set.of("schemaVersion", "baselineRunId", "well", "date", "injectionType", "controlMode", "targetInjectionRate");
    private static final Set<String> PRODUCTION_FIELDS = Set.of("schemaVersion", "baselineRunId", "well", "phase", "status", "controlMode", "targetOilRate");
    private static final Set<String> MULTI_EVENT_FIELDS = Set.of("schemaVersion", "baselineRunId", "well", "events");
    private static final Set<String> EVENT_FIELDS = Set.of("date", "status");
    private static final Pattern WELL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$");
    private static final Pattern INJECTION_TYPE = Pattern.compile("^[A-Za-z][A-Za-z0-9_ -]{0,31}$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private EclipseScheduleParameters() {}

    public static boolean valid(JsonNode value) {
        if (value == null || !value.isObject()) return false;
        var names = new java.util.HashSet<String>();
        value.properties().forEach(field -> names.add(field.getKey()));
        String schema = value.path("schemaVersion").asText();
        if (MULTI_WELL_SCHEMA.equals(schema) || MULTI_DATE_SCHEMA.equals(schema)) return validMultiWell(value, names, MULTI_DATE_SCHEMA.equals(schema));
        if (SCHEMA.equals(schema) && !names.equals(FIELDS)) return false;
        if (CONTROL_SCHEMA.equals(schema) && !names.equals(CONTROL_FIELDS)) return false;
        if (INJECTION_SCHEMA.equals(schema) && !names.equals(INJECTION_FIELDS)) return false;
        if (PRODUCTION_SCHEMA.equals(schema) && !names.equals(PRODUCTION_FIELDS)) return false;
        if (MULTI_EVENT_SCHEMA.equals(schema) && !names.equals(MULTI_EVENT_FIELDS)) return false;
        if (!SCHEMA.equals(schema) && !CONTROL_SCHEMA.equals(schema) && !INJECTION_SCHEMA.equals(schema) && !PRODUCTION_SCHEMA.equals(schema) && !MULTI_EVENT_SCHEMA.equals(schema)) return false;
        if (!value.path("baselineRunId").isIntegralNumber() || !value.path("baselineRunId").canConvertToLong()
                || value.path("baselineRunId").asLong() <= 0) return false;
        String well = value.path("well").asText(null);
        String date = value.path("date").asText(null);
        String status = value.path("status").asText(null);
        if (well == null || !WELL.matcher(well).matches()
                || (!PRODUCTION_SCHEMA.equals(schema) && !MULTI_EVENT_SCHEMA.equals(schema) && (date == null || !DATE.matcher(date).matches()))) return false;
        if (!INJECTION_SCHEMA.equals(schema) && !MULTI_EVENT_SCHEMA.equals(schema) && (status == null || !Set.of("OPEN", "SHUT").contains(status))) return false;
        if (MULTI_EVENT_SCHEMA.equals(schema)) return validEvents(value.path("events"));
        if (!PRODUCTION_SCHEMA.equals(schema)) {
            try { LocalDate.parse(date); } catch (DateTimeException exception) { return false; }
        }
        if (CONTROL_SCHEMA.equals(schema)) {
            if (!"ORAT".equals(value.path("controlMode").asText())
                    || !value.path("targetOilRate").isNumber()
                    || !Double.isFinite(value.path("targetOilRate").asDouble())
                    || value.path("targetOilRate").asDouble() <= 0
                    || value.path("targetOilRate").asDouble() > 100000000) return false;
        }
        if (INJECTION_SCHEMA.equals(schema)) {
            if (!INJECTION_TYPE.matcher(value.path("injectionType").asText()).matches()
                    || !"RATE".equals(value.path("controlMode").asText())
                    || !value.path("targetInjectionRate").isNumber()
                    || !Double.isFinite(value.path("targetInjectionRate").asDouble())
                    || value.path("targetInjectionRate").asDouble() <= 0
                    || value.path("targetInjectionRate").asDouble() > 100000000) return false;
        }
        if (PRODUCTION_SCHEMA.equals(schema)) {
            if (!"FORECAST_INITIAL".equals(value.path("phase").asText())
                    || !"ORAT".equals(value.path("controlMode").asText())
                    || !value.path("targetOilRate").isNumber()
                    || !Double.isFinite(value.path("targetOilRate").asDouble())
                    || value.path("targetOilRate").asDouble() <= 0
                    || value.path("targetOilRate").asDouble() > 100000000) return false;
        }
        return true;
    }

    public static boolean matchesInspection(JsonNode value, JsonNode inspection) {
        if (!valid(value) || inspection == null || !Set.of("eclipse-data-inspection/4", "eclipse-data-inspection/5", "eclipse-data-inspection/6", "eclipse-data-inspection/7", "eclipse-data-inspection/8", "eclipse-data-inspection/9", "eclipse-data-inspection/10", "eclipse-data-inspection/11", "eclipse-data-inspection/12", "eclipse-data-inspection/13").contains(inspection.path("schemaVersion").asText())) return false;
        if (Set.of(MULTI_WELL_SCHEMA, MULTI_DATE_SCHEMA).contains(value.path("schemaVersion").asText())) {
            for (JsonNode well : value.path("wells")) {
                if (!knownProducer(well.asText(), inspection)) return false;
            }
            for (JsonNode event : value.path("events")) {
                if (!hasKnownDate(inspection.path("scheduleTimeline"), LocalDate.parse(event.path("date").asText()))) return false;
            }
            return true;
        }
        String well = value.path("well").asText();
        boolean knownWell = false;
        for (JsonNode item : inspection.path("scheduleMetadata").path("wells")) {
            if (well.equals(item.path("name").asText())) { knownWell = true; break; }
        }
        if (!knownWell) return false;
        if (PRODUCTION_SCHEMA.equals(value.path("schemaVersion").asText())) {
            for (JsonNode record : inspection.path("scheduleMetadata").path("records")) {
                JsonNode values = record.path("values");
                if ("WCONPROD".equals(record.path("keyword").asText()) && values.isArray() && values.size() >= 4
                        && well.equals(values.get(0).asText())
                        && "ORAT".equalsIgnoreCase(values.get(2).asText())) return true;
            }
            return false;
        }
        if (MULTI_EVENT_SCHEMA.equals(value.path("schemaVersion").asText())) {
            for (JsonNode event : value.path("events")) {
                if (!hasKnownDate(inspection.path("scheduleTimeline"), LocalDate.parse(event.path("date").asText()))) return false;
            }
            return true;
        }
        LocalDate date = LocalDate.parse(value.path("date").asText());
        if (!hasKnownDate(inspection.path("scheduleTimeline"), date)) return false;
        if (SCHEMA.equals(value.path("schemaVersion").asText())) return true;
        if (INJECTION_SCHEMA.equals(value.path("schemaVersion").asText())) {
            for (JsonNode record : inspection.path("scheduleMetadata").path("records")) {
                JsonNode values = record.path("values");
                if ("WCONINJE".equals(record.path("keyword").asText()) && values.isArray() && values.size() >= 5
                        && well.equals(values.get(0).asText())
                        && value.path("injectionType").asText().equalsIgnoreCase(values.get(1).asText())
                        && "RATE".equalsIgnoreCase(values.get(3).asText())) return true;
            }
            return false;
        }
        for (JsonNode record : inspection.path("scheduleMetadata").path("records")) {
            if ("WCONHIST".equals(record.path("keyword").asText())
                    && record.path("values").isArray()
                    && record.path("values").size() > 0
                    && well.equals(record.path("values").get(0).asText())) return true;
        }
        return false;
    }

    private static boolean hasKnownDate(JsonNode timeline, LocalDate date) {
        for (JsonNode event : timeline) {
            if (!"DATES".equals(event.path("kind").asText())) continue;
            for (JsonNode record : event.path("records")) {
                try {
                    if (date.equals(LocalDate.of(record.path("year").asInt(), month(record.path("month").asText()), record.path("day").asInt()))) return true;
                } catch (DateTimeException | IllegalArgumentException ignored) { }
            }
        }
        return false;
    }

    private static boolean validMultiWell(JsonNode value, Set<String> names, boolean multiDate) {
        if (!names.equals(MULTI_WELL_FIELDS) || !value.path("baselineRunId").isIntegralNumber()
                || !value.path("baselineRunId").canConvertToLong() || value.path("baselineRunId").asLong() <= 0
                || !value.path("wells").isArray() || value.path("wells").size() < (multiDate ? 1 : 2) || value.path("wells").size() > 8
                || !validEvents(value.path("events"), multiDate)) return false;
        var unique = new java.util.HashSet<String>();
        for (JsonNode well : value.path("wells")) {
            if (!well.isTextual() || !WELL.matcher(well.asText()).matches() || !unique.add(well.asText())) return false;
        }
        return true;
    }

    private static boolean knownProducer(String well, JsonNode inspection) {
        boolean defined = false;
        for (JsonNode item : inspection.path("scheduleMetadata").path("wells")) {
            if (well.equals(item.path("name").asText())) { defined = true; break; }
        }
        if (!defined) return false;
        for (JsonNode record : inspection.path("scheduleMetadata").path("records")) {
            JsonNode values = record.path("values");
            if (Set.of("WCONHIST", "WCONPROD").contains(record.path("keyword").asText())
                    && values.isArray() && !values.isEmpty() && well.equals(values.get(0).asText())) return true;
        }
        return false;
    }

    private static boolean validEvents(JsonNode events) {
        return validEvents(events, false);
    }

    private static boolean validEvents(JsonNode events, boolean multiDate) {
        if (!events.isArray() || events.size() < 2 || events.size() > (multiDate ? 24 : 2)) return false;
        var parsed = new java.util.ArrayList<LocalDate>(events.size());
        for (JsonNode event : events) {
            if (!event.isObject()) return false;
            var fields = new java.util.HashSet<String>();
            event.properties().forEach(field -> fields.add(field.getKey()));
            String date = event.path("date").asText(null);
            String status = event.path("status").asText(null);
            if (!fields.equals(EVENT_FIELDS) || date == null || status == null || !DATE.matcher(date).matches()
                    || !Set.of("SHUT", "OPEN").contains(status)) return false;
            try {
                LocalDate next = LocalDate.parse(date);
                if (!parsed.isEmpty() && !parsed.getLast().isBefore(next)) return false;
                parsed.add(next);
            } catch (DateTimeException exception) { return false; }
        }
        return multiDate || ("SHUT".equals(events.get(0).path("status").asText())
                && "OPEN".equals(events.get(1).path("status").asText())
                && parsed.get(0).isBefore(parsed.get(1)));
    }

    public static int month(String value) {
        return switch (value.toUpperCase(java.util.Locale.ROOT)) {
            case "JAN" -> 1; case "FEB" -> 2; case "MAR" -> 3; case "APR" -> 4;
            case "MAY" -> 5; case "JUN" -> 6; case "JUL" -> 7; case "AUG" -> 8;
            case "SEP" -> 9; case "OCT" -> 10; case "NOV" -> 11; case "DEC" -> 12;
            default -> 0;
        };
    }
}
