package com.grdp.studio.softwareintegration.support;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** One existing WINJTEMP quality or temperature, bound to the complete original thermal input. */
public final class EclipseThermalInjectionParameters {
    public static final String SCHEMA = "eclipse-thermal-injection-parameters/1";
    public static final String CONTROL_SCHEMA = "eclipse-thermal-injection-parameters/2";
    public static final String STAGE_SCHEMA = "eclipse-thermal-injection-parameters/3";
    private static final Set<String> PARAMETERS = Set.of("schemaVersion", "baselineRunId", "well", "sourceFile", "sourceSha256", "lineNumber", "field", "unit", "originalValue", "targetValue");
    private static final Set<String> CONTEXT = Set.of("schemaVersion", "sourceFile", "sourceSha256", "sourceSizeBytes", "solver", "componentCount", "componentNames", "fluidModel", "records");
    private static final Set<String> RECORD = Set.of("well", "lineNumber", "originalSteamQuality", "originalTemperature", "temperatureUnit", "surfaceInjectionRate", "rateUnit", "bhpUpperLimit", "controlLineNumber");
    private EclipseThermalInjectionParameters() {}
    private static boolean shape(JsonNode n, Set<String> fields) {
        if (n == null || !n.isObject() || n.size() != fields.size()) return false;
        var names = new HashSet<String>(); n.properties().forEach(p -> names.add(p.getKey())); return names.equals(fields);
    }
    private static boolean integer(JsonNode n, long min, long max) { return n.isIntegralNumber() && n.canConvertToLong() && n.asLong() >= min && n.asLong() <= max; }
    private static boolean positive(JsonNode n) { return n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble() > 0 && n.asDouble() <= 100_000_000; }
    private static boolean name(JsonNode n) { return n.isTextual() && n.asText().matches("[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}"); }
    private static boolean source(JsonNode n) { return n.isTextual() && n.asText().matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]"); }
    private static boolean hash(JsonNode n) { return n.isTextual() && n.asText().matches("[a-f0-9]{64}"); }
    private static boolean value(String field, String unit, JsonNode n) {
        if (!n.isNumber() || !Double.isFinite(n.asDouble())) return false;
        double v = n.asDouble();
        return switch (field) {
            case "STEAM_QUALITY" -> "fraction".equals(unit) && v >= 0 && v <= 1;
            case "INJECTION_TEMPERATURE" -> ("degF".equals(unit) && v > -459.67 || "degC".equals(unit) && v > -273.15) && v <= 1_000_000;
            case "SURFACE_INJECTION_RATE" -> Set.of("STB/day", "sm3/day").contains(unit) && v > 0 && v <= 100_000_000;
            case "BHP_UPPER_LIMIT" -> Set.of("psia", "barsa").contains(unit) && v > 0 && v <= 100_000_000;
            default -> false;
        };
    }
    public static boolean valid(JsonNode p) {
        var fields = new HashSet<>(PARAMETERS);
        boolean staged = p != null && STAGE_SCHEMA.equals(p.path("schemaVersion").asText());
        if(staged) fields.add("startTimeDays");
        return shape(p, fields) && (!staged || integer(p.path("startTimeDays"),1,1_000_000_000) && Set.of("SURFACE_INJECTION_RATE","INJECTION_TEMPERATURE").contains(p.path("field").asText())) && Set.of(SCHEMA, CONTROL_SCHEMA, STAGE_SCHEMA).contains(p.path("schemaVersion").asText()) && integer(p.path("baselineRunId"), 1, Long.MAX_VALUE)
            && name(p.path("well")) && source(p.path("sourceFile")) && hash(p.path("sourceSha256")) && integer(p.path("lineNumber"), 1, Integer.MAX_VALUE)
            && p.path("field").isTextual() && (!SCHEMA.equals(p.path("schemaVersion").asText()) || Set.of("STEAM_QUALITY", "INJECTION_TEMPERATURE").contains(p.path("field").asText()))
            && p.path("unit").isTextual() && value(p.path("field").asText(), p.path("unit").asText(), p.path("originalValue"))
            && value(p.path("field").asText(), p.path("unit").asText(), p.path("targetValue")) && p.path("originalValue").asDouble() != p.path("targetValue").asDouble();
    }
    public static boolean validContext(JsonNode c, JsonNode i) {
        if (!shape(c, CONTEXT) || !"eclipse-thermal-injection-context/1".equals(c.path("schemaVersion").asText())
            || i == null || !Set.of("eclipse-data-inspection/15","eclipse-data-inspection/23").contains(i.path("schemaVersion").asText()) || !EclipseThermalMetadataValidator.valid(i.path("simulation"))
            || i.path("simulation").path("componentCount").asInt() != 3 || !integer(c.path("solver"), 300, 300) || !integer(c.path("componentCount"), 3, 3)
            || !"LIVEOIL".equals(c.path("fluidModel").asText()) || !c.path("componentNames").equals(i.path("simulation").path("componentNames"))
            || !source(c.path("sourceFile")) || !c.path("sourceFile").equals(i.path("caseName")) || !hash(c.path("sourceSha256")) || !integer(c.path("sourceSizeBytes"), 1, 4 * 1024 * 1024)
            || !i.path("packageFiles").isArray() || i.path("packageFiles").size() != 1 || !i.path("wellNames").isArray()
            || !c.path("records").isArray() || c.path("records").isEmpty() || c.path("records").size() > 1000) return false;
        var f = i.path("packageFiles").get(0);
        if (!f.path("relativePath").equals(c.path("sourceFile")) || !f.path("sha256").equals(c.path("sourceSha256"))
            || !integer(f.path("sizeBytes"), 1, 4 * 1024 * 1024) || f.path("sizeBytes").asLong() != c.path("sourceSizeBytes").asLong()) return false;
        boolean field = "FIELD".equals(i.path("unitSystem").asText());
        if (!field && !"METRIC".equals(i.path("unitSystem").asText())) return false;
        var wells = new HashSet<String>(); i.path("wellNames").forEach(w -> wells.add(w.asText()));
        var names = new HashSet<String>(); var rows = new HashSet<Integer>();
        for (var r : c.path("records")) {
            if (!shape(r, RECORD) || !name(r.path("well")) || !wells.contains(r.path("well").asText()) || !names.add(r.path("well").asText())
                || !integer(r.path("lineNumber"), 1, c.path("sourceSizeBytes").asLong()) || !rows.add(r.path("lineNumber").asInt())
                || !integer(r.path("controlLineNumber"), 1, r.path("lineNumber").asLong() - 1) || !rows.add(r.path("controlLineNumber").asInt())
                || !(field ? "degF" : "degC").equals(r.path("temperatureUnit").asText()) || !(field ? "STB/day" : "sm3/day").equals(r.path("rateUnit").asText())
                || !value("STEAM_QUALITY", "fraction", r.path("originalSteamQuality")) || !value("INJECTION_TEMPERATURE", r.path("temperatureUnit").asText(), r.path("originalTemperature"))
                || !positive(r.path("surfaceInjectionRate")) || !positive(r.path("bhpUpperLimit"))) return false;
        }
        return true;
    }
    public static java.util.List<Integer> stageDays(JsonNode i) {
        var days=new java.util.ArrayList<Integer>();
        if(i==null||!validContext(i.path("thermalInjectionContext"),i)||!i.path("scheduleTimeline").isArray()||i.path("scheduleTimeline").size()!=1)return days;
        var clock=i.path("scheduleTimeline").get(0);var context=i.path("thermalInjectionContext");
        if(!"TSTEP".equals(clock.path("kind").asText())||!clock.path("sourceFile").equals(context.path("sourceFile"))||!clock.path("steps").isArray()||clock.path("steps").size()<2||clock.path("steps").size()>1000)return days;
        long elapsed=0;int index=0;
        for(var step:clock.path("steps")) {
            if(!step.isString()||!step.asText().matches("[1-9][0-9]{0,8}(?:\\.0+)?"))return java.util.List.of();
            elapsed+=(long)Double.parseDouble(step.asText());if(elapsed>1_000_000_000)return java.util.List.of();
            if(++index<clock.path("steps").size())days.add((int)elapsed);
        }
        return days;
    }
    public static boolean matchesInspection(JsonNode p, JsonNode i) {
        if (!valid(p) || i == null || !validContext(i.path("thermalInjectionContext"), i)) return false;
        var c = i.path("thermalInjectionContext");
        if (!p.path("sourceFile").equals(c.path("sourceFile")) || !p.path("sourceSha256").equals(c.path("sourceSha256"))) return false;
        int matches = 0;
        String field=p.path("field").asText(); boolean control=Set.of("SURFACE_INJECTION_RATE","BHP_UPPER_LIMIT").contains(field);
        for (var r : c.path("records")) {
            if (!p.path("well").equals(r.path("well")) || p.path("lineNumber").asInt() != r.path(control?"controlLineNumber":"lineNumber").asInt()) continue;
            boolean matched=switch(field){
                case "STEAM_QUALITY" -> "fraction".equals(p.path("unit").asText()) && p.path("originalValue").asDouble()==r.path("originalSteamQuality").asDouble();
                case "INJECTION_TEMPERATURE" -> p.path("unit").equals(r.path("temperatureUnit")) && p.path("originalValue").asDouble()==r.path("originalTemperature").asDouble();
                case "SURFACE_INJECTION_RATE" -> p.path("unit").equals(r.path("rateUnit")) && p.path("originalValue").asDouble()==r.path("surfaceInjectionRate").asDouble();
                case "BHP_UPPER_LIMIT" -> ("FIELD".equals(i.path("unitSystem").asText())?"psia":"barsa").equals(p.path("unit").asText()) && p.path("originalValue").asDouble()==r.path("bhpUpperLimit").asDouble();
                default -> false;
            };
            if(matched) matches++;
        }
        return matches == 1 && (!STAGE_SCHEMA.equals(p.path("schemaVersion").asText()) || stageDays(i).contains(p.path("startTimeDays").asInt()));
    }
}
