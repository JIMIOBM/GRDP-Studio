package com.grdp.studio.softwareintegration.execution;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

@Component
public class PipesimNetworkOptimizerResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "simulationState",
            "summary", "messages", "variables", "wells", "flowlines", "sinks", "quality");
    private static final Set<String> SUMMARY_FIELDS = Set.of("info", "warnings", "errors");
    private static final Set<String> VARIABLE_FIELDS = Set.of("key", "label", "unit");
    private static final Set<String> GROUP_FIELDS = Set.of("name", "values");
    private static final Set<String> VALUE_FIELDS = Set.of("key", "value");
    private static final Set<String> QUALITY_FIELDS = Set.of("path", "code");
    private static final Set<String> APPLICATION_FIELDS = Set.of(
            "requested", "applied", "scope", "sourceModelUnchanged", "artifactName", "changes");
    private static final Set<String> APPLICATION_CHANGE_FIELDS = Set.of(
            "context", "parameter", "unit", "before", "after");

    public PipesimWellResultValidator.ValidatedResult validate(JsonNode result) {
        require(result != null && result.isObject(), "Network Optimizer result has an invalid shape");
        String schemaVersion = result.path("schemaVersion").asText();
        boolean applied = "pipesim-network-optimizer-result/2".equals(schemaVersion);
        Set<String> rootFields = applied
                ? withApplication(ROOT_FIELDS)
                : ROOT_FIELDS;
        requireObject(result, rootFields, "Network Optimizer result");
        requireText(result, "schemaVersion", applied
                ? "pipesim-network-optimizer-result/2"
                : "pipesim-network-optimizer-result/1");
        requireText(result, "model_kind", "network");
        requireText(result, "runTask", "network-optimizer");
        requireText(result, "resultContract", "VALID_FULL");
        requireText(result, "simulationState", "Completed");
        validateSummary(result.path("summary"));
        validateStrings(result.path("messages"), "messages");
        Set<String> variables = validateVariables(result.path("variables"));
        Set<String> dataPaths = new HashSet<>();
        Set<String> missingPaths = new HashSet<>();
        validateGroups(result.path("wells"), "wells", variables, dataPaths, missingPaths);
        validateGroups(result.path("flowlines"), "flowlines", variables, dataPaths, missingPaths);
        validateGroups(result.path("sinks"), "sinks", variables, dataPaths, missingPaths);
        require(validateQuality(result.path("quality")).equals(missingPaths),
                "quality entries must exactly identify unavailable optimizer values");
        if (applied) validateApplication(result.path("application"));
        return new PipesimWellResultValidator.ValidatedResult(
                SoftwareIntegrationRunStatus.SUCCEEDED, "VALID_FULL", result);
    }

    private static Set<String> withApplication(Set<String> base) {
        Set<String> fields = new HashSet<>(base);
        fields.add("application");
        return fields;
    }

    private void validateApplication(JsonNode application) {
        requireObject(application, APPLICATION_FIELDS, "Network Optimizer application");
        require(application.path("requested").isBoolean() && application.path("requested").asBoolean(),
                "Network Optimizer application was not requested");
        require(application.path("applied").isBoolean() && application.path("applied").asBoolean(),
                "Network Optimizer application was not completed");
        requireText(application, "scope", "isolated-model-copy");
        require(application.path("sourceModelUnchanged").isBoolean()
                        && application.path("sourceModelUnchanged").asBoolean(),
                "Network Optimizer application must be isolated from the source model");
        requireSafeText(application, "artifactName", "Network Optimizer application artifact");
        require("pipesim-network-optimizer-applied.pips".equals(application.path("artifactName").asText()),
                "Invalid Network Optimizer application artifact");
        JsonNode changes = application.path("changes");
        require(changes.isArray() && changes.size() <= 256, "Network Optimizer application changes have an invalid shape");
        for (JsonNode change : changes) {
            requireObject(change, APPLICATION_CHANGE_FIELDS, "Network Optimizer application change");
            requireSafeText(change, "context", "Network Optimizer application context");
            requireText(change, "parameter", "GasRate");
            requireSafeText(change, "unit", "Network Optimizer application unit", true, true);
            requireFiniteNumber(change.path("before"), "Network Optimizer application before value");
            requireFiniteNumber(change.path("after"), "Network Optimizer application after value");
        }
    }

    private Set<String> validateVariables(JsonNode array) {
        require(array.isArray() && !array.isEmpty() && array.size() <= 128, "variables has an invalid shape");
        Set<String> keys = new HashSet<>();
        for (JsonNode item : array) {
            requireObject(item, VARIABLE_FIELDS, "optimizer variable");
            String key = requireSafeText(item, "key", "optimizer variable key");
            require(keys.add(key), "optimizer variable keys must be unique");
            requireSafeText(item, "label", "optimizer variable label");
            // Engineering units such as mmscf/d and SCF/STB legitimately contain '/'.
            requireSafeText(item, "unit", "optimizer variable unit", true, true);
        }
        return keys;
    }

    private void validateGroups(JsonNode array, String groupName, Set<String> variables,
                                Set<String> dataPaths, Set<String> missingPaths) {
        require(array.isArray() && !array.isEmpty() && array.size() <= 256,
                groupName + " has an invalid shape");
        Set<String> names = new HashSet<>();
        for (JsonNode group : array) {
            requireObject(group, GROUP_FIELDS, groupName + " group");
            String name = requireSafeText(group, "name", groupName + " name");
            require(names.add(name), groupName + " names must be unique");
            JsonNode values = group.path("values");
            // The official Network Optimizer API returns a sparse set of variables per
            // object. A variable may be valid for a well but not for a flowline or sink,
            // so only membership and uniqueness can be required here.
            require(values.isArray(), groupName + " values have an invalid shape");
            Set<String> seen = new HashSet<>();
            for (JsonNode value : values) {
                requireObject(value, VALUE_FIELDS, groupName + " value");
                String key = requireSafeText(value, "key", groupName + " value key");
                require(variables.contains(key) && seen.add(key), groupName + " contains an unknown or duplicate variable");
                JsonNode scalar = value.get("value");
                String path = groupName + "." + name + "." + key;
                validateScalar(scalar, path, dataPaths, missingPaths);
            }
            require(seen.size() <= variables.size(), groupName + " contains too many optimizer variables");
        }
    }

    private void validateScalar(JsonNode value, String path, Set<String> dataPaths, Set<String> missingPaths) {
        require(dataPaths.add(path), "result contains duplicate data path " + path);
        if (value == null || value.isNull()) {
            require(missingPaths.add(path), "result contains duplicate unavailable path " + path);
            return;
        }
        require(value.isBoolean() || value.isNumber() && Double.isFinite(value.doubleValue()),
                path + " must contain a finite number, boolean, or null");
    }

    private Set<String> validateQuality(JsonNode array) {
        require(array.isArray(), "quality must be an array");
        Set<String> paths = new HashSet<>();
        for (JsonNode item : array) {
            requireObject(item, QUALITY_FIELDS, "quality item");
            String path = requireSafeText(item, "path", "quality path");
            require(paths.add(path), "quality paths must be unique");
            require("UNAVAILABLE".equals(requireSafeText(item, "code", "quality code")),
                    "Invalid optimizer quality code");
        }
        return paths;
    }

    private void validateSummary(JsonNode summary) {
        requireObject(summary, SUMMARY_FIELDS, "summary");
        for (String field : SUMMARY_FIELDS) validateStrings(summary.path(field), "summary." + field);
    }

    private void validateStrings(JsonNode values, String name) {
        require(values.isArray() && values.size() <= 256, name + " must be an array");
        for (JsonNode value : values) require(value.isTextual() && safe(value.asText()), name + " contains unsafe text");
    }

    private static void requireObject(JsonNode node, Set<String> fields, String name) {
        require(node != null && node.isObject() && node.size() == fields.size(), name + " has an invalid shape");
        for (String field : fields) require(node.has(field), name + " is missing " + field);
    }

    private static String requireSafeText(JsonNode node, String field, String name) {
        return requireSafeText(node, field, name, false);
    }

    private static String requireSafeText(JsonNode node, String field, String name, boolean allowEmpty) {
        return requireSafeText(node, field, name, allowEmpty, false);
    }

    private static String requireSafeText(JsonNode node, String field, String name,
                                          boolean allowEmpty, boolean allowSlash) {
        require(node.has(field) && node.get(field).isTextual(), name + " must be text");
        String value = node.get(field).asText();
        require((allowEmpty || !value.isBlank()) && safe(value, allowSlash), name + " is invalid");
        return value;
    }

    private static boolean safe(String value) {
        return safe(value, false);
    }

    private static boolean safe(String value, boolean allowSlash) {
        return value.length() <= 1000 && value.indexOf('\u0000') < 0
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0
                && !value.contains("..") && (allowSlash || value.indexOf('/') < 0) && value.indexOf('\\') < 0;
    }

    private static void requireText(JsonNode node, String field, String expected) {
        require(node.path(field).isTextual() && expected.equals(node.path(field).asText()), "Invalid " + field);
    }

    private static void requireFiniteNumber(JsonNode node, String name) {
        require(node != null && node.isNumber() && Double.isFinite(node.doubleValue()), name + " must be a finite number");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new PipesimWellResultValidator.ResultValidationException(message);
    }
}
