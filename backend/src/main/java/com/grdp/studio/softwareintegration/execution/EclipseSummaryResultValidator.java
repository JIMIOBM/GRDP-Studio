package com.grdp.studio.softwareintegration.execution;

import com.grdp.studio.softwareintegration.support.SoftwareIntegrationEclipseSanitizer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class EclipseSummaryResultValidator {
    private static final Set<String> REQUIRED_ROOT_FIELDS = Set.of(
            "schemaVersion", "modelKind", "runTask", "resultContract", "caseName", "eclEnd", "summary", "outputFiles");
    private static final Set<String> ALLOWED_ROOT_FIELDS = Set.of(
            "schemaVersion", "modelKind", "runTask", "resultContract", "caseName", "eclEnd", "summary", "outputFiles", "messages");
    private static final Set<String> ECL_END_FIELDS = Set.of("comments", "warnings", "problems", "errors", "bugs");
    private static final Set<String> SERIES_FIELDS = Set.of("keyword", "objectName", "unit", "points");
    private static final Set<String> POINT_FIELDS = Set.of("timeDays", "value");
    private static final Set<String> OUTPUT_FIELDS = Set.of("name", "sizeBytes", "sha256");
    private static final Set<String> MESSAGE_FIELDS = Set.of("category", "severity", "code", "message", "retryable");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private final ObjectMapper objectMapper;

    public EclipseSummaryResultValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidatedResult validate(String expectedCaseName, JsonNode result) {
        requireObject(result, REQUIRED_ROOT_FIELDS, ALLOWED_ROOT_FIELDS, "result");
        requireText(result, "schemaVersion", "eclipse-summary-result/1");
        requireText(result, "modelKind", "eclipse_100");
        requireText(result, "runTask", "eclipse");
        requireText(result, "resultContract", "VALID_FULL");
        requireText(result, "caseName", expectedCaseName);
        require(expectedCaseName != null && expectedCaseName.toUpperCase(Locale.ROOT).endsWith(".DATA")
                && expectedCaseName.equals(fileName(expectedCaseName))
                && SoftwareIntegrationEclipseSanitizer.isSafeIdentifier(expectedCaseName), "Invalid ECLIPSE caseName");
        validateEclEnd(result.get("eclEnd"));
        validateSummary(result.get("summary"));
        validateMessages(result.get("messages"));
        validateOutputFiles(expectedCaseName, result.get("outputFiles"));
        return new ValidatedResult(SoftwareIntegrationRunStatus.SUCCEEDED, "VALID_FULL",
                SoftwareIntegrationEclipseSanitizer.sanitize(result, objectMapper));
    }

    private void validateEclEnd(JsonNode eclEnd) {
        requireObject(eclEnd, ECL_END_FIELDS, ECL_END_FIELDS, "eclEnd");
        requireNullableNonNegativeInt(eclEnd.get("comments"), "eclEnd.comments");
        requireNullableNonNegativeInt(eclEnd.get("warnings"), "eclEnd.warnings");
        for (String field : List.of("problems", "errors", "bugs")) {
            JsonNode value = eclEnd.get(field);
            require(value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() == 0,
                    "eclEnd." + field + " must be zero");
        }
    }

    private void validateSummary(JsonNode summary) {
        require(summary != null, "summary is missing");
        if (summary.isNull()) return;
        requireObject(summary, Set.of("series"), Set.of("series"), "summary");
        JsonNode series = requireArray(summary.get("series"), "summary.series");
        for (JsonNode item : series) {
            requireObject(item, SERIES_FIELDS, SERIES_FIELDS, "summary series");
            requireSafeText(item.get("keyword"), false, "summary keyword");
            requireSafeText(item.get("objectName"), true, "summary objectName");
            requireSafeText(item.get("unit"), true, "summary unit");
            JsonNode points = requireArray(item.get("points"), "summary points");
            double previous = -1;
            for (JsonNode point : points) {
                requireObject(point, POINT_FIELDS, POINT_FIELDS, "summary point");
                double time = finiteNumber(point.get("timeDays"), "timeDays");
                finiteNumber(point.get("value"), "value");
                require(time >= 0 && time >= previous, "timeDays must be non-negative and ordered");
                previous = time;
            }
        }
    }

    private void validateMessages(JsonNode messages) {
        if (messages == null) return;
        JsonNode array = requireArray(messages, "messages");
        for (JsonNode message : array) {
            require(message != null && message.isObject() && message.has("code") && message.has("message"),
                    "messages must contain controlled objects");
            message.properties().forEach(field -> require(MESSAGE_FIELDS.contains(field.getKey()), "messages contain an unsupported field"));
            requireSafeCode(message.get("code"), "message code");
            if (message.has("category")) requireSafeCode(message.get("category"), "message category");
            if (message.has("severity")) requireSafeCode(message.get("severity"), "message severity");
            require(message.get("message").isTextual() && message.get("message").asText().length() <= 1000,
                    "message text is invalid");
            if (message.has("retryable")) require(message.get("retryable").isBoolean(), "message retryable must be boolean");
        }
    }

    private void validateOutputFiles(String caseName, JsonNode outputFiles) {
        JsonNode array = requireArray(outputFiles, "outputFiles");
        String baseName = caseName.substring(0, caseName.length() - ".DATA".length());
        Set<String> seen = new HashSet<>();
        for (JsonNode output : array) {
            requireObject(output, OUTPUT_FIELDS, OUTPUT_FIELDS, "output file");
            String name = requiredSafeText(output.get("name"), "output file name");
            require(name.length() <= 512 && name.equals(fileName(name)) && seen.add(name),
                    "Invalid or duplicate output file name");
            JsonNode size = output.get("sizeBytes");
            JsonNode sha = output.get("sha256");
            require(size != null && size.isIntegralNumber() && size.canConvertToLong() && size.longValue() >= 0,
                    "output file sizeBytes is invalid");
            require(sha != null && sha.isTextual() && SHA256.matcher(sha.asText()).matches(), "output file sha256 is invalid");
        }
        require(seen.contains(baseName + ".ECLEND"), "ECLEND output inventory entry is missing");
    }

    private static String fileName(String value) {
        if (value == null) return null;
        String normalized = value.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static void requireNullableNonNegativeInt(JsonNode value, String name) {
        require(value != null && (value.isNull() || value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0),
                name + " must be null or a non-negative integer");
    }

    private static double finiteNumber(JsonNode value, String name) {
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue()), name + " must be a finite number");
        return value.doubleValue();
    }

    private static void requireSafeCode(JsonNode value, String name) {
        require(value != null && value.isTextual() && CODE.matcher(value.asText()).matches(), name + " is invalid");
    }

    private static void requireSafeText(JsonNode value, boolean nullable, String name) {
        if (nullable && value != null && value.isNull()) return;
        requiredSafeText(value, name);
    }

    private static String requiredSafeText(JsonNode value, String name) {
        require(value != null && value.isTextual() && !value.asText().isBlank()
                && SoftwareIntegrationEclipseSanitizer.isSafeIdentifier(value.asText()), name + " is invalid");
        return value.asText();
    }

    private static JsonNode requireArray(JsonNode value, String name) {
        require(value != null && value.isArray(), name + " must be an array");
        return value;
    }

    private static void requireObject(JsonNode value, Set<String> required, Set<String> allowed, String name) {
        require(value != null && value.isObject(), name + " must be an object");
        for (String field : required) require(value.has(field), name + " is missing " + field);
        value.properties().forEach(field -> require(allowed.contains(field.getKey()), name + " contains an unsupported field"));
    }

    private static void requireText(JsonNode value, String field, String expected) {
        JsonNode node = value.get(field);
        require(node != null && node.isTextual() && expected.equals(node.asText()), "Invalid " + field);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new ResultValidationException(message);
    }

    public record ValidatedResult(SoftwareIntegrationRunStatus terminalStatus, String contract, JsonNode result) {}

    public static class ResultValidationException extends RuntimeException {
        public ResultValidationException(String message) { super(message); }
    }
}
