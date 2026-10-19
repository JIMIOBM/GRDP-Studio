package com.grdp.studio.softwareintegration.support;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** An initial GAS injector BHP upper limit in the qualified compositional source. */
public final class EclipseCompositionalInjectionPressureParameters {
    public static final String SCHEMA = "eclipse-compositional-injection-pressure-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "well", "sourceFile", "sourceSha256", "lineNumber", "unit", "originalBhpUpperLimit", "targetBhpUpperLimit");
    private EclipseCompositionalInjectionPressureParameters() {}
    private static boolean integer(JsonNode n, long min, long max) { return n.isIntegralNumber() && n.canConvertToLong() && n.asLong() >= min && n.asLong() <= max; }
    private static boolean rate(JsonNode n) { return n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble() > 0 && n.asDouble() <= 100_000_000; }
    public static boolean valid(JsonNode p) {
        if (p == null || !p.isObject() || p.size() != FIELDS.size()) return false;
        var keys = new HashSet<String>(); p.properties().forEach(v -> keys.add(v.getKey()));
        return keys.equals(FIELDS) && SCHEMA.equals(p.path("schemaVersion").asText()) && integer(p.path("baselineRunId"), 1, Long.MAX_VALUE)
            && p.path("well").isTextual() && p.path("well").asText().matches("[A-Z0-9][A-Z0-9:_ .-]{0,7}") && "psia".equals(p.path("unit").asText())
            && p.path("sourceFile").isTextual() && p.path("sourceFile").asText().matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]")
            && p.path("sourceSha256").isTextual() && p.path("sourceSha256").asText().matches("[a-f0-9]{64}")
            && integer(p.path("lineNumber"), 1, Integer.MAX_VALUE) && rate(p.path("originalBhpUpperLimit")) && rate(p.path("targetBhpUpperLimit"))
            && p.path("originalBhpUpperLimit").asDouble() != p.path("targetBhpUpperLimit").asDouble();
    }
    public static boolean matchesInspection(JsonNode p, JsonNode i) {
        if (!valid(p) || i == null || !EclipseCompositionalReinjectionParameters.validContext(i.path("compositionalReinjectionContext"), i)) return false;
        var c = i.path("compositionalReinjectionContext"); var r = c.path("records").get(0);
        return p.path("sourceFile").equals(c.path("sourceFile")) && p.path("sourceSha256").equals(c.path("sourceSha256"))
            && p.path("well").equals(r.path("injectionWell"))
            && p.path("lineNumber").asInt() == r.path("injectionLineNumber").asInt() && p.path("originalBhpUpperLimit").asDouble() == r.path("injectionBhpUpperLimit").asDouble();
    }
}
