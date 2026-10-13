package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class EclipseGroupGasInjectionParameters {
    public static final String SCHEMA = "eclipse-group-gas-injection-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "group", "phase", "sourceFile", "lineNumber", "controlMode", "injectionPhase", "unit", "originalTargetRate", "targetRate", "members");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$");
    private static final Pattern FILE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$");
    private EclipseGroupGasInjectionParameters() {}
    private static boolean rate(JsonNode n) { return n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble() > 0 && n.asDouble() <= 100_000_000; }
    public static boolean valid(JsonNode p) {
        if (p == null || !p.isObject() || p.size() != FIELDS.size()) return false;
        var names = new HashSet<String>(); p.properties().forEach(f -> names.add(f.getKey()));
        if (!names.equals(FIELDS) || !SCHEMA.equals(p.path("schemaVersion").asText()) || !"INITIAL".equals(p.path("phase").asText())
            || !Set.of("GAS").contains(p.path("injectionPhase").asText())
            || !"RATE".equals(p.path("controlMode").asText()) || !Set.of("Mscf/day", "sm3/day").contains(p.path("unit").asText())
            || !p.path("baselineRunId").isIntegralNumber() || !p.path("baselineRunId").canConvertToLong() || p.path("baselineRunId").asLong() <= 0
            || !p.path("lineNumber").isIntegralNumber() || !p.path("lineNumber").canConvertToInt() || p.path("lineNumber").asInt() <= 0
            || !p.path("group").isTextual() || !NAME.matcher(p.path("group").asText()).matches() || "FIELD".equals(p.path("group").asText())
            || !p.path("sourceFile").isTextual() || !FILE.matcher(p.path("sourceFile").asText()).matches()
            || !rate(p.path("originalTargetRate")) || !rate(p.path("targetRate")) || p.path("originalTargetRate").asDouble() == p.path("targetRate").asDouble()
            || !p.path("members").isArray() || p.path("members").size() < 2 || p.path("members").size() > 1000) return false;
        var members = new HashSet<String>();
        for (var m : p.path("members")) if (!m.isTextual() || !NAME.matcher(m.asText()).matches() || !members.add(m.asText())) return false;
        return true;
    }
    public static boolean matchesInspection(JsonNode p, JsonNode i) {
        if (!valid(p) || i == null || !"eclipse-data-inspection/8".equals(i.path("schemaVersion").asText())
            || !Set.of("FIELD", "METRIC").contains(i.path("unitSystem").asText()) || !i.path("packageFiles").isArray() || i.path("packageFiles").size() != 1
            || !p.path("sourceFile").asText().equals(i.path("caseName").asText())
            || !p.path("sourceFile").asText().equals(i.path("packageFiles").get(0).path("relativePath").asText())) return false;
        int matches = 0;
        for (var g : i.path("gasInjectionGroupControls")) {
            if (!g.path("group").asText().equals(p.path("group").asText())) continue;
            if (!g.path("sourceFile").equals(p.path("sourceFile")) || !g.path("lineNumber").equals(p.path("lineNumber"))
                || !g.path("injectionPhase").equals(p.path("injectionPhase")) || !g.path("controlMode").equals(p.path("controlMode")) || !g.path("unit").equals(p.path("unit"))
                || g.path("originalTargetRate").asDouble() != p.path("originalTargetRate").asDouble()
                || g.path("members").size() != p.path("members").size()) return false;
            for (int n = 0; n < g.path("members").size(); n++) {
                String well = g.path("members").get(n).path("well").asText();
                if (!well.equals(p.path("members").get(n).asText())) return false;
                long count = java.util.stream.StreamSupport.stream(i.path("scheduleMetadata").path("wells").spliterator(), false)
                    .filter(w -> well.equals(w.path("name").asText()) && p.path("group").asText().equals(w.path("group").asText())).count();
                if (count != 1) return false;
            }
            matches++;
        }
        return matches == 1;
    }
}
