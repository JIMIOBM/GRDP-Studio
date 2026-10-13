package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

import java.util.Set;

public final class WellScenarioParameters {
    private WellScenarioParameters() {}

    private static boolean supports(String modelKind) {
        return Set.of("black_oil_liquid", "basic_gas", "legacy_well").contains(modelKind);
    }

    public static boolean valid(JsonNode value, String modelKind, String runType) {
        return valid(value, modelKind, runType, null);
    }

    public static boolean valid(JsonNode value, String modelKind, String runType, JsonNode inspection) {
        if ("eclipse_100".equals(modelKind) && "eclipse".equals(runType)) {
            return value == null || value.isNull() || EclipseScheduleParameters.valid(value);
        }
        if ("network-well-performance-curves".equals(runType)) {
            JsonNode networkInspection = "network".equals(modelKind) ? inspection
                    : PipesimWellInspectionValidator.networkInspection(inspection);
            return NetworkWellPerformanceParameters.valid(value, networkInspection);
        }
        if (Set.of("network", "system-analysis", "network-optimizer").contains(runType)) {
            boolean networkModel = "network".equals(modelKind);
            boolean wellNetworkCapability = PipesimWellInspectionValidator.networkInspection(inspection) != null;
            if (!networkModel && !wellNetworkCapability) return false;
            if (value == null || value.isNull()) return "network".equals(runType);
            return switch (runType) {
                case "network" -> NetworkScenarioParameters.valid(value) || NetworkChokeBeanSizeParameters.valid(value)
                        || NetworkFlowlineDiameterParameters.valid(value, inspection) || NetworkConnectionParameters.valid(value);
                case "system-analysis" -> SystemAnalysisParameters.valid(value);
                case "network-optimizer" -> NetworkOptimizerParameters.valid(value);
                default -> false;
            };
        }
        boolean multiWell = ("pipesim-well-inspection/5".equals(inspection == null ? "" : inspection.path("schemaVersion").asText())
                || "pipesim-well-inspection/6".equals(inspection == null ? "" : inspection.path("schemaVersion").asText())
                || "pipesim-well-inspection/7".equals(inspection == null ? "" : inspection.path("schemaVersion").asText())
                || ("pipesim-well-inspection/8".equals(inspection == null ? "" : inspection.path("schemaVersion").asText()) || "pipesim-well-inspection/9".equals(inspection == null ? "" : inspection.path("schemaVersion").asText())))
                && inspection.path("wellContexts").isArray() && inspection.path("wellContexts").size() > 1;
        if (multiWell && !Set.of("esp-curves", "esp-parameter", "trajectory").contains(runType)) return false;
        if (Set.of("pipesim-well-inspection/6", "pipesim-well-inspection/7", "pipesim-well-inspection/8", "pipesim-well-inspection/9")
                .contains(inspection == null ? "" : inspection.path("schemaVersion").asText())
                && requiresFlowComponents(runType) && !hasFlowComponents(value, runType, inspection)) return false;
        if ("profile".equals(runType) && value != null && value.isObject()
                && WellTubingDiameterParameters.SCHEMA.equals(value.path("schemaVersion").asText())) {
            return supports(modelKind) && WellTubingDiameterParameters.valid(value, inspection);
        }
        if ("esp-curves".equals(runType)) {
            if (!("black_oil_liquid".equals(modelKind) || "basic_gas".equals(modelKind) || "legacy_well".equals(modelKind))
                    || value == null || value.isNull() || !value.isObject()) return false;
            if ("pipesim-esp-curves-parameters/1".equals(value.path("schemaVersion").asText())) return !multiWell
                    && !"pipesim-well-inspection/6".equals(inspection == null ? "" : inspection.path("schemaVersion").asText()) && value.size() == 1
                    && inspection != null && inspection.path("espContexts").isArray() && inspection.path("espContexts").size() == 1;
            return "pipesim-esp-curves-parameters/2".equals(value.path("schemaVersion").asText())
                    && value.size() == 3 && validWellContext(value.path("wellContext")) && validEspContext(value.path("espContext"))
                    && matchesEspContext(value.path("espContext").asText(), value.path("wellContext").asText(), inspection);
        }
        if ("esp-parameter".equals(runType)) {
            if (!("black_oil_liquid".equals(modelKind) || "basic_gas".equals(modelKind) || "legacy_well".equals(modelKind))
                    || value == null || value.isNull() || !value.isObject() || !validEspContext(value.path("espContext"))) return false;
            String schema = value.path("schemaVersion").asText();
            if ("pipesim-esp-parameter-parameters/1".equals(schema)) {
                return !multiWell && value.size() == 4 && "operatingFrequency".equals(value.path("parameter").asText())
                        && value.path("targetValueHz").isNumber()
                        && Double.isFinite(value.path("targetValueHz").asDouble())
                        && value.path("targetValueHz").asDouble() > 0 && matchesEspContext(value.path("espContext").asText(), null, inspection)
                        && value.path("targetValueHz").asDouble() <= 200;
            }
            if ("pipesim-esp-parameter-parameters/2".equals(schema)) return !multiWell
                    && value.size() == 4 && "headFactor".equals(value.path("parameter").asText())
                    && value.path("targetValue").isNumber()
                    && Double.isFinite(value.path("targetValue").asDouble())
                    && value.path("targetValue").asDouble() > 0 && matchesEspContext(value.path("espContext").asText(), null, inspection)
                    && value.path("targetValue").asDouble() <= 2;
            if (!"pipesim-esp-parameter-parameters/3".equals(schema) || value.size() != 5
                    || !validWellContext(value.path("wellContext")) || !value.path("targetValue").isNumber()
                    || !Double.isFinite(value.path("targetValue").asDouble()) || value.path("targetValue").asDouble() <= 0
                    || !matchesEspContext(value.path("espContext").asText(), value.path("wellContext").asText(), inspection)) return false;
            String parameter = value.path("parameter").asText();
            return ("headFactor".equals(parameter) && value.path("targetValue").asDouble() <= 2)
                    || ("operatingFrequency".equals(parameter) && value.path("targetValue").asDouble() <= 200);
        }
        if ("legacy_well".equals(modelKind)) {
            if ("trajectory".equals(runType)) {
                return trajectoryParametersValid(value, inspection);
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
                && !"vfp-tables".equals(runType) && !"esp-curves".equals(runType) && !"trajectory".equals(runType);
        if ("trajectory".equals(runType)) {
            return ("black_oil_liquid".equals(modelKind) || "basic_gas".equals(modelKind) || "legacy_well".equals(modelKind))
                    && trajectoryParametersValid(value, inspection);
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

    private static boolean validEspContext(JsonNode value) {
        if (value == null || !value.isTextual()) return false;
        String text = value.asText();
        return !text.isBlank() && text.length() <= 255 && !text.contains("..")
                && text.indexOf('/') < 0 && text.indexOf('\\') < 0
                && text.chars().noneMatch(Character::isISOControl);
    }

    private static boolean validWellContext(JsonNode value) { return validEspContext(value); }

    private static boolean requiresFlowComponents(String runType) {
        return Set.of("nodal", "profile", "combined", "sensitivity", "gas-lift-performance",
                "gas-lift-diagnostics", "vfp-tables", "esp-curves", "esp-parameter").contains(runType);
    }

    private static boolean hasFlowComponents(JsonNode parameters, String runType, JsonNode inspection) {
        if (!inspection.path("wellContexts").isArray()) return false;
        String wellContext = null;
        if (parameters != null && parameters.isObject()) {
            if (parameters.path("wellContext").isTextual()) wellContext = parameters.path("wellContext").asText();
            else if ("esp-parameter".equals(runType) && parameters.path("schemaVersion").asText().endsWith("/3")) return false;
        }
        if (wellContext == null && inspection.path("well").isTextual()) wellContext = inspection.path("well").asText();
        if (wellContext == null) return false;
        for (JsonNode well : inspection.path("wellContexts")) {
            if (!wellContext.equals(well.path("context").asText())) continue;
            return well.path("completionContexts").isArray() && !well.path("completionContexts").isEmpty()
                    && well.path("tubingContexts").isArray() && !well.path("tubingContexts").isEmpty();
        }
        return false;
    }

    private static boolean matchesEspContext(String context, String wellContext, JsonNode inspection) {
        if (inspection == null || !inspection.path("espContexts").isArray()) return false;
        if ("pipesim-well-inspection/4".equals(inspection.path("schemaVersion").asText())) {
            if (wellContext != null || !inspection.path("well").isTextual()) return false;
            for (JsonNode candidate : inspection.path("espContexts")) if (candidate.isTextual() && context.equals(candidate.asText())) return true;
            return false;
        }
        String schema = inspection.path("schemaVersion").asText();
        if (!("pipesim-well-inspection/5".equals(schema) || "pipesim-well-inspection/6".equals(schema)
                || "pipesim-well-inspection/7".equals(schema) || "pipesim-well-inspection/8".equals(schema) || "pipesim-well-inspection/9".equals(schema))
                || !inspection.path("wellContexts").isArray()) return false;
        for (JsonNode well : inspection.path("wellContexts")) {
            if (well.path("context").asText().equals(wellContext)) {
                if (Set.of("pipesim-well-inspection/6", "pipesim-well-inspection/7", "pipesim-well-inspection/8", "pipesim-well-inspection/9").contains(schema)
                        && (!well.path("completionContexts").isArray() || well.path("completionContexts").size() == 0
                        || !well.path("tubingContexts").isArray() || well.path("tubingContexts").size() == 0)) return false;
                for (JsonNode candidate : well.path("espContexts")) if (candidate.isTextual() && context.equals(candidate.asText())) return true;
            }
        }
        return false;
    }

    private static boolean trajectoryParametersValid(JsonNode value, JsonNode inspection) {
        if (value == null || value.isNull() || !value.isObject()) return false;
        if ("pipesim-well-trajectory-parameters/1".equals(value.path("schemaVersion").asText())) {
            return value.size() == 1 && (inspection == null || inspection.path("schemaVersion").asText().equals("pipesim-well-inspection/3")
                    || inspection.path("schemaVersion").asText().equals("pipesim-well-inspection/4"));
        }
        String schema = value.path("schemaVersion").asText();
        boolean selectedWellSchema = "pipesim-well-trajectory-parameters/2".equals(schema);
        boolean editSchema = "pipesim-well-trajectory-parameters/3".equals(schema);
        if ((!selectedWellSchema && !editSchema) || value.size() != (editSchema ? 4 : 2)
                || !validWellContext(value.path("wellContext")) || inspection == null
                || !("pipesim-well-inspection/5".equals(inspection.path("schemaVersion").asText())
                || "pipesim-well-inspection/6".equals(inspection.path("schemaVersion").asText())
                || "pipesim-well-inspection/7".equals(inspection.path("schemaVersion").asText())
                || ("pipesim-well-inspection/8".equals(inspection.path("schemaVersion").asText()) || "pipesim-well-inspection/9".equals(inspection.path("schemaVersion").asText())))) return false;
        if (editSchema && (!value.path("stationIndex").isIntegralNumber()
                || value.path("stationIndex").asInt() < 1 || value.path("stationIndex").asInt() > 4094
                || !value.path("targetInclinationDeg").isNumber()
                || !Double.isFinite(value.path("targetInclinationDeg").asDouble())
                || value.path("targetInclinationDeg").asDouble() < 0 || value.path("targetInclinationDeg").asDouble() > 90)) return false;
        for (JsonNode well : inspection.path("wellContexts")) if (well.path("context").asText().equals(value.path("wellContext").asText())) return true;
        return false;
    }
}
