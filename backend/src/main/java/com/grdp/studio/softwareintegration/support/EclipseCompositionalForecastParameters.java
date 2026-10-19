package com.grdp.studio.softwareintegration.support;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class EclipseCompositionalForecastParameters {
    public static final String SCHEMA = "eclipse-compositional-forecast-parameters/1";
    public static final String CATALOG = "eclipse-compositional-save-catalog.json";
    private static final Set<String> PARAMETERS = Set.of("schemaVersion", "historyRunId", "sourceFile", "sourceSha256", "saveCatalogSha256", "saveReport", "targetGasRate");
    private static final Set<String> HEADER = Set.of("schemaVersion", "sourceFile", "sourceSha256", "sourceSizeBytes", "solver", "solverVersion", "caseName", "startDate",
        "reportNumber", "timeDays", "date", "unitSystem", "dimensions", "activeCells", "componentCount", "componentNames", "equationOfState", "endTimeDays", "files");
    private EclipseCompositionalForecastParameters() {}
    private static boolean shape(JsonNode n, Set<String> fields) {
        if (n == null || !n.isObject() || n.size() != fields.size()) return false;
        var names = new HashSet<String>(); n.properties().forEach(p -> names.add(p.getKey())); return names.equals(fields);
    }
    private static boolean integer(JsonNode n, long min, long max) { return n.isIntegralNumber() && n.canConvertToLong() && n.asLong() >= min && n.asLong() <= max; }
    private static boolean hash(JsonNode n) { return n.isTextual() && n.asText().matches("[a-f0-9]{64}"); }
    private static boolean source(JsonNode n) { return n.isTextual() && n.asText().matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]"); }
    private static boolean day(JsonNode n) { return n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble() > 0 && n.asDouble() <= 1_000_000 && n.asDouble() == Math.rint(n.asDouble()); }
    private static boolean rate(JsonNode n) { return n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble() > 0 && n.asDouble() <= 100_000_000; }
    public static boolean valid(JsonNode p) {
        return shape(p, PARAMETERS) && SCHEMA.equals(p.path("schemaVersion").asText()) && integer(p.path("historyRunId"), 1, Long.MAX_VALUE)
            && source(p.path("sourceFile")) && hash(p.path("sourceSha256")) && hash(p.path("saveCatalogSha256"))
            && integer(p.path("saveReport"), 1, 9999) && (p.path("targetGasRate").isNull() || rate(p.path("targetGasRate")));
    }
    public static boolean validCatalog(JsonNode c) {
        if (!shape(c, HEADER) || !"eclipse-compositional-save-catalog/1".equals(c.path("schemaVersion").asText())
            || !source(c.path("sourceFile")) || !hash(c.path("sourceSha256")) || !integer(c.path("sourceSizeBytes"), 1, 4 * 1024 * 1024)
            || !integer(c.path("solver"), 300, 300) || !"2024.1".equals(c.path("solverVersion").asText())
            || !"FIELD".equals(c.path("unitSystem").asText()) || !"PR".equals(c.path("equationOfState").asText())
            || !integer(c.path("componentCount"), 9, 9) || !c.path("componentNames").isArray() || c.path("componentNames").size() != 9
            || !integer(c.path("reportNumber"), 1, 9999) || !day(c.path("timeDays")) || !day(c.path("endTimeDays"))
            || c.path("endTimeDays").asDouble() <= c.path("timeDays").asDouble()) return false;
        String file = c.path("sourceFile").asText(), stem = file.substring(0, file.length() - 5);
        if (!c.path("caseName").isTextual() || !stem.equals(c.path("caseName").asText())) return false;
        var components = new HashSet<String>();
        for (var name : c.path("componentNames")) if (!name.isTextual() || !name.asText().matches("[A-Za-z0-9][A-Za-z0-9+_.-]{0,15}") || !components.add(name.asText())) return false;
        if (!shape(c.path("dimensions"), Set.of("nx", "ny", "nz"))) return false;
        long cells = 1;
        for (var axis : List.of("nx", "ny", "nz")) {
            if (!integer(c.path("dimensions").path(axis), 1, 1_000_000)) return false;
            cells *= c.path("dimensions").path(axis).asLong(); if (cells > 1_000_000) return false;
        }
        if (!integer(c.path("activeCells"), 1, cells)) return false;
        try {
            if (!c.path("startDate").isTextual() || !c.path("date").isTextual()
                || !c.path("startDate").asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !c.path("date").asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                || !LocalDate.parse(c.path("startDate").asText()).plusDays(c.path("timeDays").asLong()).equals(LocalDate.parse(c.path("date").asText()))) return false;
            var origin = LocalDate.parse(c.path("startDate").asText());
            if (origin.getYear() < 1 || origin.plusDays(c.path("endTimeDays").asLong()).getYear() > 9999) return false;
        } catch (RuntimeException error) { return false; }
        var files = c.path("files");
        if (!files.isArray() || files.size() != 3) return false;
        var report = String.format(Locale.ROOT, "%04d", c.path("reportNumber").asInt());
        var expected = List.of(stem + ".V0000", stem + ".V" + report, stem + ".X" + report);
        long total = 0;
        for (int j = 0; j < 3; j++) {
            var f = files.get(j);
            if (!shape(f, Set.of("name", "artifactName", "sizeBytes", "sha256")) || !expected.get(j).equals(f.path("name").asText())
                || !("eclipse-output-" + expected.get(j)).equals(f.path("artifactName").asText()) || !hash(f.path("sha256"))
                || !integer(f.path("sizeBytes"), 24, 128 * 1024 * 1024)) return false;
            total += f.path("sizeBytes").asLong();
        }
        return total + 2L * c.path("sourceSizeBytes").asLong() + 64 * 1024 <= 64L * 1024 * 1024;
    }
    public static boolean validPortableContext(JsonNode c, JsonNode inspection) {
        if (!shape(c, Set.of("schemaVersion", "sourceFile", "sourceSha256", "sourceSizeBytes", "historySourceFile", "targetGasRate", "historyCatalog"))
            || inspection == null || !"eclipse-data-inspection/18".equals(inspection.path("schemaVersion").asText())
            || !"eclipse-compositional-portable-restart-context/1".equals(c.path("schemaVersion").asText())
            || !source(c.path("sourceFile")) || !hash(c.path("sourceSha256")) || !integer(c.path("sourceSizeBytes"), 1, 4 * 1024 * 1024)
            || !c.path("sourceFile").equals(inspection.path("caseName")) || !(c.path("targetGasRate").isNull() || rate(c.path("targetGasRate")))) return false;
        var history = c.path("historyCatalog");
        if (!validCatalog(history) || !("history/input/" + history.path("sourceFile").asText()).equals(c.path("historySourceFile").asText())
            || !"FIELD".equals(inspection.path("unitSystem").asText()) || !history.path("dimensions").equals(inspection.path("dimensions"))
            || !EclipseSimulationMetadataValidator.valid(inspection.path("simulation"))
            || !history.path("componentNames").equals(inspection.path("simulation").path("componentNames"))
            || inspection.path("simulation").path("componentCount").asInt() != 9) return false;
        var expected = new java.util.HashMap<String, JsonNode>();
        for (var file : history.path("files")) expected.put("history/state/" + file.path("name").asText(), file);
        var files = inspection.path("packageFiles");
        if (!files.isArray() || files.size() != 6) return false;
        var seen = new HashSet<String>(); long total = 0;
        for (var file : files) {
            if (!shape(file, Set.of("relativePath", "sizeBytes", "sha256")) || !file.path("relativePath").isTextual()
                || !integer(file.path("sizeBytes"), 1, 64 * 1024 * 1024) || !hash(file.path("sha256"))) return false;
            var path = file.path("relativePath").asText(); if (!seen.add(path)) return false;
            total += file.path("sizeBytes").asLong();
            if (path.equals(c.path("sourceFile").asText())) {
                if (!file.path("sha256").equals(c.path("sourceSha256")) || file.path("sizeBytes").asLong() != c.path("sourceSizeBytes").asLong()) return false;
            } else if (path.equals(c.path("historySourceFile").asText())) {
                if (!file.path("sha256").equals(history.path("sourceSha256")) || file.path("sizeBytes").asLong() != history.path("sourceSizeBytes").asLong()) return false;
            } else if (expected.containsKey(path)) {
                var nativeFile = expected.get(path);
                if (!file.path("sha256").equals(nativeFile.path("sha256")) || file.path("sizeBytes").asLong() != nativeFile.path("sizeBytes").asLong()) return false;
            } else if (!path.equals("eclipse-compositional-restart-binding.json") || file.path("sizeBytes").asLong() > 64 * 1024) return false;
        }
        var paths = new HashSet<>(expected.keySet()); paths.add(c.path("sourceFile").asText()); paths.add(c.path("historySourceFile").asText()); paths.add("eclipse-compositional-restart-binding.json");
        return seen.equals(paths) && total <= 64L * 1024 * 1024;
    }
    public static boolean matchesInspection(JsonNode c, JsonNode i) {
        if (!validCatalog(c) || i == null || !EclipseCompositionalFluidPropertyParameters.validContext(i.path("compositionalFluidPropertyContext"), i)
            || !EclipseCompositionalReinjectionParameters.validContext(i.path("compositionalReinjectionContext"), i)) return false;
        var source = i.path("compositionalFluidPropertyContext");
        for (var field : List.of("sourceFile", "sourceSha256", "componentNames")) if (!c.path(field).equals(source.path(field))) return false;
        return c.path("sourceSizeBytes").asLong() == source.path("sourceSizeBytes").asLong() && c.path("dimensions").equals(i.path("dimensions"));
    }
    public static boolean matchesRequest(JsonNode p, JsonNode c, JsonNode i) {
        return valid(p) && matchesInspection(c, i) && p.path("sourceFile").equals(c.path("sourceFile")) && p.path("sourceSha256").equals(c.path("sourceSha256"))
            && p.path("saveReport").asInt() == c.path("reportNumber").asInt() && (p.path("targetGasRate").isNull()
                || p.path("targetGasRate").asDouble() != i.path("compositionalProductionContext").path("records").get(0).path("originalGasRate").asDouble());
    }
    public static boolean matchesResult(JsonNode c, JsonNode result) {
        if (!validCatalog(c) || result == null || !"eclipse-summary-result/2".equals(result.path("schemaVersion").asText())
            || !"eclipse_300".equals(result.path("modelKind").asText()) || !c.path("sourceFile").equals(result.path("caseName"))
            || !c.path("startDate").equals(result.path("summary").path("startDate"))
            || !integer(result.path("simulation").path("solver"), 300, 300) || !"COMPOSITIONAL".equals(result.path("simulation").path("modelType").asText())
            || !c.path("componentNames").equals(result.path("simulation").path("componentNames"))
            || !"PR".equals(result.path("simulation").path("equationOfState").asText()) || !integer(result.path("simulation").path("componentCount"), 9, 9)) return false;
        for (var field : List.of("problems", "errors", "bugs")) if (!integer(result.path("eclEnd").path(field), 0, 0)) return false;
        for (var axis : List.of("nx", "ny", "nz")) if (result.path("grid").path(axis).asInt(-1) != c.path("dimensions").path(axis).asInt()) return false;
        if (result.path("grid").path("activeCells").asInt(-1) != c.path("activeCells").asInt() || !result.path("outputFiles").isArray()) return false;
        for (var file : c.path("files")) {
            int matches = 0;
            for (var output : result.path("outputFiles")) if (file.path("name").equals(output.path("name"))
                && file.path("sha256").equals(output.path("sha256")) && integer(output.path("sizeBytes"), file.path("sizeBytes").asLong(), file.path("sizeBytes").asLong())) matches++;
            if (matches != 1) return false;
        }
        return true;
    }
}
