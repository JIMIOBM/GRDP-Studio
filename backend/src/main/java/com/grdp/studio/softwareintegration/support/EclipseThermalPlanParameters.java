package com.grdp.studio.softwareintegration.support;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** A complete thermal injection schedule anchored to unchanged original report times. */
public final class EclipseThermalPlanParameters {
    public static final String SCHEMA = "eclipse-thermal-injection-plan-parameters/1";
    private static final Set<String> VALUES = Set.of("surfaceInjectionRate", "injectionTemperature", "steamQuality", "bhpUpperLimit");
    private EclipseThermalPlanParameters() {}
    private static boolean shape(JsonNode n, Set<String> fields) {
        if (n == null || !n.isObject() || n.size() != fields.size()) return false;
        var names = new HashSet<String>(); n.properties().forEach(p -> names.add(p.getKey())); return names.equals(fields);
    }
    private static boolean integer(JsonNode n, long min, long max) {
        return n.isIntegralNumber() && n.canConvertToLong() && n.asLong() >= min && n.asLong() <= max;
    }
    private static boolean text(JsonNode n, String pattern) { return n.isTextual() && n.asText().matches(pattern); }
    private static boolean values(JsonNode v, boolean field) {
        for (var k : VALUES) if (!v.path(k).isNumber() || !Double.isFinite(v.path(k).asDouble())) return false;
        return v.path("surfaceInjectionRate").asDouble() > 0 && v.path("surfaceInjectionRate").asDouble() <= 1e8
            && v.path("bhpUpperLimit").asDouble() > 0 && v.path("bhpUpperLimit").asDouble() <= 1e8
            && v.path("steamQuality").asDouble() >= 0 && v.path("steamQuality").asDouble() <= 1
            && v.path("injectionTemperature").asDouble() > (field ? -459.67 : -273.15) && v.path("injectionTemperature").asDouble() <= 1e6;
    }
    private static boolean same(JsonNode a, JsonNode b) {
        return VALUES.stream().allMatch(k -> a.path(k).asDouble() == b.path(k).asDouble());
    }
    public static boolean valid(JsonNode p) {
        if (!shape(p, Set.of("schemaVersion", "baselineRunId", "well", "sourceFile", "sourceSha256", "lineNumber", "controlLineNumber",
                "temperatureUnit", "rateUnit", "pressureUnit", "original", "stages"))
            || !SCHEMA.equals(p.path("schemaVersion").asText()) || !integer(p.path("baselineRunId"), 1, Long.MAX_VALUE)
            || !integer(p.path("lineNumber"), 1, Integer.MAX_VALUE) || !integer(p.path("controlLineNumber"), 1, p.path("lineNumber").asLong()-1)
            || !text(p.path("well"), "[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}")
            || !text(p.path("sourceFile"), "[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]")
            || !text(p.path("sourceSha256"), "[a-f0-9]{64}") || !shape(p.path("original"), VALUES)
            || !p.path("stages").isArray() || p.path("stages").size() < 2 || p.path("stages").size() > 8) return false;
        boolean field = "degF".equals(p.path("temperatureUnit").asText());
        if (!(field && "STB/day".equals(p.path("rateUnit").asText()) && "psia".equals(p.path("pressureUnit").asText())
            || "degC".equals(p.path("temperatureUnit").asText()) && "sm3/day".equals(p.path("rateUnit").asText()) && "barsa".equals(p.path("pressureUnit").asText()))
            || !values(p.path("original"), field)) return false;
        var stageFields = new HashSet<>(VALUES); stageFields.add("startTimeDays");
        int day = 0; var previous = p.path("original");
        for (var s : p.path("stages")) {
            if (!shape(s, stageFields) || !integer(s.path("startTimeDays"),day+1L,1_000_000_000) || !values(s,field) || same(s,previous)) return false;
            day = s.path("startTimeDays").asInt(); previous = s;
        }
        return true;
    }
    public static boolean matchesInspection(JsonNode p, JsonNode i) {
        if (!valid(p) || i == null || !EclipseThermalInjectionParameters.validContext(i.path("thermalInjectionContext"),i)) return false;
        var c = i.path("thermalInjectionContext");
        if (!p.path("sourceFile").equals(c.path("sourceFile")) || !p.path("sourceSha256").equals(c.path("sourceSha256"))) return false;
        var days = EclipseThermalInjectionParameters.stageDays(i);
        for (var s : p.path("stages")) if (!days.contains(s.path("startTimeDays").asInt())) return false;
        int matches = 0;
        for (var r : c.path("records")) {
            if (p.path("well").equals(r.path("well")) && p.path("lineNumber").equals(r.path("lineNumber"))
                && p.path("controlLineNumber").equals(r.path("controlLineNumber")) && p.path("temperatureUnit").equals(r.path("temperatureUnit"))
                && p.path("rateUnit").equals(r.path("rateUnit")) && p.path("original").path("surfaceInjectionRate").asDouble() == r.path("surfaceInjectionRate").asDouble()
                && p.path("original").path("injectionTemperature").asDouble() == r.path("originalTemperature").asDouble()
                && p.path("original").path("steamQuality").asDouble() == r.path("originalSteamQuality").asDouble()
                && p.path("original").path("bhpUpperLimit").asDouble() == r.path("bhpUpperLimit").asDouble()) matches++;
        }
        return matches == 1;
    }
}
