package com.grdp.studio.softwareintegration.execution;

import com.grdp.studio.softwareintegration.support.SoftwareIntegrationEclipseSanitizer;
import com.grdp.studio.softwareintegration.support.SystemAnalysisParameters;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

@Component
public class PipesimSystemAnalysisResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "study",
            "producer", "branchTerminator", "outletPressurePsi", "scanVariable", "cases");

    public PipesimWellResultValidator.ValidatedResult validate(String expectedStudy, JsonNode result) {
        return validate(expectedStudy, result, null);
    }

    public PipesimWellResultValidator.ValidatedResult validate(String expectedStudy, JsonNode result, JsonNode parameters) {
        requireObject(result, ROOT_FIELDS, "system analysis result");
        boolean dynamic = "pipesim-system-analysis-result/2".equals(result.path("schemaVersion").asText());
        if (!dynamic) requireText(result, "schemaVersion", "pipesim-system-analysis-result/1");
        if (dynamic || parameters != null && !parameters.isNull()) {
            require(SystemAnalysisParameters.valid(parameters), "System Analysis requires valid request parameters");
            requireText(parameters, "schemaVersion", dynamic
                    ? "pipesim-system-analysis-parameters/2" : "pipesim-system-analysis-parameters/1");
            bindRequest(result, parameters);
        }
        requireText(result, "model_kind", "network");
        requireText(result, "runTask", "system-analysis");
        requireText(result, "resultContract", "VALID_FULL");
        requireSafeText(result, "study", expectedStudy, "study");
        requireSafeText(result, "producer", dynamic ? parameters.path("producer").asText() : "Well", "producer");
        requireSafeText(result, "branchTerminator", null, "branchTerminator");
        requireText(result, "scanVariable", "liquidFlowRate");
        requireNumber(result.get("outletPressurePsi"), 0.0, 100000.0, "outletPressurePsi");

        JsonNode cases = result.get("cases");
        require(cases != null && cases.isArray() && cases.size() >= 2 && cases.size() <= 8,
                "cases has an invalid shape");
        double previous = Double.NEGATIVE_INFINITY;
        for (JsonNode item : cases) {
            requireObject(item, Set.of("caseName", "scanValue", "system", "node", "profile"), "system analysis case");
            requireSafeText(item, "caseName", null, "caseName");
            requireNumber(item.get("scanValue"), 0.0, 1000000000.0, "scanValue");
            require(item.get("scanValue").doubleValue() > previous,
                    "System analysis scan values must be strictly increasing");
            previous = item.get("scanValue").doubleValue();
            validateSystem(item.get("system"));
            validateNodes(item.get("node"));
            validateProfile(item.get("profile"));
            if (dynamic) validateDynamicUnits(item);
        }
        return new PipesimWellResultValidator.ValidatedResult(
                SoftwareIntegrationRunStatus.SUCCEEDED, "VALID_FULL", result);
    }

    private static void bindRequest(JsonNode result, JsonNode parameters) {
        for (String field : new String[]{"producer", "branchTerminator", "scanVariable"}) {
            requireText(result, field, parameters.path(field).asText());
        }
        require(result.path("outletPressurePsi").isNumber()
                && result.path("outletPressurePsi").doubleValue() == parameters.path("outletPressurePsi").doubleValue(),
                "System Analysis outlet pressure differs from request");
        JsonNode cases = result.get("cases");
        JsonNode values = parameters.get("values");
        require(cases != null && cases.isArray() && cases.size() == values.size(), "System Analysis case count differs from request");
        for (int index = 0; index < cases.size(); index++) {
            require(cases.get(index).path("scanValue").isNumber()
                    && cases.get(index).path("scanValue").doubleValue() == values.get(index).doubleValue(),
                    "System Analysis scan differs from request");
        }
    }

    private static void validateDynamicUnits(JsonNode item) {
        Set<String> profileNames = new java.util.HashSet<>();
        for (JsonNode variable : item.path("profile").path("variables")) {
            String name = variable.path("variable").asText();
            require(profileNames.add(name), "profile contains duplicate variable");
            if (Set.of("TotalDistance", "Pressure", "Temperature").contains(name)) {
                require(variable.path("unit").isTextual() && !variable.path("unit").asText().isBlank(), "profile unit missing");
            }
            if (!"BranchEquipment".equals(name)) {
                requireAvailableUnit(variable);
                for (JsonNode value : variable.path("values")) requireNativeNumber(value);
            }
        }
        require(profileNames.containsAll(Set.of("TotalDistance", "Pressure", "Temperature")), "profile lacks required variables");
        for (JsonNode variable : item.path("system")) {
            requireAvailableUnit(variable);
            requireNativeNumber(variable.path("value"));
        }
        for (JsonNode node : item.path("node")) {
            for (JsonNode variable : node.path("variables")) {
                requireAvailableUnit(variable);
                requireNativeNumber(variable.path("value"));
            }
        }
    }

    private static void requireNativeNumber(JsonNode value) {
        requireNumber(value, null, null, "native value");
        double number = value.doubleValue();
        require(number != -1e31 && Math.abs(number - 1.2345e25) > 1.2345e25 * 1e-12,
                "native value is unavailable");
    }

    private static void requireAvailableUnit(JsonNode variable) {
        String expected = switch (variable.path("variable").asText()) {
            case "TotalDistance" -> "ft";
            case "Pressure", "BottomHolePressure" -> "psia";
            case "Temperature" -> "degF";
            case "VolumeFlowrateLiquidStockTank" -> "STB/d";
            default -> null;
        };
        require(expected != null && expected.equals(variable.path("unit").asText()), "unverified native Field unit");
    }

    private void validateSystem(JsonNode values) {
        require(values != null && values.isArray() && !values.isEmpty(), "system must not be empty");
        for (JsonNode value : values) {
            requireObject(value, Set.of("variable", "unit", "value"), "system value");
            requireSafeText(value, "variable", null, "system variable");
            requireUnit(value.get("unit"), "system unit");
            requireNumber(value.get("value"), null, null, "system value");
        }
    }

    private void validateNodes(JsonNode nodes) {
        require(nodes != null && nodes.isArray() && !nodes.isEmpty(), "node must not be empty");
        for (JsonNode node : nodes) {
            requireObject(node, Set.of("node", "variables"), "node result");
            requireSafeText(node, "node", null, "node name");
            JsonNode variables = node.get("variables");
            require(variables.isArray() && !variables.isEmpty(), "node variables must not be empty");
            for (JsonNode value : variables) {
                requireObject(value, Set.of("variable", "unit", "value"), "node variable");
                requireSafeText(value, "variable", null, "node variable name");
                requireUnit(value.get("unit"), "node variable unit");
                requireNumber(value.get("value"), null, null, "node variable value");
            }
        }
    }

    private void validateProfile(JsonNode profile) {
        requireObject(profile, Set.of("pointCount", "variables"), "profile");
        JsonNode pointCount = profile.get("pointCount");
        require(pointCount.isIntegralNumber() && pointCount.intValue() > 0 && pointCount.intValue() <= 100000,
                "profile pointCount is invalid");
        JsonNode variables = profile.get("variables");
        require(variables.isArray() && !variables.isEmpty(), "profile variables must not be empty");
        JsonNode distance = null;
        JsonNode pressure = null;
        for (JsonNode variable : variables) {
            requireObject(variable, Set.of("variable", "unit", "values"), "profile variable");
            String name = requireSafeText(variable, "variable", null, "profile variable name");
            requireUnit(variable.get("unit"), "profile variable unit");
            JsonNode values = variable.get("values");
            require(values.isArray() && values.size() == pointCount.intValue() && !values.isEmpty(),
                    "profile variable values have an invalid length");
            for (JsonNode value : values) {
                if ("BranchEquipment".equals(name)) {
                    require(value.isNull() || value.isTextual() && safeText(value.asText(), true),
                            "profile equipment value is invalid");
                } else {
                    requireNumber(value, null, null, "profile numeric value");
                }
            }
            if ("TotalDistance".equals(name)) {
                require(distance == null, "profile contains duplicate TotalDistance");
                distance = values;
            } else if ("Pressure".equals(name)) {
                require(pressure == null, "profile contains duplicate Pressure");
                pressure = values;
            }
        }
        require(distance != null && pressure != null && distance.size() == pressure.size(),
                "profile must contain matching TotalDistance and Pressure");
    }

    private static void requireUnit(JsonNode value, String name) {
        require(value == null || value.isNull() || value.isTextual() && safeText(value.asText(), true),
                name + " is invalid");
    }

    private static void requireNumber(JsonNode value, Double lowerExclusive, Double upperInclusive, String name) {
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue())
                        && (lowerExclusive == null || value.doubleValue() > lowerExclusive)
                        && (upperInclusive == null || value.doubleValue() <= upperInclusive),
                name + " must be a finite number");
    }

    private static String requireSafeText(JsonNode object, String field, String expected, String name) {
        JsonNode value = object.get(field);
        require(value != null && value.isTextual() && safeText(value.asText(), false)
                        && (expected == null || expected.equals(value.asText())),
                name + " is invalid");
        return value.asText();
    }

    private static boolean safeText(String value, boolean allowEmpty) {
        return value != null && (allowEmpty || !value.isBlank())
                && SoftwareIntegrationEclipseSanitizer.isSafeIdentifier(value);
    }

    private static void requireText(JsonNode object, String field, String expected) {
        require(object.get(field) != null && object.get(field).isTextual()
                        && expected.equals(object.get(field).asText()),
                "Invalid " + field);
    }

    private static void requireObject(JsonNode node, Set<String> fields, String name) {
        require(node != null && node.isObject() && node.size() == fields.size(), name + " has an invalid shape");
        for (String field : fields) require(node.has(field), name + " is missing " + field);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new PipesimWellResultValidator.ResultValidationException(message);
    }
}
