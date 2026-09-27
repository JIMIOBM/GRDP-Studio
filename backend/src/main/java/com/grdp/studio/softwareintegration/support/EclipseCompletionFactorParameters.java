package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** L25 contract for changing one inspected COMPDAT connection factor. */
public final class EclipseCompletionFactorParameters {
    public static final String SCHEMA = "eclipse-completion-factor-parameters/1";
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "baselineRunId", "well", "sourceFile", "lineNumber", "i", "j", "k1", "k2",
            "originalConnectionFactor", "targetConnectionFactor");
    private static final Pattern WELL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$");
    private static final Pattern RELATIVE_PATH = Pattern.compile("^[^\\\\/:][^\\\\:]{0,511}$");
    private static final Pattern INDEX = Pattern.compile("^[1-9][0-9]{0,6}$");

    private EclipseCompletionFactorParameters() {}

    public static boolean valid(JsonNode value) {
        if (value == null || !value.isObject()) return false;
        var names = new HashSet<String>();
        value.properties().forEach(field -> names.add(field.getKey()));
        if (!names.equals(FIELDS) || !SCHEMA.equals(value.path("schemaVersion").asText())) return false;
        if (!value.path("baselineRunId").isIntegralNumber() || !value.path("baselineRunId").canConvertToLong()
                || value.path("baselineRunId").asLong() <= 0) return false;
        String well = value.path("well").asText(null);
        String sourceFile = value.path("sourceFile").asText(null);
        if (well == null || !WELL.matcher(well).matches() || sourceFile == null || !validRelativePath(sourceFile)
                || !value.path("lineNumber").isIntegralNumber() || !value.path("lineNumber").canConvertToInt()
                || value.path("lineNumber").asInt() <= 0 || value.path("lineNumber").asInt() > 2_000_000) return false;
        String i = value.path("i").asText(null);
        String j = value.path("j").asText(null);
        String k1 = value.path("k1").asText(null);
        String k2 = value.path("k2").asText(null);
        if (i == null || j == null || k1 == null || k2 == null || !INDEX.matcher(i).matches() || !INDEX.matcher(j).matches()
                || !INDEX.matcher(k1).matches() || !INDEX.matcher(k2).matches() || Integer.parseInt(k1) > Integer.parseInt(k2)) return false;
        return positiveFinite(value, "originalConnectionFactor") && positiveFinite(value, "targetConnectionFactor")
                && Double.compare(value.path("originalConnectionFactor").asDouble(), value.path("targetConnectionFactor").asDouble()) != 0;
    }

    public static boolean matchesInspection(JsonNode value, JsonNode inspection) {
        if (!valid(value) || inspection == null || !"eclipse-data-inspection/4".equals(inspection.path("schemaVersion").asText())) return false;
        String well = value.path("well").asText();
        for (JsonNode completion : inspection.path("scheduleMetadata").path("completions")) {
            if (!"COMPDAT".equals(completion.path("keyword").asText()) || !well.equals(completion.path("well").asText())
                    || !value.path("sourceFile").asText().equals(completion.path("sourceFile").asText())
                    || value.path("lineNumber").asInt() != completion.path("lineNumber").asInt()
                    || !value.path("i").asText().equals(completion.path("i").asText())
                    || !value.path("j").asText().equals(completion.path("j").asText())
                    || !value.path("k1").asText().equals(completion.path("k1").asText())
                    || !value.path("k2").asText().equals(completion.path("k2").asText())
                    || !Set.of("OPEN", "SHUT").contains(completion.path("status").asText())) continue;
            for (JsonNode record : inspection.path("scheduleMetadata").path("records")) {
                JsonNode values = record.path("values");
                if (!"COMPDAT".equals(record.path("keyword").asText()) || !value.path("sourceFile").asText().equals(record.path("sourceFile").asText())
                        || value.path("lineNumber").asInt() != record.path("lineNumber").asInt() || !values.isArray() || values.size() <= 7
                        || !well.equals(values.path(0).asText()) || !value.path("i").asText().equals(values.path(1).asText())
                        || !value.path("j").asText().equals(values.path(2).asText()) || !value.path("k1").asText().equals(values.path(3).asText())
                        || !value.path("k2").asText().equals(values.path(4).asText())) continue;
                try {
                    double original = Double.parseDouble(values.path(7).asText());
                    return Double.isFinite(original) && original > 0
                            && Double.compare(original, value.path("originalConnectionFactor").asDouble()) == 0;
                } catch (NumberFormatException ignored) { return false; }
            }
        }
        return false;
    }

    private static boolean positiveFinite(JsonNode value, String name) {
        double number = value.path(name).asDouble(Double.NaN);
        return Double.isFinite(number) && number > 0 && number <= 1_000_000_000d;
    }

    private static boolean validRelativePath(String value) {
        return RELATIVE_PATH.matcher(value).matches() && !value.endsWith("/") && !value.contains("//")
                && !value.contains("..") && value.chars().noneMatch(Character::isISOControl);
    }
}
