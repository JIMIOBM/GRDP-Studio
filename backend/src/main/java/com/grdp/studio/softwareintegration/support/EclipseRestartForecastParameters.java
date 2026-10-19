package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Report/time/physical-source binding for verified, portable E100 forecasts. */
public final class EclipseRestartForecastParameters {
    public static final String SCHEMA = "eclipse-history-forecast-parameters/2";
    public static final String BINARY_SCHEMA = "eclipse-history-forecast-parameters/3";
    private static final Pattern SHA = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern CASE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}$");
    private EclipseRestartForecastParameters() {}

    public static boolean valid(JsonNode value) {
        return fields(value, "schemaVersion", "historyRunId", "forecastDataFile", "restartArtifactName", "restartArtifactSha256", "restartReport", "restartTimeDays", "restartDate", "physicsSha256")
                && Set.of(SCHEMA, BINARY_SCHEMA).contains(value.path("schemaVersion").asText())
                && integer(value.path("historyRunId"), 1, Long.MAX_VALUE)
                && integer(value.path("restartReport"), 1, Integer.MAX_VALUE)
                && integer(value.path("restartTimeDays"), 0, 3_000_000)
                && date(value.path("restartDate")) != null && sha(value.path("physicsSha256"))
                && value.path("forecastDataFile").isTextual() && EclipseHistoryForecastParameters.isSafeRelativeData(value.path("forecastDataFile").asText())
                && value.path("restartArtifactName").isTextual()
                && value.path("restartArtifactName").asText().matches("(?i)^eclipse-output-[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\." + (BINARY_SCHEMA.equals(value.path("schemaVersion").asText()) ? "UNRST" : "FUNRST") + "$")
                && sha(value.path("restartArtifactSha256"));
    }

    public static boolean matchesCatalog(JsonNode value, JsonNode source, JsonNode inspection,
                                         String artifactName, String artifactSha256, long artifactSize) {
        if (!valid(value) || !validCatalog(source) || inspection == null || !inspection.path("packageFiles").isArray()) return false;
        var catalog = source.path("catalog");
        if (BINARY_SCHEMA.equals(value.path("schemaVersion").asText()) != "eclipse-restart-source/2".equals(source.path("schemaVersion").asText())
                || !artifactName.equals(catalog.path("artifactName").asText()) || !artifactSha256.equals(catalog.path("artifactSha256").asText())
                || artifactSize != catalog.path("artifactSizeBytes").asLong()
                || !artifactName.equals(value.path("restartArtifactName").asText()) || !artifactSha256.equals(value.path("restartArtifactSha256").asText())
                || !source.path("physicsSha256").equals(value.path("physicsSha256"))) return false;
        JsonNode selected = null;
        for (var report : catalog.path("reports")) if (report.path("reportNumber").equals(value.path("restartReport"))) selected = report;
        if (selected == null || selected.path("timeDays").asLong() != value.path("restartTimeDays").asLong()
                || !selected.path("date").equals(value.path("restartDate"))) return false;
        JsonNode future = null;
        for (var candidate : source.path("forecastInputs")) if (candidate.path("relativePath").equals(value.path("forecastDataFile"))) future = candidate;
        if (future == null || !future.path("restartReport").equals(value.path("restartReport"))) return false;
        for (var file : inspection.path("packageFiles"))
            if (file.path("relativePath").equals(future.path("relativePath")) && file.path("sha256").equals(future.path("sha256"))) return true;
        return false;
    }

    public static boolean validCatalog(JsonNode source) {
        if (source == null) return false;
        boolean binary = "eclipse-restart-source/2".equals(source.path("schemaVersion").asText());
        if (!fields(source, "schemaVersion", "catalog", "physicsSha256", "forecastInputs")
                || !(binary || "eclipse-restart-source/1".equals(source.path("schemaVersion").asText())) || !sha(source.path("physicsSha256"))) return false;
        var catalog = source.path("catalog");
        if (!fields(catalog, "schemaVersion", "caseName", "startDate", "artifactName", "artifactSha256", "artifactSizeBytes", "reports")
                || !(binary ? "eclipse-restart-catalog/2" : "eclipse-restart-catalog/1").equals(catalog.path("schemaVersion").asText())
                || !catalog.path("caseName").isTextual() || !CASE.matcher(catalog.path("caseName").asText()).matches()
                || !catalog.path("artifactName").asText().equals("eclipse-output-" + catalog.path("caseName").asText() + (binary ? ".UNRST" : ".FUNRST"))
                || !sha(catalog.path("artifactSha256")) || !integer(catalog.path("artifactSizeBytes"), 1, 128L * 1024 * 1024)) return false;
        var origin = date(catalog.path("startDate")); var reports = catalog.path("reports");
        if (origin == null || !reports.isArray() || reports.size() < 1 || reports.size() > 8192) return false;
        long previousNumber = -1, previousTime = -1, nextOffset = -1; JsonNode first = reports.get(0);
        var numbers = new HashSet<Long>();
        for (var report : reports) {
            if (!fields(report, "reportNumber", "timeDays", "date", "unitSystem", "dimensions", "activeCells", "phaseMask", "solver", "byteOffset", "sizeBytes", "sha256")
                    || !integer(report.path("reportNumber"), 0, Integer.MAX_VALUE) || !integer(report.path("timeDays"), 0, 3_000_000)
                    || !integer(report.path("byteOffset"), 0, catalog.path("artifactSizeBytes").asLong())
                    || !integer(report.path("sizeBytes"), 1, catalog.path("artifactSizeBytes").asLong())
                    || !integer(report.path("activeCells"), 1, 100_000_000) || !integer(report.path("phaseMask"), 1, 7)
                    || !integer(report.path("solver"), 100, 100) || !sha(report.path("sha256"))
                    || !report.path("unitSystem").isTextual() || !Set.of("FIELD", "METRIC").contains(report.path("unitSystem").asText())) return false;
            var dimensions = report.path("dimensions");
            if (!fields(dimensions, "nx", "ny", "nz") || !integer(dimensions.path("nx"), 1, 100_000_000)
                    || !integer(dimensions.path("ny"), 1, 100_000_000) || !integer(dimensions.path("nz"), 1, 100_000_000)) return false;
            long cells = dimensions.path("nx").asLong() * dimensions.path("ny").asLong();
            if (cells > 100_000_000 || cells * dimensions.path("nz").asLong() > 100_000_000
                    || report.path("activeCells").asLong() > cells * dimensions.path("nz").asLong()) return false;
            long number = report.path("reportNumber").asLong(), time = report.path("timeDays").asLong(), offset = report.path("byteOffset").asLong();
            if (!numbers.add(number) || number <= previousNumber || time <= previousTime || nextOffset >= 0 && offset != nextOffset
                    || offset + report.path("sizeBytes").asLong() > catalog.path("artifactSizeBytes").asLong()
                    || !origin.plusDays(time).equals(date(report.path("date")))) return false;
            for (var key : new String[] {"dimensions", "activeCells", "phaseMask", "unitSystem", "solver"}) if (!report.path(key).equals(first.path(key))) return false;
            previousNumber = number; previousTime = time; nextOffset = offset + report.path("sizeBytes").asLong();
        }
        if (nextOffset != catalog.path("artifactSizeBytes").asLong()) return false;
        var forecasts = source.path("forecastInputs");
        if (!forecasts.isArray() || forecasts.size() > 64) return false;
        var paths = new HashSet<String>();
        for (var future : forecasts) {
            if (!fields(future, "relativePath", "sha256", "restartReport", "restartCase", "futureDates")
                    || !future.path("relativePath").isTextual() || !EclipseHistoryForecastParameters.isSafeRelativeData(future.path("relativePath").asText())
                    || !paths.add(future.path("relativePath").asText()) || !sha(future.path("sha256"))
                    || !integer(future.path("restartReport"), 1, Integer.MAX_VALUE) || !numbers.contains(future.path("restartReport").asLong())
                    || !future.path("restartCase").equals(catalog.path("caseName"))) return false;
            LocalDate last = null;
            for (var report : reports) if (report.path("reportNumber").equals(future.path("restartReport"))) last = date(report.path("date"));
            var dates = future.path("futureDates"); if (!dates.isArray() || dates.size() < 1 || dates.size() > 8192 || last == null) return false;
            for (var item : dates) { var current = date(item); if (current == null || !current.isAfter(last)) return false; last = current; }
        }
        return true;
    }
    private static boolean fields(JsonNode value, String... fields) {
        if (value == null || !value.isObject() || value.size() != fields.length) return false;
        var names = new HashSet<String>(); value.properties().forEach(e -> names.add(e.getKey())); return names.equals(Set.of(fields));
    }
    private static boolean integer(JsonNode value, long min, long max) { return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= min && value.asLong() <= max; }
    private static boolean sha(JsonNode value) { return value.isTextual() && SHA.matcher(value.asText()).matches(); }
    private static LocalDate date(JsonNode value) {
        if (!value.isTextual() || !value.asText().matches("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) return null;
        try { return LocalDate.parse(value.asText()); } catch (RuntimeException e) { return null; }
    }
}
