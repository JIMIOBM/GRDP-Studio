package com.grdp.studio.softwareintegration.support;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Existing FIELD available-vapor reinjection fraction with fixed sales and an explicit shut schedule. */
public final class EclipseCompositionalReinjectionParameters {
    public static final String SCHEMA = "eclipse-compositional-reinjection-parameters/1";
    private static final Set<String> PARAMETERS = Set.of("schemaVersion", "baselineRunId", "group", "phase", "sourceFile", "sourceSha256", "lineNumber", "unit", "originalFraction", "targetFraction");
    private static final Set<String> CONTEXT = Set.of("schemaVersion", "sourceFile", "sourceSha256", "sourceSizeBytes", "solver", "componentCount", "componentNames", "equationOfState", "records");
    private static final Set<String> RECORD = Set.of("group", "phase", "controlMode", "lineNumber", "originalFraction", "gasSourceKind", "gasSourceGroup", "gasSourceLineNumber",
        "salesTarget", "salesLineNumber", "rateUnit", "pressureUnit", "injectionWell", "injectionBhpUpperLimit", "injectionLineNumber", "productionWell", "productionGasTarget", "productionBhpLowerLimit", "shutAfterDays", "shutLineNumber");
    private EclipseCompositionalReinjectionParameters() {}
    private static boolean shape(JsonNode n, Set<String> fields) {
        if (n == null || !n.isObject() || n.size() != fields.size()) return false;
        var keys = new HashSet<String>(); n.properties().forEach(v -> keys.add(v.getKey())); return keys.equals(fields);
    }
    private static boolean integer(JsonNode n, long min, long max) { return n.isIntegralNumber() && n.canConvertToLong() && n.asLong() >= min && n.asLong() <= max; }
    private static boolean number(JsonNode n, double min, double max) { return n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble() >= min && n.asDouble() <= max; }
    private static boolean positive(JsonNode n) { return number(n, Double.MIN_VALUE, 100_000_000); }
    private static boolean source(JsonNode n) { return n.isTextual() && n.asText().matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]"); }
    private static boolean hash(JsonNode n) { return n.isTextual() && n.asText().matches("[a-f0-9]{64}"); }
    public static boolean valid(JsonNode p) {
        return shape(p, PARAMETERS) && SCHEMA.equals(p.path("schemaVersion").asText()) && integer(p.path("baselineRunId"), 1, Long.MAX_VALUE)
            && "FIELD".equals(p.path("group").asText()) && "GAS".equals(p.path("phase").asText()) && "fraction".equals(p.path("unit").asText())
            && source(p.path("sourceFile")) && hash(p.path("sourceSha256")) && integer(p.path("lineNumber"), 1, Integer.MAX_VALUE)
            && number(p.path("originalFraction"), 0, 1) && number(p.path("targetFraction"), 0, 1) && p.path("originalFraction").asDouble() != p.path("targetFraction").asDouble();
    }
    public static boolean validContext(JsonNode c, JsonNode i) {
        if (!shape(c, CONTEXT) || i == null || !Set.of("eclipse-data-inspection/16", "eclipse-data-inspection/17", "eclipse-data-inspection/19", "eclipse-data-inspection/20", "eclipse-data-inspection/21", "eclipse-data-inspection/22").contains(i.path("schemaVersion").asText())
            || !"eclipse-compositional-reinjection-context/1".equals(c.path("schemaVersion").asText()) || !"FIELD".equals(i.path("unitSystem").asText())
            || !EclipseCompositionalProductionParameters.validContext(i.path("compositionalProductionContext"), i)
            || i.path("compositionalProductionContext").path("records").size() != 1 || !i.path("wellNames").isArray() || i.path("wellNames").size() != 2
            || !source(c.path("sourceFile")) || !hash(c.path("sourceSha256")) || !integer(c.path("sourceSizeBytes"), 1, 4 * 1024 * 1024)
            || !integer(c.path("solver"), 300, 300) || !integer(c.path("componentCount"), 9, 9) || !"PR".equals(c.path("equationOfState").asText())
            || !c.path("componentNames").equals(i.path("simulation").path("componentNames"))
            || !c.path("records").isArray() || c.path("records").size() != 1) return false;
        var producerContext = i.path("compositionalProductionContext");
        if (!c.path("sourceFile").equals(producerContext.path("sourceFile")) || !c.path("sourceSha256").equals(producerContext.path("sourceSha256"))
            || c.path("sourceSizeBytes").asLong() != producerContext.path("sourceSizeBytes").asLong()) return false;
        var r = c.path("records").get(0); var p = producerContext.path("records").get(0);
        if (!shape(r, RECORD) || !"FIELD".equals(r.path("group").asText()) || !"GAS".equals(r.path("phase").asText()) || !"REIN".equals(r.path("controlMode").asText())
            || !number(r.path("originalFraction"), 0, 1) || !"GV".equals(r.path("gasSourceKind").asText()) || !"FIELD".equals(r.path("gasSourceGroup").asText())
            || !number(r.path("salesTarget"), 0, 100_000_000) || !"Mscf/day".equals(r.path("rateUnit").asText()) || !"psia".equals(r.path("pressureUnit").asText())
            || !r.path("injectionWell").isTextual() || !r.path("injectionWell").asText().matches("[A-Z0-9][A-Z0-9:_ .-]{0,7}")
            || !r.path("productionWell").equals(p.path("well")) || !r.path("productionWell").asText().equals(r.path("productionWell").asText().toUpperCase(java.util.Locale.ROOT))
            || r.path("injectionWell").equals(r.path("productionWell")) || !positive(r.path("injectionBhpUpperLimit"))
            || !positive(r.path("productionGasTarget")) || !positive(r.path("productionBhpLowerLimit"))
            || r.path("productionGasTarget").asDouble() != p.path("originalGasRate").asDouble() || r.path("productionBhpLowerLimit").asDouble() != p.path("bhpLimit").asDouble()
            || !positive(r.path("shutAfterDays")) || r.path("shutAfterDays").asDouble() > 10_000_000) return false;
        var wells = new HashSet<String>(); i.path("wellNames").forEach(w -> wells.add(w.asText()));
        if (wells.size() != 2 || !wells.equals(Set.of(r.path("injectionWell").asText(), r.path("productionWell").asText()))) return false;
        var lines = new HashSet<Integer>(); int lastInitial = Math.max(p.path("lineNumber").asInt(), p.path("bhpLineNumber").asInt());
        for (String key : new String[]{"lineNumber", "gasSourceLineNumber", "salesLineNumber", "injectionLineNumber"}) {
            if (!integer(r.path(key), 1, c.path("sourceSizeBytes").asLong()) || !lines.add(r.path(key).asInt())) return false;
            lastInitial = Math.max(lastInitial, r.path(key).asInt());
        }
        if (!integer(r.path("shutLineNumber"), lastInitial + 1L, c.path("sourceSizeBytes").asLong())) return false;
        return timeline(i.path("scheduleTimeline"), c.path("sourceFile").asText(), c.path("sourceSizeBytes").asLong(), lastInitial, r.path("shutLineNumber").asInt(), r.path("shutAfterDays").asDouble());
    }
    private static boolean timeline(JsonNode events, String file, long size, int initial, int shutLine, double shutDays) {
        if (!events.isArray() || events.isEmpty()) return false;
        double elapsed = 0, before = 0; int previous = initial; boolean after = false;
        for (var event : events) {
            if (!shape(event, Set.of("kind", "steps", "sourceFile", "lineNumber")) || !"TSTEP".equals(event.path("kind").asText())
                || !file.equals(event.path("sourceFile").asText()) || !integer(event.path("lineNumber"), previous + 1L, size)
                || !event.path("steps").isArray() || event.path("steps").isEmpty()) return false;
            previous = event.path("lineNumber").asInt();
            for (var step : event.path("steps")) {
                if (!step.isTextual() || !step.asText().matches("[+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[Ee][+-]?[0-9]+)?")) return false;
                double value;
                try { value = Double.parseDouble(step.asText()); } catch (NumberFormatException e) { return false; }
                if (!Double.isFinite(value) || value <= 0 || value > 100_000_000) return false;
                elapsed += value;
            }
            if (previous < shutLine) before = elapsed; else after = true;
            if (!Double.isFinite(elapsed) || elapsed > 10_000_000) return false;
        }
        return after && before == shutDays && elapsed > shutDays;
    }
    public static boolean matchesInspection(JsonNode p, JsonNode i) {
        if (!valid(p) || i == null || !validContext(i.path("compositionalReinjectionContext"), i)) return false;
        var c = i.path("compositionalReinjectionContext"); var r = c.path("records").get(0);
        return p.path("sourceFile").equals(c.path("sourceFile")) && p.path("sourceSha256").equals(c.path("sourceSha256"))
            && p.path("group").equals(r.path("group")) && p.path("phase").equals(r.path("phase"))
            && p.path("lineNumber").asInt() == r.path("lineNumber").asInt() && p.path("originalFraction").asDouble() == r.path("originalFraction").asDouble();
    }
}
