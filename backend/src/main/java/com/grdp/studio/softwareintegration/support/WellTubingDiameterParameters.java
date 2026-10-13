package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict contract for persisting one inspected PIPESIM Tubing inner diameter. */
public final class WellTubingDiameterParameters {
    public static final String SCHEMA = "pipesim-well-tubing-diameter-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "wellContext", "tubingContext",
            "originalInnerDiameter", "unit", "targetInnerDiameter");
    private static final Pattern NAME = Pattern.compile("^[^\\p{Cntrl}/\\\\]{1,255}$");

    private WellTubingDiameterParameters() {}

    public static boolean valid(JsonNode value, JsonNode inspection) {
        if (value == null || !value.isObject() || !SCHEMA.equals(value.path("schemaVersion").asText())) return false;
        var names = new HashSet<String>();
        value.properties().forEach(field -> names.add(field.getKey()));
        if (!names.equals(FIELDS) || !value.path("baselineRunId").isIntegralNumber()
                || !value.path("baselineRunId").canConvertToLong() || value.path("baselineRunId").asLong() <= 0
                || !safeName(value.path("wellContext").asText(null)) || !safeName(value.path("tubingContext").asText(null))
                || !positiveFinite(value.path("originalInnerDiameter")) || !positiveFinite(value.path("targetInnerDiameter"))
                || !safeUnit(value.path("unit").asText(null))) return false;
        double original = value.path("originalInnerDiameter").asDouble();
        double target = value.path("targetInnerDiameter").asDouble();
        if (Math.abs(target - original) <= Math.max(1e-12, Math.abs(original) * 1e-12)) return false;

        JsonNode normalized = PipesimWellInspectionValidator.validate(inspection);
        if (normalized == null || !java.util.Set.of("pipesim-well-inspection/8", "pipesim-well-inspection/9").contains(normalized.path("schemaVersion").asText())) return false;
        for (JsonNode well : normalized.path("wellContexts")) {
            if (!value.path("wellContext").asText().equals(well.path("context").asText())) continue;
            for (JsonNode tubing : well.path("tubingComponents")) {
                if (value.path("tubingContext").asText().equals(tubing.path("context").asText())
                        && tubing.path("innerDiameter").isNumber()
                        && Double.compare(original, tubing.path("innerDiameter").asDouble()) == 0
                        && value.path("unit").asText().equals(tubing.path("unit").asText())) return true;
            }
        }
        return false;
    }

    private static boolean safeName(String value) {
        return value != null && NAME.matcher(value).matches() && !value.contains("..") && !value.contains("://");
    }

    private static boolean safeUnit(String value) {
        return value != null && !value.isBlank() && value.length() <= 32 && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean positiveFinite(JsonNode value) {
        double number = value.asDouble(Double.NaN);
        return value.isNumber() && Double.isFinite(number) && number > 0 && number <= 1000d;
    }
}
