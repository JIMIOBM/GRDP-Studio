package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** Native discovery is scoped to the immutable version, well and Study. */
public final class GasLiftCapabilityValidator {
    private GasLiftCapabilityValidator() {}

    public static boolean valid(JsonNode data, JsonNode wells) {
        if (!data.isArray() || data.isEmpty() || data.size() > 8192) return false;
        Set<String> wellNames = new HashSet<>();
        for (JsonNode well : wells) wellNames.add(well.path("context").asText());
        Set<String> studies = new HashSet<>();
        Set<String> tuples = new HashSet<>();
        for (JsonNode item : data) {
            if (!item.isObject() || item.size() != 6 || !item.path("well").isTextual()
                    || !item.path("study").isTextual() || !item.path("state").isTextual() || !item.path("reason").isTextual()
                    || !item.has("useGasliftValve") || !item.has("targetInjectionRateAvailable")) return false;
            String well = item.path("well").asText(), study = item.path("study").asText();
            if (!wellNames.contains(well) || study.isBlank() || study.length() > 255
                    || study.chars().anyMatch(Character::isISOControl) || !tuples.add(well + "\n" + study)) return false;
            JsonNode valve = item.path("useGasliftValve"), axis = item.path("targetInjectionRateAvailable");
            if (!(valve.isBoolean() || valve.isNull()) || !(axis.isBoolean() || axis.isNull())) return false;
            String reason = item.path("reason").asText();
            boolean consistent = switch (item.path("state").asText()) {
                case "supported" -> "GAS_LIFT_VALVE_SUPPORTED".equals(reason) && valve.asBoolean() && axis.asBoolean();
                case "unsupported" -> "GAS_LIFT_VALVE_REQUIRED".equals(reason) && valve.isBoolean() && !valve.asBoolean() && axis.isBoolean()
                        || "GAS_LIFT_TARGET_AXIS_UNAVAILABLE".equals(reason) && valve.asBoolean() && axis.isBoolean() && !axis.asBoolean();
                case "unknown" -> "GAS_LIFT_CAPABILITY_UNKNOWN".equals(reason) && (valve.isNull() || axis.isNull());
                default -> false;
            };
            if (!consistent) return false;
            studies.add(study);
        }
        return studies.size() <= 64 && tuples.size() == wellNames.size() * studies.size();
    }

    public static String rejection(JsonNode inspection, String well, String study) {
        if (inspection == null || !"pipesim-well-inspection/9".equals(inspection.path("schemaVersion").asText()))
            return "气举任务能力尚未检查，请先重新验证模型。已有历史仍可浏览。";
        for (JsonNode item : inspection.path("gasLiftCapabilities")) {
            if (well.equals(item.path("well").asText()) && study.equals(item.path("study").asText())) {
                if ("supported".equals(item.path("state").asText())) return null;
                if ("GAS_LIFT_VALVE_REQUIRED".equals(item.path("reason").asText()))
                    return "当前井为注气口模式，不适用当前气举阀任务；GLR 扫描是另一种工作流。";
                if ("GAS_LIFT_TARGET_AXIS_UNAVAILABLE".equals(item.path("reason").asText()))
                    return "当前井和 Study 未提供目标注气量扫描轴，不能运行当前气举阀任务。";
            }
        }
        return "当前井和 Study 的气举任务能力未知，请重新验证；求解尚未启动。";
    }
}
