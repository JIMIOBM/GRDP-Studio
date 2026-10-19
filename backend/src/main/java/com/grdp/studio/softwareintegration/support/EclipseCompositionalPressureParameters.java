package com.grdp.studio.softwareintegration.support;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Existing initial producer BHP lower limit in a qualified E300 compositional input. */
public final class EclipseCompositionalPressureParameters {
    public static final String SCHEMA = "eclipse-compositional-pressure-parameters/1";
    private static final Set<String> PARAMETERS = Set.of("schemaVersion", "baselineRunId", "well", "sourceFile", "sourceSha256", "lineNumber", "unit", "originalBhpLowerLimit", "targetBhpLowerLimit");
    private EclipseCompositionalPressureParameters() {}
    private static boolean shape(JsonNode n, Set<String> fields) {
        if (n == null || !n.isObject() || n.size() != fields.size()) return false;
        var names = new HashSet<String>(); n.properties().forEach(p -> names.add(p.getKey())); return names.equals(fields);
    }
    private static boolean integer(JsonNode n, long min, long max) { return n.isIntegralNumber() && n.canConvertToLong() && n.asLong() >= min && n.asLong() <= max; }
    private static boolean positive(JsonNode n) { return n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble() > 0 && n.asDouble() <= 100_000_000; }
    private static boolean name(JsonNode n) { return n.isTextual() && n.asText().matches("[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}"); }
    private static boolean source(JsonNode n) { return n.isTextual() && n.asText().matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]"); }
    private static boolean hash(JsonNode n) { return n.isTextual() && n.asText().matches("[a-f0-9]{64}"); }
    public static boolean valid(JsonNode n) {
        return shape(n, PARAMETERS) && SCHEMA.equals(n.path("schemaVersion").asText()) && integer(n.path("baselineRunId"), 1, Long.MAX_VALUE)
            && name(n.path("well")) && source(n.path("sourceFile")) && hash(n.path("sourceSha256")) && integer(n.path("lineNumber"), 1, Integer.MAX_VALUE)
            && Set.of("psia", "barsa").contains(n.path("unit").asText()) && positive(n.path("originalBhpLowerLimit")) && positive(n.path("targetBhpLowerLimit"))
            && n.path("originalBhpLowerLimit").asDouble() != n.path("targetBhpLowerLimit").asDouble();
    }
    public static boolean matchesInspection(JsonNode p, JsonNode inspection) {
        if (!valid(p) || inspection == null || !EclipseCompositionalProductionParameters.validContext(inspection.path("compositionalProductionContext"), inspection)) return false;
        var c = inspection.path("compositionalProductionContext");
        if (!p.path("sourceFile").equals(c.path("sourceFile")) || !p.path("sourceSha256").equals(c.path("sourceSha256"))) return false;
        String unit = "FIELD".equals(inspection.path("unitSystem").asText()) ? "psia" : "barsa";
        int matches = 0;
        for (var r : c.path("records")) if (p.path("well").equals(r.path("well")) && p.path("lineNumber").asInt() == r.path("bhpLineNumber").asInt()
            && r.path("bhpLineNumber").asInt() > r.path("lineNumber").asInt() && unit.equals(p.path("unit").asText()) && p.path("originalBhpLowerLimit").asDouble() == r.path("bhpLimit").asDouble()) matches++;
        return matches == 1;
    }
}
