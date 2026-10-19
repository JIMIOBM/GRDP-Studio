package com.grdp.studio.softwareintegration.support;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** A constant FIELD gas sales requirement in the qualified compositional source. */
public final class EclipseCompositionalGasSalesParameters {
    public static final String SCHEMA = "eclipse-compositional-gas-sales-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "group", "phase", "sourceFile", "sourceSha256", "lineNumber", "unit", "originalSalesTarget", "targetSalesTarget");
    private EclipseCompositionalGasSalesParameters() {}
    private static boolean integer(JsonNode n, long min, long max) { return n.isIntegralNumber() && n.canConvertToLong() && n.asLong() >= min && n.asLong() <= max; }
    private static boolean rate(JsonNode n) { return n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble() >= 0 && n.asDouble() <= 100_000_000; }
    public static boolean valid(JsonNode p) {
        if (p == null || !p.isObject() || p.size() != FIELDS.size()) return false;
        var keys = new HashSet<String>(); p.properties().forEach(v -> keys.add(v.getKey()));
        return keys.equals(FIELDS) && SCHEMA.equals(p.path("schemaVersion").asText()) && integer(p.path("baselineRunId"), 1, Long.MAX_VALUE)
            && "FIELD".equals(p.path("group").asText()) && "GAS".equals(p.path("phase").asText()) && "Mscf/day".equals(p.path("unit").asText())
            && p.path("sourceFile").isTextual() && p.path("sourceFile").asText().matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]")
            && p.path("sourceSha256").isTextual() && p.path("sourceSha256").asText().matches("[a-f0-9]{64}")
            && integer(p.path("lineNumber"), 1, Integer.MAX_VALUE) && rate(p.path("originalSalesTarget")) && rate(p.path("targetSalesTarget"))
            && p.path("originalSalesTarget").asDouble() != p.path("targetSalesTarget").asDouble();
    }
    public static boolean matchesInspection(JsonNode p, JsonNode i) {
        if (!valid(p) || i == null || !EclipseCompositionalReinjectionParameters.validContext(i.path("compositionalReinjectionContext"), i)) return false;
        var c = i.path("compositionalReinjectionContext"); var r = c.path("records").get(0);
        return p.path("sourceFile").equals(c.path("sourceFile")) && p.path("sourceSha256").equals(c.path("sourceSha256"))
            && p.path("group").equals(r.path("group")) && p.path("phase").equals(r.path("phase"))
            && p.path("lineNumber").asInt() == r.path("salesLineNumber").asInt() && p.path("originalSalesTarget").asDouble() == r.path("salesTarget").asDouble();
    }
}
