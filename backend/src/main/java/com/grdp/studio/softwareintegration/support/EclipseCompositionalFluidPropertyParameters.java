package com.grdp.studio.softwareintegration.support;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** One explicit component PCRIT value in the qualified single-EoS compositional source. */
public final class EclipseCompositionalFluidPropertyParameters {
    public static final String SCHEMA = "eclipse-compositional-fluid-property-parameters/1";
    public static final String TEMPERATURE_SCHEMA = "eclipse-compositional-temperature-parameters/1";
    public static final String CRITICAL_Z_SCHEMA = "eclipse-compositional-critical-z-parameters/1";
    public static final String ACENTRIC_FACTOR_SCHEMA = "eclipse-compositional-acentric-factor-parameters/1";
    public static final String EOS_PROPERTY_SCHEMA = "eclipse-compositional-eos-property-parameters/1";
    private static final java.util.List<String> EOS_FIELDS = java.util.List.of("MW", "OMEGAA", "OMEGAB");
    private static String propertyUnit(String field) { return "PCRIT".equals(field) ? "psia" : "TCRIT".equals(field) ? "degR" : "MW".equals(field) ? "lb/lb-M" : "dimensionless"; }
    private static final Set<String> PARAMETERS = Set.of("schemaVersion", "baselineRunId", "sourceFile", "sourceSha256", "lineNumber", "field", "componentIndex", "componentName", "unit", "originalValue", "targetValue");
    private static final Set<String> CONTEXT = Set.of("schemaVersion", "sourceFile", "sourceSha256", "sourceSizeBytes", "solver", "componentCount", "componentNames", "equationOfState", "records");
    private static final Set<String> RECORD = Set.of("componentIndex", "componentName", "field", "lineNumber", "unit", "originalValue");
    private EclipseCompositionalFluidPropertyParameters() {}
    private static boolean shape(JsonNode n, Set<String> fields) {
        if (n == null || !n.isObject() || n.size() != fields.size()) return false;
        var keys = new HashSet<String>(); n.properties().forEach(p -> keys.add(p.getKey())); return keys.equals(fields);
    }
    private static boolean integer(JsonNode n, long min, long max) { return n.isIntegralNumber() && n.canConvertToLong() && n.asLong() >= min && n.asLong() <= max; }
    private static boolean positive(JsonNode n) { return n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble() > 0 && n.asDouble() <= 100_000_000; }
    private static boolean propertyValue(JsonNode n, boolean signed) { return signed ? n.isNumber() && Double.isFinite(n.asDouble()) && Math.abs(n.asDouble()) <= 100_000_000 : positive(n); }
    private static boolean name(JsonNode n) { return n.isTextual() && n.asText().matches("[A-Za-z0-9][A-Za-z0-9+_.-]{0,15}"); }
    private static boolean source(JsonNode n) { return n.isTextual() && n.asText().matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]"); }
    private static boolean hash(JsonNode n) { return n.isTextual() && n.asText().matches("[a-f0-9]{64}"); }
    public static boolean valid(JsonNode p) {
        boolean eos = p != null && EOS_PROPERTY_SCHEMA.equals(p.path("schemaVersion").asText());
        boolean acentric = p != null && ACENTRIC_FACTOR_SCHEMA.equals(p.path("schemaVersion").asText());
        boolean temperature = p != null && TEMPERATURE_SCHEMA.equals(p.path("schemaVersion").asText());
        boolean criticalZ = p != null && CRITICAL_Z_SCHEMA.equals(p.path("schemaVersion").asText());
        return shape(p, PARAMETERS) && (eos || acentric || temperature || criticalZ || SCHEMA.equals(p.path("schemaVersion").asText())) && integer(p.path("baselineRunId"), 1, Long.MAX_VALUE)
            && source(p.path("sourceFile")) && hash(p.path("sourceSha256")) && integer(p.path("lineNumber"), 1, Integer.MAX_VALUE)
            && (eos ? EOS_FIELDS.contains(p.path("field").asText()) : (acentric ? "ACF" : criticalZ ? "ZCRIT" : temperature ? "TCRIT" : "PCRIT").equals(p.path("field").asText())) && integer(p.path("componentIndex"), 1, 9) && name(p.path("componentName"))
            && propertyUnit(p.path("field").asText()).equals(p.path("unit").asText()) && propertyValue(p.path("originalValue"), acentric) && propertyValue(p.path("targetValue"), acentric)
            && p.path("originalValue").asDouble() != p.path("targetValue").asDouble();
    }
    public static boolean validContext(JsonNode c, JsonNode i) {
        return validContext(c, i, "PCRIT");
    }
    public static boolean validTemperatureContext(JsonNode c, JsonNode i) {
        return validContext(c, i, "TCRIT");
    }
    public static boolean validCriticalZContext(JsonNode c, JsonNode i) {
        return validContext(c, i, "ZCRIT");
    }
    public static boolean validAcentricFactorContext(JsonNode c, JsonNode i) { return validContext(c, i, "ACF"); }
    public static boolean validEosPropertyContexts(JsonNode contexts, JsonNode i) {
        if (contexts == null || !contexts.isArray() || contexts.size() != EOS_FIELDS.size()) return false;
        for (int j = 0; j < EOS_FIELDS.size(); j++) if (!validContext(contexts.get(j), i, EOS_FIELDS.get(j))) return false;
        return true;
    }
    private static JsonNode sourceContext(JsonNode i, String field) {
        if (EOS_FIELDS.contains(field)) {
            for (var c : i.path("compositionalEosPropertyContexts")) if (field.equals(c.path("records").path(0).path("field").asText())) return c;
            return null;
        }
        return i.path("ACF".equals(field) ? "compositionalAcentricFactorContext" : "ZCRIT".equals(field) ? "compositionalCriticalZContext" : "TCRIT".equals(field) ? "compositionalTemperatureContext" : "compositionalFluidPropertyContext");
    }
    private static boolean validContext(JsonNode c, JsonNode i, String field) {
        boolean eos = EOS_FIELDS.contains(field), acentric = "ACF".equals(field), temperature = "TCRIT".equals(field), criticalZ = "ZCRIT".equals(field);
        String unit = propertyUnit(field);
        if (!shape(c, CONTEXT) || i == null || !(eos ? "eclipse-data-inspection/22".equals(i.path("schemaVersion").asText()) : acentric ? Set.of("eclipse-data-inspection/21", "eclipse-data-inspection/22").contains(i.path("schemaVersion").asText()) : criticalZ ? Set.of("eclipse-data-inspection/20", "eclipse-data-inspection/21", "eclipse-data-inspection/22").contains(i.path("schemaVersion").asText())
                : temperature ? Set.of("eclipse-data-inspection/19", "eclipse-data-inspection/20", "eclipse-data-inspection/21", "eclipse-data-inspection/22").contains(i.path("schemaVersion").asText())
                : Set.of("eclipse-data-inspection/17", "eclipse-data-inspection/19", "eclipse-data-inspection/20", "eclipse-data-inspection/21", "eclipse-data-inspection/22").contains(i.path("schemaVersion").asText()))
            || !(eos ? "eclipse-compositional-eos-property-context/1" : acentric ? "eclipse-compositional-acentric-factor-context/1" : criticalZ ? "eclipse-compositional-critical-z-context/1" : temperature ? "eclipse-compositional-temperature-context/1" : "eclipse-compositional-fluid-property-context/1").equals(c.path("schemaVersion").asText()) || !"FIELD".equals(i.path("unitSystem").asText())
            || !EclipseCompositionalProductionParameters.validContext(i.path("compositionalProductionContext"), i)
            || !source(c.path("sourceFile")) || !hash(c.path("sourceSha256")) || !integer(c.path("sourceSizeBytes"), 1, 4 * 1024 * 1024)
            || !integer(c.path("solver"), 300, 300) || !integer(c.path("componentCount"), 9, 9) || !"PR".equals(c.path("equationOfState").asText())
            || !c.path("componentNames").isArray() || c.path("componentNames").size() != 9 || !c.path("records").isArray() || c.path("records").size() != 9) return false;
        var producer = i.path("compositionalProductionContext");
        for (String key : new String[]{"sourceFile", "sourceSha256", "componentNames"}) if (!c.path(key).equals(producer.path(key))) return false;
        if (c.path("sourceSizeBytes").asLong() != producer.path("sourceSizeBytes").asLong()) return false;
        var names = new HashSet<String>(); int previousLine = 1;
        for (int j = 0; j < 9; j++) {
            var r = c.path("records").get(j); var component = c.path("componentNames").get(j);
            if (!name(component) || !names.add(component.asText()) || !shape(r, RECORD) || !integer(r.path("componentIndex"), j + 1, j + 1)
                || !r.path("componentName").equals(component) || !field.equals(r.path("field").asText()) || !unit.equals(r.path("unit").asText())
                || !integer(r.path("lineNumber"), previousLine, c.path("sourceSizeBytes").asLong()) || !propertyValue(r.path("originalValue"), acentric)) return false;
            previousLine = r.path("lineNumber").asInt();
        }
        return true;
    }
    public static boolean matchesInspection(JsonNode p, JsonNode i) {
        if (!valid(p) || i == null) return false;
        boolean acentric = ACENTRIC_FACTOR_SCHEMA.equals(p.path("schemaVersion").asText());
        boolean temperature = TEMPERATURE_SCHEMA.equals(p.path("schemaVersion").asText());
        boolean criticalZ = CRITICAL_Z_SCHEMA.equals(p.path("schemaVersion").asText());
        var c = sourceContext(i, p.path("field").asText());
        if (!validContext(c, i, p.path("field").asText())) return false;
        var r = c.path("records").get(p.path("componentIndex").asInt() - 1);
        return p.path("sourceFile").equals(c.path("sourceFile")) && p.path("sourceSha256").equals(c.path("sourceSha256"))
            && p.path("field").equals(r.path("field")) && p.path("unit").equals(r.path("unit")) && p.path("componentName").equals(r.path("componentName"))
            && p.path("lineNumber").asInt() == r.path("lineNumber").asInt() && p.path("originalValue").asDouble() == r.path("originalValue").asDouble();
    }
}
