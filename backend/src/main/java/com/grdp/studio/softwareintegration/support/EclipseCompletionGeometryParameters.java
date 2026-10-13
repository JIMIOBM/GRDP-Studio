package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Explicit initial single-connection numerical edits, only with calculated CF. */
public final class EclipseCompletionGeometryParameters {
    public static final String SCHEMA = "eclipse-completion-geometry-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion","baselineRunId","well","sourceFile","lineNumber","i","j","k1","k2","field","unit","originalValue","targetValue","recordValues");
    private EclipseCompletionGeometryParameters() {}
    public static boolean valid(JsonNode v) {
        if (v == null || !v.isObject()) return false;
        var names = new HashSet<String>(); v.properties().forEach(p -> names.add(p.getKey()));
        if (!names.equals(FIELDS) || !SCHEMA.equals(v.path("schemaVersion").asText()) || !v.path("baselineRunId").isIntegralNumber()
                || !v.path("baselineRunId").canConvertToLong() || v.path("baselineRunId").asLong() <= 0 || !v.path("lineNumber").isIntegralNumber()
                || !v.path("lineNumber").canConvertToInt() || v.path("lineNumber").asInt() <= 0 || v.path("lineNumber").asInt() > 2_000_000) return false;
        for (String key : List.of("well","sourceFile","i","j","k1","k2","field","unit")) if (!v.path(key).isString()) return false;
        if (!v.path("well").asText().matches("^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$")
                || !v.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$")) return false;
        for (String key : List.of("i","j","k1","k2")) if (!v.path(key).asText().matches("^[1-9][0-9]{0,6}$")) return false;
        String field = v.path("field").asText(), unit = v.path("unit").asText();
        if (!Set.of("DIAMETER","KH","SKIN").contains(field) || !v.path("k1").asText().equals(v.path("k2").asText())
                || List.of("METRIC","FIELD","LAB","PVT-M").stream().noneMatch(s -> unit.equals(unitFor(s,field)))) return false;
        if (!v.path("originalValue").isNumber() || !v.path("targetValue").isNumber()) return false;
        double original = v.path("originalValue").asDouble(), target = v.path("targetValue").asDouble();
        if (original == target || !scalar(field,unit,original) || !scalar(field,unit,target)) return false;
        JsonNode row = v.path("recordValues");
        if (!row.isArray() || row.size() < 11 || row.size() > 14) return false;
        for (JsonNode item : row) if (!item.isString() || item.asText().isEmpty() || item.asText().length() > 64 || item.asText().chars().anyMatch(Character::isISOControl)) return false;
        if (!row.path(0).asText().equals(v.path("well").asText()) || !"OPEN".equals(row.path(5).asText())) return false;
        for (int i=1;i<=4;i++) if (!row.path(i).asText().equals(v.path(List.of("i","j","k1","k2").get(i-1)).asText())) return false;
        String cf = row.path(7).asText();
        if (!Set.of("*","1*").contains(cf) && number(cf) != 0) return false;
        return number(row.path(index(field)).asText()) == original;
    }
    public static boolean matchesInspection(JsonNode v, JsonNode inspection) {
        if (!valid(v) || inspection == null || inspection.path("packageFiles").size() != 1
                || !v.path("sourceFile").asText().equals(inspection.path("packageFiles").path(0).path("relativePath").asText())) return false;
        String system = inspection.path("unitSystem").asText();
        if (!Set.of("FIELD","METRIC","LAB","PVT-M").contains(system) || !v.path("unit").asText().equals(unitFor(system,v.path("field").asText()))) return false;
        for (String field : List.of("DIAMETER","KH","SKIN")) if (!scalar(field,unitFor(system,field),number(v.path("recordValues").path(index(field)).asText()))) return false;
        for (String[] axis : new String[][]{{"i","nx"},{"j","ny"},{"k1","nz"}})
            if (Integer.parseInt(v.path(axis[0]).asText()) > inspection.path("dimensions").path(axis[1]).asInt(-1)) return false;
        JsonNode selected = null; int count = 0;
        for (JsonNode record : inspection.path("scheduleMetadata").path("records")) {
            if (!Set.of("COMPDAT","COMPDATM").contains(record.path("keyword").asText())) continue;
            String well = record.path("values").path(0).asText();
            if (well.contains("*") || well.contains("?")) return false;
            if (well.trim().equals(v.path("well").asText().trim())) { selected = record; count++; }
        }
        if (count != 1 || selected == null || !"COMPDAT".equals(selected.path("keyword").asText())
                || !selected.path("sourceFile").equals(v.path("sourceFile")) || !selected.path("lineNumber").equals(v.path("lineNumber"))
                || !selected.path("values").equals(v.path("recordValues"))) return false;
        for (JsonNode date : inspection.path("scheduleTimeline")) if (!date.path("sourceFile").equals(v.path("sourceFile"))
                || !date.path("lineNumber").isIntegralNumber() || date.path("lineNumber").asInt() <= v.path("lineNumber").asInt()) return false;
        return true;
    }
    private static int index(String field) { return "DIAMETER".equals(field) ? 8 : "KH".equals(field) ? 9 : 10; }
    private static String unitFor(String system, String field) {
        if ("SKIN".equals(field)) return "1";
        String length = "FIELD".equals(system) ? "ft" : "LAB".equals(system) ? "cm" : Set.of("METRIC","PVT-M").contains(system) ? "m" : "";
        return "KH".equals(field) ? "mD."+length : length;
    }
    private static boolean scalar(String field,String unit,double n) {
        if (!Double.isFinite(n)) return false;
        return switch (field) {
            case "SKIN" -> n>=0 && n<=100;
            case "KH" -> n>0 && n<=1_000_000_000;
            case "DIAMETER" -> n>0 && n*("ft".equals(unit)?0.3048:"cm".equals(unit)?0.01:1)<=2;
            default -> false;
        };
    }
    private static double number(String text) {
        try { return Double.parseDouble(text.replace('D','E').replace('d','e')); } catch (NumberFormatException e) { return Double.NaN; }
    }
}
