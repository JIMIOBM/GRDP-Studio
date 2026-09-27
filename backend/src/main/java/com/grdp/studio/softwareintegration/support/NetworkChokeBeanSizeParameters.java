package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** L26 contract for changing one inspected PIPESIM Network Choke Bean Size. */
public final class NetworkChokeBeanSizeParameters {
    public static final String SCHEMA = "pipesim-network-choke-bean-size-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "choke", "originalBeanSize", "targetBeanSize");
    private static final Pattern NAME = Pattern.compile("^[^\\p{Cntrl}/\\\\]{1,255}$");

    private NetworkChokeBeanSizeParameters() {}

    public static boolean valid(JsonNode value) {
        if (value == null || !value.isObject() || !SCHEMA.equals(value.path("schemaVersion").asText())) return false;
        var names = new HashSet<String>();
        value.properties().forEach(field -> names.add(field.getKey()));
        if (!names.equals(FIELDS) || !value.path("baselineRunId").isIntegralNumber() || !value.path("baselineRunId").canConvertToLong()
                || value.path("baselineRunId").asLong() <= 0 || !safeName(value.path("choke").asText(null))
                || !positiveFinite(value, "originalBeanSize") || !positiveFinite(value, "targetBeanSize")) return false;
        double original = value.path("originalBeanSize").asDouble();
        double target = value.path("targetBeanSize").asDouble();
        return Math.abs(target - original) > Math.max(1e-12, Math.abs(original) * 1e-12);
    }

    public static boolean matchesInspection(JsonNode value, JsonNode inspection) {
        if (!valid(value) || inspection == null || !"pipesim-network-inspection/3".equals(inspection.path("schemaVersion").asText())) return false;
        for (JsonNode choke : inspection.path("chokes")) {
            if (value.path("choke").asText().equals(choke.path("name").asText())
                    && Double.compare(value.path("originalBeanSize").asDouble(), choke.path("beanSize").asDouble()) == 0) return true;
        }
        return false;
    }

    private static boolean safeName(String value) {
        return value != null && NAME.matcher(value).matches() && !value.contains("..") && !value.contains("://");
    }

    private static boolean positiveFinite(JsonNode value, String name) {
        double number = value.path(name).asDouble(Double.NaN);
        return Double.isFinite(number) && number > 0 && number <= 1_000_000d;
    }
}
