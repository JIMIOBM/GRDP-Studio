package com.grdp.studio.softwareintegration.execution;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

@Component
public class PipesimNetworkWellPerformanceResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "model_kind", "runTask", "resultContract", "study",
            "producerContext", "branchTerminator", "sensitivity", "flowratePoints", "simulationState", "system", "cases",
            "summary", "messages", "quality", "pwiArtifact");

    public PipesimWellResultValidator.ValidatedResult validate(String expectedStudy, JsonNode result) {
        require(result != null && result.isObject() && fieldsEqual(result, ROOT_FIELDS), "result has an invalid shape");
        requireText(result, "schemaVersion", "pipesim-network-well-performance-result/1");
        requireText(result, "model_kind", "network");
        requireText(result, "runTask", "network-well-performance-curves");
        requireText(result, "resultContract", "VALID_FULL");
        requireText(result, "study", expectedStudy);
        safeText(result, "producerContext");
        safeText(result, "branchTerminator");
        requireText(result, "simulationState", "Completed");
        require(result.path("flowratePoints").isIntegralNumber() && result.path("flowratePoints").intValue() == 50,
                "flowratePoints does not match the supported PIPESIM 2022.1 output contract");

        JsonNode sensitivity = result.path("sensitivity");
        require(sensitivity.isObject() && fieldsEqual(sensitivity, Set.of("variable", "unit", "values")), "sensitivity has an invalid shape");
        requireText(sensitivity, "variable", "InnerDiameter");
        requireText(sensitivity, "unit", "in");
        JsonNode values = sensitivity.path("values");
        require(values.isArray() && values.size() >= 2 && values.size() <= 8, "sensitivity values have an invalid count");
        double previous = Double.NEGATIVE_INFINITY;
        for (JsonNode value : values) {
            require(finite(value) && value.doubleValue() > previous && value.doubleValue() <= 1000,
                    "sensitivity values must be finite and strictly increasing");
            previous = value.doubleValue();
        }

        validateSeries(result.path("system"), true);
        JsonNode cases = result.path("cases");
        require(cases.isArray() && cases.size() == values.size(), "case count must match sensitivity values");
        Set<String> caseIds = new HashSet<>();
        for (int index = 0; index < cases.size(); index++) {
            JsonNode item = cases.get(index);
            require(item.isObject() && fieldsEqual(item, Set.of("caseId", "caseIndex", "sensitivityValueIn", "node")), "case has an invalid shape");
            String caseId = safeText(item, "caseId");
            require(caseIds.add(caseId), "case identifiers must be unique");
            require(item.path("caseIndex").isIntegralNumber() && item.path("caseIndex").intValue() == index,
                    "case indices must be consecutive");
            require(finite(item.path("sensitivityValueIn")) && item.path("sensitivityValueIn").doubleValue() == values.get(index).doubleValue(),
                    "case sensitivity value does not match the submitted value");
            validateSeries(item.path("node"), true);
        }
        validateSummary(result.path("summary"));
        validateTexts(result.path("messages"), "messages");
        validateQuality(result.path("quality"));
        JsonNode pwi = result.path("pwiArtifact");
        require(pwi.isObject() && fieldsEqual(pwi, Set.of("artifactName"))
                && "pipesim-network-well-performance.pwi".equals(safeText(pwi, "artifactName")), "PWI artifact metadata is invalid");
        return new PipesimWellResultValidator.ValidatedResult(SoftwareIntegrationRunStatus.SUCCEEDED, "VALID_FULL", result);
    }

    private static void validateSeries(JsonNode series, boolean requireNonEmpty) {
        require(series.isArray() && (!requireNonEmpty || !series.isEmpty()), "result series must be a non-empty array");
        for (JsonNode item : series) {
            require(item.isObject() && fieldsEqual(item, Set.of("variable", "unit", "values")), "series has an invalid shape");
            safeText(item, "variable");
            safeText(item, "unit");
            JsonNode values = item.path("values");
            require(values.isArray() && !values.isEmpty(), "series values must not be empty");
            for (JsonNode value : values) {
                require(value.isObject() && fieldsEqual(value, Set.of("name", "value")), "named result value has an invalid shape");
                safeText(value, "name");
                JsonNode scalar = value.path("value");
                require(scalar.isNull() || scalar.isBoolean() || scalar.isTextual() || finite(scalar), "result contains a non-finite or unsupported value");
            }
        }
    }

    private static void validateSummary(JsonNode summary) {
        require(summary.isObject() && fieldsEqual(summary, Set.of("info", "warnings", "errors")), "summary has an invalid shape");
        validateTexts(summary.path("info"), "summary.info");
        validateTexts(summary.path("warnings"), "summary.warnings");
        validateTexts(summary.path("errors"), "summary.errors");
    }

    private static void validateTexts(JsonNode values, String name) {
        require(values.isArray(), name + " must be an array");
        for (JsonNode value : values) require(value.isTextual() && safe(value.asText(), 1000), name + " contains unsafe text");
    }

    private static void validateQuality(JsonNode values) {
        require(values.isArray() && values.size() <= 100000, "quality has an invalid shape");
        Set<String> paths = new HashSet<>();
        for (JsonNode item : values) {
            require(item.isObject() && fieldsEqual(item, Set.of("path", "code")), "quality item has an invalid shape");
            String path = safeText(item, "path");
            String code = safeText(item, "code");
            require(paths.add(path) && Set.of("NON_FINITE", "UNAVAILABLE").contains(code), "quality item is invalid");
        }
    }

    private static boolean fieldsEqual(JsonNode value, Set<String> fields) {
        if (!value.isObject() || value.size() != fields.size()) return false;
        for (String name : value.propertyNames()) if (!fields.contains(name)) return false;
        return true;
    }

    private static void requireText(JsonNode node, String field, String expected) {
        require(node.path(field).isTextual() && expected.equals(node.path(field).asText()), field + " does not match the expected contract");
    }

    private static String safeText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isTextual() && safe(value.asText(), 255), field + " is invalid");
        return value.asText();
    }

    private static boolean safe(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit && value.chars().noneMatch(Character::isISOControl)
                && !value.contains("file://") && !value.contains("net.pipe://") && !value.matches("(?i).*\\b[A-Z]:\\\\.*");
    }

    private static boolean finite(JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.doubleValue());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new PipesimNetworkResultValidator.ResultValidationException(message);
    }
}
