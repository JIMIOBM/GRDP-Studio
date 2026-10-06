package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.util.Map;

/** Public scalar contract mirrors the installed Toolkit adapter; no implicit fluid defaults. */
public final class TemplateCreationInputs {
    private TemplateCreationInputs() { }
    private record Range(double min, double max, boolean inclusive) { }
    private static final Map<String, Range> RANGES = Map.of(
            "oilApi", new Range(0, 100, false), "gasSpecificGravity", new Range(0, 10, false),
            "waterSpecificGravity", new Range(0, 10, false), "gorScfStb", new Range(0, 1_000_000, true),
            "waterCutPercent", new Range(0, 100, true), "reservoirPressurePsia", new Range(0, 100_000, false),
            "reservoirTemperatureDegF", new Range(-459.67, 1000, false),
            "outletPressurePsia", new Range(0, 100_000, false), "liquidRateStbDay", new Range(0, 1_000_000, false));

    public static void validate(String well, JsonNode inputs) {
        if (well == null || !well.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || inputs == null || !inputs.isObject()
                || inputs.size() != 12
                || !"pipesim-template-profile-inputs/1".equals(inputs.path("schemaVersion").asText())
                || !"PIPESIM_FIELD".equals(inputs.path("unitsSystem").asText())) {
            throw new IllegalArgumentException("井名或模板参数合同无效");
        }
        JsonNode study = inputs.path("study");
        if (!study.isString() || study.asText().isBlank() || study.asText().length() > 64
                || study.asText().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("请明确填写有效 Study 名称");
        }
        RANGES.forEach((name, range) -> {
            JsonNode value = inputs.path(name);
            double number = value.asDouble(Double.NaN);
            if (!value.isNumber() || !Double.isFinite(number) || number > range.max()
                    || (range.inclusive() ? number < range.min() : number <= range.min())) {
                throw new IllegalArgumentException("参数超出范围：" + name);
            }
        });
    }
}
