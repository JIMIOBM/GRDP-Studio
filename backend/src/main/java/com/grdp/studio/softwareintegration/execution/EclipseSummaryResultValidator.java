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
            "schemaVersion", "modelKind", "runTask", "resultContract", "caseName", "eclEnd", "summary", "grid", "fieldIndex", "outputFiles", "messages");
    private static final Set<String> ECL_END_FIELDS = Set.of("comments", "warnings", "problems", "errors", "bugs");
    private static final Set<String> SERIES_FIELDS = Set.of("keyword", "objectName", "unit", "points");
    private static final Set<String> POINT_FIELDS = Set.of("timeDays", "value");
    private static final Set<String> OUTPUT_FIELDS = Set.of("name", "sizeBytes", "sha256");
    private static final Set<String> MESSAGE_FIELDS = Set.of("category", "severity", "code", "message", "retryable");
    private static final Set<String> GRID_FIELDS = Set.of("fileName", "nx", "ny", "nz", "activeCells");
    private static final Set<String> FIELD_INDEX_FIELDS = Set.of("schemaVersion", "files");
    private static final Set<String> FIELD_FILE_FIELDS = Set.of("name", "sizeBytes", "byteOrder", "fields");
    private static final Set<String> FIELD_FIELDS = Set.of("keyword", "dataType", "count", "elementSize", "dataBytes", "segments");
    private static final Set<String> FIELD_FIELDS_WITH_TIME_STEP = Set.of("keyword", "dataType", "count", "elementSize", "dataBytes", "segments", "timeStep");
    private static final Set<String> SEGMENT_FIELDS = Set.of("offset", "length");
    private static final Set<String> FIELD_DATA_TYPES = Set.of("INTE", "LOGI", "REAL", "DOUB", "CHAR");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final Pattern FIELD_KEYWORD = Pattern.compile("[A-Z0-9_]{1,8}");
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
        String resultContract = requireContract(result.get("resultContract"));
        requireText(result, "caseName", expectedCaseName);
        require(expectedCaseName != null && expectedCaseName.toUpperCase(Locale.ROOT).endsWith(".DATA")
                && expectedCaseName.equals(fileName(expectedCaseName))
                && SoftwareIntegrationEclipseSanitizer.isSafeIdentifier(expectedCaseName), "Invalid ECLIPSE caseName");
        validateEclEnd(result.get("eclEnd"), resultContract);
        validateSummary(result.get("summary"));
        validateGrid(result.get("grid"));
        validateFieldIndex(result.get("fieldIndex"));
        validateMessages(result.get("messages"));
        validateOutputFiles(expectedCaseName, result.get("outputFiles"));
        SoftwareIntegrationRunStatus terminalStatus = "VALID_PARTIAL".equals(resultContract)
                ? SoftwareIntegrationRunStatus.PARTIAL_SUCCEEDED : SoftwareIntegrationRunStatus.SUCCEEDED;
        return new ValidatedResult(terminalStatus, resultContract,
                SoftwareIntegrationEclipseSanitizer.sanitize(result, objectMapper));
    }

    private String requireContract(JsonNode value) {
        require(value != null && value.isTextual()
                && Set.of("VALID_FULL", "VALID_PARTIAL").contains(value.asText()), "Invalid resultContract");
        return value.asText();
    }

    private void validateEclEnd(JsonNode eclEnd, String resultContract) {
        requireObject(eclEnd, ECL_END_FIELDS, ECL_END_FIELDS, "eclEnd");
        requireNullableNonNegativeInt(eclEnd.get("comments"), "eclEnd.comments");
        requireNullableNonNegativeInt(eclEnd.get("warnings"), "eclEnd.warnings");
        int problems = nonNegativeInt(eclEnd.get("problems"), "eclEnd.problems");
        require(nonNegativeInt(eclEnd.get("errors"), "eclEnd.errors") == 0, "eclEnd.errors must be zero");
        require(nonNegativeInt(eclEnd.get("bugs"), "eclEnd.bugs") == 0, "eclEnd.bugs must be zero");
        if ("VALID_PARTIAL".equals(resultContract)) {
            require(problems > 0, "VALID_PARTIAL requires at least one ECLIPSE problem");
        } else {
            require(problems == 0, "eclEnd.problems must be zero for VALID_FULL");
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
        require(array.size() <= 512, "messages contains too many diagnostics");
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

    private void validateGrid(JsonNode grid) {
        if (grid == null || grid.isNull()) return;
        requireObject(grid, GRID_FIELDS, GRID_FIELDS, "grid");
        requiredSafeText(grid.get("fileName"), "grid fileName");
        int nx = positiveInt(grid.get("nx"), "grid nx");
        int ny = positiveInt(grid.get("ny"), "grid ny");
        int nz = positiveInt(grid.get("nz"), "grid nz");
        JsonNode active = grid.get("activeCells");
        require(active != null && (active.isNull() || active.isIntegralNumber() && active.canConvertToLong()
                && active.longValue() >= 0 && active.longValue() <= (long) nx * ny * nz),
                "grid activeCells is invalid");
    }

    private void validateFieldIndex(JsonNode fieldIndex) {
        if (fieldIndex == null || fieldIndex.isNull()) return;
        requireObject(fieldIndex, FIELD_INDEX_FIELDS, FIELD_INDEX_FIELDS, "fieldIndex");
        requireText(fieldIndex, "schemaVersion", "eclipse-binary-field-index/1");
        JsonNode files = requireArray(fieldIndex.get("files"), "fieldIndex.files");
        require(files.size() <= 64, "fieldIndex contains too many files");
        for (JsonNode file : files) {
            requireObject(file, FIELD_FILE_FIELDS, FIELD_FILE_FIELDS, "fieldIndex file");
            String name = requiredSafeText(file.get("name"), "fieldIndex file name");
            require(name.matches("(?i)[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.(?:EGRID|INIT|UNRST)"),
                    "fieldIndex file name is invalid");
            long sizeBytes = nonNegativeLong(file.get("sizeBytes"), "fieldIndex file sizeBytes");
            require(file.get("byteOrder") != null && file.get("byteOrder").isTextual()
                            && Set.of("BIG", "LITTLE").contains(file.get("byteOrder").asText()),
                    "fieldIndex byteOrder is invalid");
            JsonNode fields = requireArray(file.get("fields"), "fieldIndex fields");
            require(fields.size() <= 8192, "fieldIndex contains too many fields");
            for (JsonNode field : fields) validateField(field, sizeBytes);
        }
    }

    private void validateField(JsonNode field, long fileSize) {
        requireObject(field, FIELD_FIELDS, FIELD_FIELDS_WITH_TIME_STEP, "fieldIndex field");
        require(field.get("keyword") != null && field.get("keyword").isTextual()
                        && FIELD_KEYWORD.matcher(field.get("keyword").asText()).matches(),
                "fieldIndex keyword is invalid");
        String dataType = requiredSafeText(field.get("dataType"), "fieldIndex dataType");
        require(FIELD_DATA_TYPES.contains(dataType), "fieldIndex dataType is invalid");
        long count = nonNegativeLong(field.get("count"), "fieldIndex count");
        long elementSize = nonNegativeLong(field.get("elementSize"), "fieldIndex elementSize");
        long expectedElementSize = dataType.equals("DOUB") ? 8 : 4;
        if (dataType.equals("CHAR")) expectedElementSize = 8;
        require(elementSize == expectedElementSize, "fieldIndex elementSize is invalid");
        if (field.has("timeStep")) {
            require(field.get("timeStep").isIntegralNumber() && field.get("timeStep").canConvertToInt()
                            && field.get("timeStep").intValue() > 0,
                    "fieldIndex timeStep is invalid");
        }
        long dataBytes = nonNegativeLong(field.get("dataBytes"), "fieldIndex dataBytes");
        require(count <= Long.MAX_VALUE / elementSize && dataBytes == count * elementSize,
                "fieldIndex dataBytes is inconsistent");
        JsonNode segments = requireArray(field.get("segments"), "fieldIndex segments");
        long covered = 0;
        long previousEnd = -1;
        for (JsonNode segment : segments) {
            requireObject(segment, SEGMENT_FIELDS, SEGMENT_FIELDS, "fieldIndex segment");
            long offset = nonNegativeLong(segment.get("offset"), "fieldIndex segment offset");
            long length = nonNegativeLong(segment.get("length"), "fieldIndex segment length");
            require(length > 0 && length % elementSize == 0 && offset <= fileSize && length <= fileSize - offset,
                    "fieldIndex segment is outside the binary file");
            require(previousEnd < 0 || offset >= previousEnd, "fieldIndex segments are not ordered");
            previousEnd = offset + length;
            require(length > 0 && covered <= Long.MAX_VALUE - length,
                    "fieldIndex segment length is invalid");
            covered += length;
        }
        require(covered == dataBytes, "fieldIndex segments do not cover the field data");
        require(dataBytes == 0 || segments.size() > 0, "fieldIndex has no data segments");
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

    private static int nonNegativeInt(JsonNode value, String name) {
        require(value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0,
                name + " must be a non-negative integer");
        return value.intValue();
    }

    private static int positiveInt(JsonNode value, String name) {
        require(value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0,
                name + " must be a positive integer");
        return value.intValue();
    }

    private static long nonNegativeLong(JsonNode value, String name) {
        require(value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0,
                name + " must be a non-negative integer");
        return value.longValue();
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
