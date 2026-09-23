package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

import java.util.Set;

public final class WellScenarioParameters {
    private WellScenarioParameters() {}

    public static boolean valid(JsonNode value, String modelKind, String runType) {
        if ("eclipse_100".equals(modelKind) && "eclipse".equals(runType)) {
            return value == null || value.isNull() || EclipseScheduleParameters.valid(value);
        }
        if ("legacy_well".equals(modelKind)) {
            if ("trajectory".equals(runType)) {
                return value != null && !value.isNull() && value.isObject() && value.size() == 1
                        && "pipesim-well-trajectory-parameters/1".equals(value.path("schemaVersion").asText());
            }
            if ("profile".equals(runType) || "combined".equals(runType)) {
                if (value == null || value.isNull()) return true;
                return value.isObject() && value.size() == 2
                        && "pipesim-well-profile-parameters/1".equals(value.path("schemaVersion").asText())
                        && value.path("outletPressurePsi").isNumber()
                        && Double.isFinite(value.path("outletPressurePsi").asDouble())
                        && value.path("outletPressurePsi").asDouble() > 0
                        && value.path("outletPressurePsi").asDouble() <= 100000;
            }
            return "nodal".equals(runType) && (value == null || value.isNull());
        }
        if (value == null || value.isNull()) return !"sensitivity".equals(runType)
                && !"system-analysis".equals(runType) && !"gas-lift-diagnostics".equals(runType)
                && !"vfp-tables".equals(runType) && !"trajectory".equals(runType);
        if ("esp-curves".equals(runType)) {
            return ("black_oil_liquid".equals(modelKind) || "basic_gas".equals(modelKind)) && value.isObject() && value.size() == 1
                    && "pipesim-esp-curves-parameters/1".equals(value.path("schemaVersion").asText());
        }
        if ("trajectory".equals(runType)) {
            return ("black_oil_liquid".equals(modelKind) || "basic_gas".equals(modelKind) || "legacy_well".equals(modelKind))
                    && value.isObject() && value.size() == 1
                    && "pipesim-well-trajectory-parameters/1".equals(value.path("schemaVersion").asText());
        }
        if ("sensitivity".equals(runType)) return sensitivityValid(value, modelKind);
        if ("gas-lift-performance".equals(runType)) {
            return "black_oil_liquid".equals(modelKind) && GasLiftPerformanceParameters.valid(value);
        }
        if ("gas-lift-diagnostics".equals(runType)) {
            return "black_oil_liquid".equals(modelKind) && GasLiftDiagnosticsParameters.valid(value);
        }
        if ("vfp-tables".equals(runType)) {
            return "black_oil_liquid".equals(modelKind) && VfpTablesParameters.valid(value);
        }
        if ("network".equals(runType)) return "network".equals(modelKind)
                && (NetworkScenarioParameters.valid(value) || NetworkChokeBeanSizeParameters.valid(value));
        if ("system-analysis".equals(runType)) return "network".equals(modelKind) && SystemAnalysisParameters.valid(value);
        if ("network-optimizer".equals(runType)) return "network".equals(modelKind) && NetworkOptimizerParameters.valid(value);
        boolean supported = "basic_gas".equals(modelKind)
                ? ("nodal".equals(runType) || "profile".equals(runType) || "combined".equals(runType))
                : "black_oil_liquid".equals(modelKind) && "nodal".equals(runType);
        if (!supported) return false;
        if (!value.isObject() || value.size() != 2 || !value.has("schemaVersion") || !value.has("reservoirPressurePsi")) return false;
        JsonNode pressure = value.get("reservoirPressurePsi");
        return "pipesim-well-parameters/1".equals(value.path("schemaVersion").asText())
                && pressure.isNumber() && Double.isFinite(pressure.asDouble())
                && pressure.asDouble() > 0 && pressure.asDouble() <= 100000;
    }

    private static boolean sensitivityValid(JsonNode value, String modelKind) {
        if (!"black_oil_liquid".equals(modelKind) && !"basic_gas".equals(modelKind)) return false;
        if (!value.isObject() || value.size() != 3
                || !"pipesim-well-sensitivity-parameters/1".equals(value.path("schemaVersion").asText())) return false;
        String target = value.path("targetVariable").asText();
        boolean targetSupported = Set.of("reservoirPressure", "tubingInnerDiameter").contains(target)
                || "black_oil_liquid".equals(modelKind) && Set.of("waterCut", "gor").contains(target);
        if (!targetSupported || !value.path("values").isArray()
                || value.path("values").size() < 2 || value.path("values").size() > 12) return false;
        double previous = Double.NEGATIVE_INFINITY;
        for (JsonNode item : value.path("values")) {
            if (!item.isNumber() || !Double.isFinite(item.asDouble()) || item.asDouble() <= 0 || item.asDouble() <= previous) return false;
            previous = item.asDouble();
        }
        double max = switch (target) {
            case "reservoirPressure" -> 100000;
            case "waterCut" -> 100;
            case "gor" -> 1000000;
            case "tubingInnerDiameter" -> 100;
            default -> 0;
        };
        return previous <= max;
    }
}
