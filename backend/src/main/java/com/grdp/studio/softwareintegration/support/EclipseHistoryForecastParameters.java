package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

import java.util.Set;
import java.util.regex.Pattern;

/** Strict contract for a forecast DATA run restarted from a successful history run. */
public final class EclipseHistoryForecastParameters {
    public static final String SCHEMA = "eclipse-history-forecast-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "historyRunId", "forecastDataFile", "restartArtifactName", "restartArtifactSha256", "restartReport");
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern DATA = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._ /-]{0,511}\\.DATA$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESTART = Pattern.compile("^eclipse-output-[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.FUNRST$", Pattern.CASE_INSENSITIVE);

    private EclipseHistoryForecastParameters() {}

    public static boolean valid(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != FIELDS.size()) return false;
        var names = new java.util.HashSet<String>();
        value.properties().forEach(field -> names.add(field.getKey()));
        if (!names.equals(FIELDS) || !SCHEMA.equals(value.path("schemaVersion").asText())) return false;
        if (!value.path("historyRunId").isIntegralNumber() || !value.path("historyRunId").canConvertToLong() || value.path("historyRunId").asLong() <= 0) return false;
        if (!value.path("restartReport").isIntegralNumber() || !value.path("restartReport").canConvertToInt() || value.path("restartReport").asInt() <= 0) return false;
        String data = value.path("forecastDataFile").asText(null);
        String artifact = value.path("restartArtifactName").asText(null);
        String sha256 = value.path("restartArtifactSha256").asText(null);
        return isSafeRelativeData(data) && artifact != null && RESTART.matcher(artifact).matches()
                && sha256 != null && SHA256.matcher(sha256).matches();
    }

    public static boolean matchesInspection(JsonNode value, JsonNode inspection) {
        if (!valid(value) || inspection == null || !inspection.path("packageFiles").isArray()) return false;
        String requested = value.path("forecastDataFile").asText();
        for (JsonNode file : inspection.path("packageFiles")) {
            if (requested.equals(file.path("relativePath").asText()) && file.path("relativePath").asText().toLowerCase(java.util.Locale.ROOT).endsWith(".data")) return true;
        }
        return false;
    }

    public static boolean isSafeRelativeData(String value) {
        if (value == null || !DATA.matcher(value).matches() || value.contains("\\") || value.startsWith("/") || value.contains(":")) return false;
        return java.util.Arrays.stream(value.split("/", -1)).noneMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."));
    }
}
