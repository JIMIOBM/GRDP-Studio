package com.grdp.studio.softwareintegration.execution;

import com.grdp.studio.softwareintegration.support.SoftwareIntegrationEclipseSanitizer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

@Component
public class PipesimNetworkResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "study", "simulationState",
            "topology", "system", "node", "profiles", "summary", "messages", "quality");
    private static final Set<String> TOPOLOGY_FIELDS = Set.of("nodes", "edges", "counts");
    private static final Set<String> NODE_FIELDS = Set.of("id", "componentType");
    private static final Set<String> EDGE_FIELDS = Set.of("source", "destination", "sourcePort");
    private static final Set<String> COUNT_FIELDS = Set.of("nodes", "edges", "sources", "sinks", "flowlines");
    private static final Set<String> SERIES_FIELDS = Set.of("variable", "unit", "values");
    private static final Set<String> NAMED_VALUE_FIELDS = Set.of("name", "value");
    private static final Set<String> PROFILE_FIELDS = Set.of("branch", "pointCount", "variables");
    private static final Set<String> SUMMARY_FIELDS = Set.of("info", "warnings", "errors");
    private static final Set<String> QUALITY_FIELDS = Set.of("path", "code");
    private static final Set<String> QUALITY_CODES = Set.of("NON_FINITE", "UNAVAILABLE");

    public ValidatedResult validate(String expectedRunTask, String expectedStudy, JsonNode result) {
        require("network".equals(expectedRunTask), "Unsupported network runTask");
        require(result != null && result.isObject(), "result has an invalid shape");
        String contract = requireString(result, "resultContract", "resultContract");
        if ("VALID_PARTIAL".equals(contract)) return validatePartial(expectedStudy, result);
        require("VALID_FULL".equals(contract), "Invalid resultContract");
        requireObject(result, ROOT_FIELDS, "result");
        requireText(result, "schemaVersion", "pipesim-network-result/1");
        requireText(result, "model_kind", "network");
        requireText(result, "runTask", expectedRunTask);
        requireText(result, "study", expectedStudy);
        requireText(result, "simulationState", "Completed");
        validateTopology(result.get("topology"));
        Set<String> missingPaths = new HashSet<>();
        Set<String> dataPaths = new HashSet<>();
        validateNamedSeries(result.get("system"), "system", dataPaths, missingPaths);
        validateNamedSeries(result.get("node"), "node", dataPaths, missingPaths);
        validateProfiles(result.get("profiles"), dataPaths, missingPaths, true);
        validateSummary(result.get("summary"));
        validateStringArray(result.get("messages"), "messages");
        require(validateQuality(result.get("quality")).equals(missingPaths),
                "quality entries must exactly identify cleaned numeric nulls");
        return new ValidatedResult(SoftwareIntegrationRunStatus.SUCCEEDED, "VALID_FULL", result);
    }

    private ValidatedResult validatePartial(String expectedStudy, JsonNode result) {
        requireAllowedObject(result, ROOT_FIELDS, "result");
        requireText(result, "schemaVersion", "pipesim-network-result/1");
        requireText(result, "model_kind", "network");
        requireText(result, "runTask", "network");
        requireText(result, "study", expectedStudy);
        requireText(result, "simulationState", "Completed");
        validateTopology(result.get("topology"));
        Set<String> dataPaths = new HashSet<>();
        Set<String> missingPaths = new HashSet<>();
        if (result.has("system")) validateNamedSeries(result.get("system"), "system", dataPaths, missingPaths);
        if (result.has("node")) validateNamedSeries(result.get("node"), "node", dataPaths, missingPaths);
        if (result.has("profiles")) validateProfiles(result.get("profiles"), dataPaths, missingPaths, false);
        if (result.has("summary")) validateSummary(result.get("summary"));
        if (result.has("messages")) validateStringArray(result.get("messages"), "messages");
        if (result.has("quality")) validateQuality(result.get("quality"));
        return new ValidatedResult(SoftwareIntegrationRunStatus.PARTIAL_SUCCEEDED, "VALID_PARTIAL", result);
    }

    private void validateTopology(JsonNode topology) {
        requireObject(topology, TOPOLOGY_FIELDS, "topology");
        JsonNode nodes = requireArray(topology.get("nodes"), "topology.nodes");
        require(!nodes.isEmpty(), "topology.nodes must not be empty");
        Set<String> nodeIds = new HashSet<>();
        for (JsonNode node : nodes) {
            requireObject(node, NODE_FIELDS, "topology node");
            require(nodeIds.add(requireSafeString(node, "id", "topology node id")),
                    "topology node ids must be unique");
            requireSafeString(node, "componentType", "topology node componentType");
        }

        JsonNode edges = requireArray(topology.get("edges"), "topology.edges");
        require(!edges.isEmpty(), "topology.edges must not be empty");
        for (JsonNode edge : edges) {
            requireObject(edge, EDGE_FIELDS, "topology edge");
            String source = requireSafeString(edge, "source", "topology edge source");
            String destination = requireSafeString(edge, "destination", "topology edge destination");
            requireSafeText(edge, "sourcePort", "topology edge sourcePort");
            require(nodeIds.contains(source), "topology edge source must reference a listed node");
            require(nodeIds.contains(destination), "topology edge destination must reference a listed node");
        }

        JsonNode counts = topology.get("counts");
        requireObject(counts, COUNT_FIELDS, "topology.counts");
        for (String field : COUNT_FIELDS) requireNonNegativeInt(counts, field, "topology.counts." + field);
        require(counts.get("nodes").intValue() >= nodes.size(), "topology.counts.nodes is less than topology.nodes");
        require(counts.get("edges").intValue() >= edges.size(), "topology.counts.edges is less than topology.edges");
    }

    private void validateNamedSeries(JsonNode groups, String name, Set<String> dataPaths,
                                     Set<String> missingPaths) {
        JsonNode array = requireArray(groups, name);
        for (JsonNode group : array) {
            requireObject(group, SERIES_FIELDS, name + " series");
            String variable = requireSafeText(group, "variable", name + " variable");
            requireSafeText(group, "unit", name + " unit");
            JsonNode values = requireArray(group.get("values"), name + " values");
            for (JsonNode value : values) {
                requireObject(value, NAMED_VALUE_FIELDS, name + " named value");
                String valueName = requireSafeText(value, "name", name + " value name");
                require(value.get("value") != null, name + " named value is missing value");
                validateNumericValue(value.get("value"), name + "." + variable + "." + valueName,
                        dataPaths, missingPaths);
            }
        }
    }

    private void validateProfiles(JsonNode profiles, Set<String> dataPaths, Set<String> missingPaths,
                                  boolean requirePlotSeries) {
        JsonNode array = requireArray(profiles, "profiles");
        if (requirePlotSeries) require(!array.isEmpty(), "profiles must not be empty");
        for (JsonNode profile : array) {
            requireObject(profile, PROFILE_FIELDS, "profile");
            String branch = requireSafeText(profile, "branch", "profile branch");
            requireNonNegativeInt(profile, "pointCount", "profile pointCount");
            JsonNode variables = requireArray(profile.get("variables"), "profile variables");
            JsonNode totalDistance = null;
            JsonNode pressure = null;
            for (JsonNode variable : variables) {
                requireObject(variable, SERIES_FIELDS, "profile variable");
                String variableName = requireSafeText(variable, "variable", "profile variable name");
                requireSafeText(variable, "unit", "profile variable unit");
                JsonNode values = requireArray(variable.get("values"), "profile variable values");
                String path = "profiles." + branch + "." + variableName;
                if ("BranchEquipment".equals(variableName)) validateTextValue(values, path, dataPaths);
                else validateNumericValue(values, path, dataPaths, missingPaths);
                if ("TotalDistance".equals(variableName)) {
                    require(totalDistance == null, "profile contains duplicate TotalDistance");
                    totalDistance = values;
                } else if ("Pressure".equals(variableName)) {
                    require(pressure == null, "profile contains duplicate Pressure");
                    pressure = values;
                }
            }
            if (requirePlotSeries) {
                require(totalDistance != null && !totalDistance.isEmpty(), "profile TotalDistance must not be empty");
                require(pressure != null && !pressure.isEmpty(), "profile Pressure must not be empty");
                require(totalDistance.size() == pressure.size(), "profile TotalDistance and Pressure lengths differ");
            }
        }
    }

    private void validateSummary(JsonNode summary) {
        requireObject(summary, SUMMARY_FIELDS, "summary");
        for (String field : SUMMARY_FIELDS) validateStringArray(summary.get(field), "summary." + field);
    }

    private Set<String> validateQuality(JsonNode quality) {
        JsonNode array = requireArray(quality, "quality");
        Set<String> paths = new HashSet<>();
        for (JsonNode item : array) {
            requireObject(item, QUALITY_FIELDS, "quality item");
            String path = requireSafeString(item, "path", "quality path");
            require(paths.add(path), "quality paths must be unique");
            require(QUALITY_CODES.contains(requireSafeString(item, "code", "quality code")), "Invalid quality code");
        }
        return paths;
    }

    private boolean validateNumericValue(JsonNode value, String path, Set<String> dataPaths,
                                         Set<String> missingPaths) {
        if (value.isNull()) {
            require(dataPaths.add(path), "result contains duplicate data path " + path);
            require(missingPaths.add(path), "result contains duplicate numeric null path " + path);
            return true;
        }
        if (value.isArray()) {
            boolean hasNumericLeaf = false;
            for (int index = 0; index < value.size(); index++) {
                hasNumericLeaf |= validateNumericValue(value.get(index), path + "[" + index + "]",
                        dataPaths, missingPaths);
            }
            require(hasNumericLeaf, path + " must contain finite numbers or null");
            return true;
        }
        if (value.isObject()) {
            boolean hasNumericLeaf = false;
            for (var field : value.properties()) {
                requireSafeText(field.getKey(), path + " field");
                hasNumericLeaf |= validateNumericValue(field.getValue(), path + "." + field.getKey(),
                        dataPaths, missingPaths);
            }
            require(hasNumericLeaf, path + " must contain finite numbers or null");
            return true;
        }
        require(dataPaths.add(path), "result contains duplicate data path " + path);
        require(value.isNumber() && Double.isFinite(value.doubleValue()), path + " must contain finite numbers or null");
        double number = value.doubleValue();
        require(!isUnavailableSentinel(number), path + " contains an uncleaned unavailable sentinel");
        return true;
    }

    private void validateTextValue(JsonNode value, String path, Set<String> dataPaths) {
        if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                validateTextValue(value.get(index), path + "[" + index + "]", dataPaths);
            }
            return;
        }
        require(dataPaths.add(path), "result contains duplicate data path " + path);
        require(value.isNull() || value.isTextual()
                        && SoftwareIntegrationEclipseSanitizer.isSafeIdentifier(value.asText()),
                path + " must contain safe text or null");
    }

    private static boolean isUnavailableSentinel(double value) {
        return close(value, 1.2345e25) || close(value, -1.0e31);
    }

    private static boolean close(double value, double expected) {
        return Math.abs(value - expected) <= Math.abs(expected) * 1.0e-12;
    }

    private void validateStringArray(JsonNode values, String name) {
        JsonNode array = requireArray(values, name);
        for (JsonNode value : array) {
            require(value != null && value.isTextual()
                    && SoftwareIntegrationEclipseSanitizer.isSafeIdentifier(value.asText()),
                    name + " must contain safe strings");
        }
    }

    private static JsonNode requireArray(JsonNode node, String name) {
        require(node != null && node.isArray(), name + " must be an array");
        return node;
    }

    private static void requireObject(JsonNode node, Set<String> fields, String name) {
        require(node != null && node.isObject() && node.size() == fields.size(), name + " has an invalid shape");
        for (String field : fields) require(node.has(field), name + " is missing " + field);
    }

    private static void requireAllowedObject(JsonNode node, Set<String> fields, String name) {
        require(node != null && node.isObject(), name + " has an invalid shape");
        node.properties().forEach(field -> require(fields.contains(field.getKey()), name + " has an invalid shape"));
    }

    private static String requireString(JsonNode node, String field, String name) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual(), name + " must be a string");
        return value.asText();
    }

    private static String requireSafeString(JsonNode node, String field, String name) {
        String value = requireString(node, field, name);
        require(!value.isBlank() && SoftwareIntegrationEclipseSanitizer.isSafeIdentifier(value), name + " is invalid");
        return value;
    }

    private static String requireSafeText(JsonNode node, String field, String name) {
        return requireSafeText(requireString(node, field, name), name);
    }

    private static String requireSafeText(String value, String name) {
        require(SoftwareIntegrationEclipseSanitizer.isSafeIdentifier(value), name + " is invalid");
        return value;
    }

    private static void requireText(JsonNode node, String field, String expected) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual() && expected.equals(value.asText()), "Invalid " + field);
    }

    private static void requireNonNegativeInt(JsonNode node, String field, String name) {
        JsonNode value = node.get(field);
        require(value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0,
                name + " must be a non-negative integer");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new ResultValidationException(message);
    }

    public record ValidatedResult(SoftwareIntegrationRunStatus terminalStatus, String contract, JsonNode result) {}

    public static class ResultValidationException extends RuntimeException {
        public ResultValidationException(String message) { super(message); }
    }
}
