package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict contract for persisting one inspected Flowline inner diameter in a derived PIPESIM model. */
public final class NetworkFlowlineDiameterParameters {
    public static final String SCHEMA = "pipesim-network-flowline-diameter-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "flowline", "originalInnerDiameter", "unit", "targetInnerDiameter");
    private static final Pattern NAME = Pattern.compile("^[^\\p{Cntrl}/\\\\]{1,255}$");

    private NetworkFlowlineDiameterParameters() {}

    public static boolean valid(JsonNode value, JsonNode inspection) {
        if (value == null || !value.isObject() || !SCHEMA.equals(value.path("schemaVersion").asText())) return false;
        var names = new HashSet<String>();
        value.properties().forEach(field -> names.add(field.getKey()));
        if (!names.equals(FIELDS) || !value.path("baselineRunId").isIntegralNumber() || !value.path("baselineRunId").canConvertToLong()
                || value.path("baselineRunId").asLong() <= 0 || !safeName(value.path("flowline").asText(null))
                || !safeUnit(value.path("unit").asText(null)) || !positiveFinite(value, "originalInnerDiameter")
                || !positiveFinite(value, "targetInnerDiameter")) return false;
        double original = value.path("originalInnerDiameter").asDouble();
        double target = value.path("targetInnerDiameter").asDouble();
        if (Math.abs(target - original) <= Math.max(1e-12, Math.abs(original) * 1e-12)) return false;

        JsonNode normalized = PipesimNetworkInspectionValidator.validateNode(inspection);
        if (normalized == null || !"pipesim-network-inspection/5".equals(normalized.path("schemaVersion").asText())) return false;
        for (JsonNode flowline : normalized.path("flowlines")) {
            if (value.path("flowline").asText().equals(flowline.path("name").asText())
                    && flowline.path("innerDiameter").isNumber()
                    && Double.compare(original, flowline.path("innerDiameter").asDouble()) == 0
                    && value.path("unit").asText().equals(flowline.path("unit").asText())) return true;
        }
        return false;
    }

    private static boolean safeName(String value) {
        return value != null && NAME.matcher(value).matches() && !value.contains("..") && !value.contains("://");
    }

    private static boolean safeUnit(String value) {
        return value != null && !value.isBlank() && value.length() <= 32 && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean positiveFinite(JsonNode value, String name) {
        double number = value.path(name).asDouble(Double.NaN);
        return Double.isFinite(number) && number > 0 && number <= 1000d;
    }
}
