package com.grdp.studio.softwareintegration.support;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Existing initial gas target in a qualified E300 compositional input. */
public final class EclipseCompositionalProductionParameters {
    public static final String SCHEMA = "eclipse-compositional-production-parameters/1";
    private static final Set<String> PARAMETERS = Set.of("schemaVersion", "baselineRunId", "well", "sourceFile", "sourceSha256", "lineNumber", "unit", "originalGasRate", "targetGasRate");
    private static final Set<String> CONTEXT = Set.of("schemaVersion", "sourceFile", "sourceSha256", "sourceSizeBytes", "solver", "componentCount", "componentNames", "equationOfState", "records");
    private static final Set<String> RECORD = Set.of("well", "lineNumber", "controlMode", "unit", "originalGasRate", "bhpLimit", "bhpLineNumber");
    private EclipseCompositionalProductionParameters() {}
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
            && Set.of("Mscf/day", "sm3/day").contains(n.path("unit").asText()) && positive(n.path("originalGasRate")) && positive(n.path("targetGasRate"))
            && n.path("originalGasRate").asDouble() != n.path("targetGasRate").asDouble();
    }
    public static boolean validContext(JsonNode c, JsonNode inspection) {
        if (!shape(c, CONTEXT) || !"eclipse-compositional-production-context/1".equals(c.path("schemaVersion").asText())
            || inspection == null || !Set.of("eclipse-data-inspection/14", "eclipse-data-inspection/16", "eclipse-data-inspection/17", "eclipse-data-inspection/19", "eclipse-data-inspection/20", "eclipse-data-inspection/21", "eclipse-data-inspection/22").contains(inspection.path("schemaVersion").asText())
            || !EclipseSimulationMetadataValidator.valid(inspection.path("simulation")) || inspection.path("simulation").path("componentCount").asInt() != 9
            || !integer(c.path("solver"), 300, 300) || !integer(c.path("componentCount"), 9, 9) || !"PR".equals(c.path("equationOfState").asText())
            || !c.path("componentNames").equals(inspection.path("simulation").path("componentNames"))
            || !source(c.path("sourceFile")) || !c.path("sourceFile").equals(inspection.path("caseName")) || !hash(c.path("sourceSha256"))
            || !integer(c.path("sourceSizeBytes"), 1, 4 * 1024 * 1024) || !inspection.path("packageFiles").isArray() || inspection.path("packageFiles").size() != 1
            || !c.path("records").isArray() || c.path("records").isEmpty() || c.path("records").size() > 1000 || !inspection.path("wellNames").isArray()) return false;
        var f = inspection.path("packageFiles").get(0);
        if (!f.path("relativePath").equals(c.path("sourceFile")) || !f.path("sha256").equals(c.path("sourceSha256"))
            || !integer(f.path("sizeBytes"), 1, 4 * 1024 * 1024) || f.path("sizeBytes").asLong() != c.path("sourceSizeBytes").asLong()) return false;
        String unit = switch (inspection.path("unitSystem").asText()) { case "FIELD" -> "Mscf/day"; case "METRIC" -> "sm3/day"; default -> ""; };
        if (unit.isEmpty()) return false;
        var wells = new HashSet<String>(); inspection.path("wellNames").forEach(w -> wells.add(w.asText()));
        var seen = new HashSet<String>(); var rows = new HashSet<Integer>();
        for (var r : c.path("records")) {
            if (!shape(r, RECORD) || !name(r.path("well")) || !wells.contains(r.path("well").asText()) || !seen.add(r.path("well").asText())
                || !integer(r.path("lineNumber"), 1, c.path("sourceSizeBytes").asLong()) || !rows.add(r.path("lineNumber").asInt())
                || !integer(r.path("bhpLineNumber"), 1, c.path("sourceSizeBytes").asLong()) || !rows.add(r.path("bhpLineNumber").asInt())
                || !"GRAT".equals(r.path("controlMode").asText()) || !unit.equals(r.path("unit").asText())
                || !positive(r.path("originalGasRate")) || !positive(r.path("bhpLimit"))) return false;
        }
        return true;
    }
    public static boolean matchesInspection(JsonNode p, JsonNode inspection) {
        if (!valid(p) || inspection == null || !validContext(inspection.path("compositionalProductionContext"), inspection)) return false;
        var c = inspection.path("compositionalProductionContext");
        if (!p.path("sourceFile").equals(c.path("sourceFile")) || !p.path("sourceSha256").equals(c.path("sourceSha256"))) return false;
        int matches = 0;
        for (var r : c.path("records")) if (p.path("well").equals(r.path("well")) && p.path("lineNumber").asInt() == r.path("lineNumber").asInt()
            && p.path("unit").equals(r.path("unit")) && p.path("originalGasRate").asDouble() == r.path("originalGasRate").asDouble()) matches++;
        return matches == 1;
    }
}
