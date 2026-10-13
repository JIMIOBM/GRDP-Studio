package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class EclipseAdvancedInjectionParameters {
    public static final String SCHEMA = "eclipse-advanced-injection-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "group", "phase", "sourceFile", "lineNumber", "controlMode", "injectionPhase", "unit", "originalTargetRate", "targetRate", "members", "referenceGroup", "referenceMembers");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$");
    private static final Pattern FILE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$");
    private EclipseAdvancedInjectionParameters() {}
    public static boolean supported(String phase, String mode) {
        return "OIL".equals(phase) && "RATE".equals(mode) || "GAS".equals(phase) && Set.of("RESV", "REIN", "VREP").contains(mode);
    }
    public static String unit(String system, String phase, String mode) {
        if (!supported(phase, mode) || !Set.of("FIELD", "METRIC").contains(system)) return null;
        if (ratio(mode)) return "fraction";
        if ("RESV".equals(mode)) return "FIELD".equals(system) ? "RB/day" : "rm3/day";
        return "FIELD".equals(system) ? "STB/day" : "sm3/day";
    }
    private static boolean ratio(String mode) { return Set.of("REIN", "VREP").contains(mode); }
    private static boolean name(JsonNode n) { return n.isTextual() && NAME.matcher(n.asText()).matches(); }
    private static boolean rate(JsonNode n) { return n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble() > 0 && n.asDouble() <= 100_000_000; }
    private static boolean names(JsonNode a, int minimum) {
        if (!a.isArray() || a.size() < minimum || a.size() > 1000) return false;
        var seen = new HashSet<String>();
        for (var n : a) if (!name(n) || !seen.add(n.asText())) return false;
        return true;
    }
    public static boolean valid(JsonNode p) {
        if (p == null || !p.isObject() || p.size() != FIELDS.size()) return false;
        var fields = new HashSet<String>(); p.properties().forEach(f -> fields.add(f.getKey()));
        String mode = p.path("controlMode").asText(), phase = p.path("injectionPhase").asText();
        if (!fields.equals(FIELDS) || !SCHEMA.equals(p.path("schemaVersion").asText()) || !"INITIAL".equals(p.path("phase").asText())
            || !supported(phase, mode) || !(p.path("unit").asText().equals(unit("FIELD", phase, mode)) || p.path("unit").asText().equals(unit("METRIC", phase, mode)))
            || !p.path("baselineRunId").isIntegralNumber() || !p.path("baselineRunId").canConvertToLong() || p.path("baselineRunId").asLong() <= 0
            || !p.path("lineNumber").isIntegralNumber() || !p.path("lineNumber").canConvertToInt() || p.path("lineNumber").asInt() <= 0
            || !name(p.path("group")) || "FIELD".equals(p.path("group").asText())
            || !p.path("sourceFile").isTextual() || !FILE.matcher(p.path("sourceFile").asText()).matches()
            || !rate(p.path("originalTargetRate")) || !rate(p.path("targetRate")) || p.path("originalTargetRate").asDouble() == p.path("targetRate").asDouble()
            || !names(p.path("members"), 2)) return false;
        if (ratio(mode)) return name(p.path("referenceGroup")) && !"FIELD".equals(p.path("referenceGroup").asText())
            && !p.path("group").equals(p.path("referenceGroup")) && names(p.path("referenceMembers"), 1);
        return p.path("referenceGroup").isNull() && p.path("referenceMembers").isArray() && p.path("referenceMembers").isEmpty();
    }
    public static boolean matchesInspection(JsonNode p, JsonNode i) {
        if (!valid(p) || i == null || !"eclipse-data-inspection/11".equals(i.path("schemaVersion").asText())
            || !Set.of("FIELD", "METRIC").contains(i.path("unitSystem").asText()) || i.path("packageFiles").size() != 1
            || !p.path("sourceFile").equals(i.path("caseName")) || !p.path("sourceFile").equals(i.path("packageFiles").get(0).path("relativePath"))) return false;
        int matched = 0;
        for (var g : i.path("advancedInjectionGroupControls")) {
            if (!g.path("group").equals(p.path("group"))) continue;
            for (String field : new String[]{"sourceFile", "lineNumber", "controlMode", "injectionPhase", "unit", "referenceGroup", "referenceMembers"})
                if (!g.path(field).equals(p.path(field))) return false;
            if (g.path("originalTargetRate").asDouble() != p.path("originalTargetRate").asDouble() || g.path("members").size() != p.path("members").size()) return false;
            if (!p.path("unit").asText().equals(unit(i.path("unitSystem").asText(), p.path("injectionPhase").asText(), p.path("controlMode").asText()))) return false;
            for (int n = 0; n < g.path("members").size(); n++) if (!g.path("members").get(n).path("well").equals(p.path("members").get(n))) return false;
            for (String pair : new String[]{"members", "referenceMembers"}) {
                String group = p.path(pair.equals("members") ? "group" : "referenceGroup").asText();
                int count = 0;
                for (var w : i.path("scheduleMetadata").path("wells")) if (group.equals(w.path("group").asText())) count++;
                if (count != p.path(pair).size()) return false;
                for (var member : p.path(pair)) {
                    int found = 0;
                    for (var w : i.path("scheduleMetadata").path("wells")) if (member.equals(w.path("name")) && group.equals(w.path("group").asText())) found++;
                    if (found != 1) return false;
                }
            }
            matched++;
        }
        return matched == 1;
    }
}
