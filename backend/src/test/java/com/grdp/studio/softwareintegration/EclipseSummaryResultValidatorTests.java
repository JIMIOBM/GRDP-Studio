package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.EclipseSummaryResultValidator;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationEclipseSanitizer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EclipseSummaryResultValidatorTests {
    private static final String SHA = "a".repeat(64);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EclipseSummaryResultValidator validator = new EclipseSummaryResultValidator(objectMapper);

    @Test
    void acceptsZeroEclEndNullableAdvisoryCountsNullSummaryAndEmptySeries() {
        JsonNode nullSummary = result("null", "null", "null");
        var validated = validator.validate("CASE.DATA", nullSummary);
        assertThat(validated.contract()).isEqualTo("VALID_FULL");
        assertThat(validated.terminalStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(validated.result().path("summary").isNull()).isTrue();

        JsonNode emptySeries = result("0", "0", "{\"series\":[]}");
        assertThat(validator.validate("CASE.DATA", emptySeries).result()
                .path("summary").path("series")).isEmpty();
    }

    @Test
    void preservesWorkerSeriesAndPointOrderWithoutDerivation() {
        JsonNode result = result("0", "0", """
                {"series":[{"keyword":"FOPR","objectName":"FIELD","unit":"STB/DAY","points":[
                  {"timeDays":0.0,"value":3.0},{"timeDays":0.0,"value":2.0},{"timeDays":2.0,"value":1.0}]}]}
                """);
        JsonNode persisted = validator.validate("CASE.DATA", result).result();
        assertThat(persisted.path("summary").path("series").get(0).path("points").toString())
                .isEqualTo("[{\"timeDays\":0.0,\"value\":3.0},{\"timeDays\":0.0,\"value\":2.0},{\"timeDays\":2.0,\"value\":1.0}]");
        assertThat(persisted.path("summary").path("series").get(0).path("unit").asText()).isEqualTo("STB/DAY");
    }

    @Test
    void rejectsNonZeroProblemsErrorsOrBugs() {
        for (String field : List.of("problems", "errors", "bugs")) {
            ObjectNode invalid = (ObjectNode) result("0", "0", "null");
            ((ObjectNode) invalid.path("eclEnd")).put(field, 1);
            assertThatThrownBy(() -> validator.validate("CASE.DATA", invalid))
                    .isInstanceOf(EclipseSummaryResultValidator.ResultValidationException.class);
        }
    }

    @Test
    void rejectsEclEndFieldsThatDoNotUseTheWorkerLowerCamelContract() {
        ObjectNode invalid = (ObjectNode) result("0", "0", "null");
        ObjectNode eclEnd = (ObjectNode) invalid.path("eclEnd");
        eclEnd.set("Comments", eclEnd.get("comments"));
        eclEnd.remove("comments");

        assertInvalid(invalid);
    }

    @Test
    void rejectsNonFiniteNumbersAndOutOfOrderTime() {
        ObjectNode nan = (ObjectNode) result("0", "0", series());
        ((ObjectNode) nan.path("summary").path("series").get(0).path("points").get(0)).put("value", Double.NaN);
        assertInvalid(nan);
        ObjectNode infinity = (ObjectNode) result("0", "0", series());
        ((ObjectNode) infinity.path("summary").path("series").get(0).path("points").get(0)).put("timeDays", Double.POSITIVE_INFINITY);
        assertInvalid(infinity);
        ObjectNode unordered = (ObjectNode) result("0", "0", """
                {"series":[{"keyword":"FOPR","objectName":null,"unit":null,"points":[
                  {"timeDays":2.0,"value":1.0},{"timeDays":1.0,"value":2.0}]}]}
                """);
        assertInvalid(unordered);
    }

    @Test
    void rejectsExtraFieldsAndEveryPartialContract() {
        ObjectNode extra = (ObjectNode) result("0", "0", "null");
        extra.put("unexpected", true);
        assertInvalid(extra);
        ObjectNode partial = (ObjectNode) result("0", "0", "null");
        partial.put("resultContract", "VALID_PARTIAL");
        assertInvalid(partial);

        ObjectNode missingEclEnd = (ObjectNode) result("0", "0", "null");
        missingEclEnd.remove("eclEnd");
        assertInvalid(missingEclEnd);

        ObjectNode nestedExtra = (ObjectNode) result("0", "0", series());
        ((ObjectNode) nestedExtra.path("summary").path("series").get(0).path("points").get(0))
                .put("derivedValue", 1);
        assertInvalid(nestedExtra);
    }

    @Test
    void acceptsMetadataOnlyOutputInventoryAndValidatesItsFields() {
        assertThat(validator.validate("CASE.DATA", result("0", "0", "null")).contract()).isEqualTo("VALID_FULL");
        ObjectNode negativeSize = (ObjectNode) result("0", "0", "null");
        ((ObjectNode) negativeSize.path("outputFiles").get(0)).put("sizeBytes", -1);
        assertInvalid(negativeSize);
        ObjectNode uppercase = (ObjectNode) result("0", "0", "null");
        ((ObjectNode) uppercase.path("outputFiles").get(0)).put("sha256", SHA.toUpperCase());
        assertInvalid(uppercase);
    }

    @Test
    void rejectsMissingOrMalformedOutputInventorySha256() {
        ObjectNode missing = (ObjectNode) result("0", "0", "null");
        ((ObjectNode) missing.path("outputFiles").get(0)).remove("sha256");
        assertInvalid(missing);
        ObjectNode malformed = (ObjectNode) result("0", "0", "null");
        ((ObjectNode) malformed.path("outputFiles").get(0)).put("sha256", "g".repeat(64));
        assertInvalid(malformed);
    }

    @Test
    void sanitizesControlledMessagesAndRecursiveErrorDetailsWithoutChangingNumbers() {
        ObjectNode value = (ObjectNode) result("0", "0", "null");
        value.set("messages", objectMapper.readTree("""
                [{"category":"SOLVER","code":"NOTICE","message":"Path C:\\\\Users\\\\operator\\\\CASE.DATA token=secret","retryable":false}]
                """));
        JsonNode sanitizedResult = validator.validate("CASE.DATA", value).result();
        assertThat(sanitizedResult.path("messages").get(0).path("message").asText())
                .contains("[local path]").doesNotContain("operator", "secret");

        JsonNode error = objectMapper.readTree("""
                {"category":"SOLVER","code":"ECLIPSE_SOLVER_FAILED","details":{
                  "eclEnd":{"problems":2,"errors":1,"bugs":0},"password":"secret",
                  "nested":[{"commandLine":"eclrun -v 2024.1 eclipse CASE.DATA", "licenseServer":"27000@private"}],
                  "workingDirectory":"\\\\\\\\server\\\\private\\\\case", "note":"/home/operator/private/case"}}
                """);
        JsonNode sanitizedError = SoftwareIntegrationEclipseSanitizer.sanitize(error, objectMapper);
        assertThat(sanitizedError.path("details").path("eclEnd").path("problems").asInt()).isEqualTo(2);
        assertThat(sanitizedError.toString()).contains("[redacted]", "[local path]")
                .doesNotContain("secret", "eclrun -v", "27000@private", "server\\\\private", "/home/operator");
    }

    @Test
    void rejectsUnstructuredMessagesAndSensitiveEngineeringIdentifiers() {
        ObjectNode messages = (ObjectNode) result("0", "0", "null");
        messages.set("messages", objectMapper.readTree("[\"raw output\"]"));
        assertInvalid(messages);
        ObjectNode pathObject = (ObjectNode) result("0", "0", series());
        ((ObjectNode) pathObject.path("summary").path("series").get(0)).put("objectName", "C:\\private\\well");
        assertInvalid(pathObject);
        assertThatThrownBy(() -> validator.validate("nested/CASE.DATA", result("0", "0", "null")))
                .isInstanceOf(EclipseSummaryResultValidator.ResultValidationException.class);
    }

    private void assertInvalid(JsonNode result) {
        assertThatThrownBy(() -> validator.validate("CASE.DATA", result))
                .isInstanceOf(EclipseSummaryResultValidator.ResultValidationException.class);
    }

    private JsonNode result(String comments, String warnings, String summary) {
        return objectMapper.readTree("""
                {"schemaVersion":"eclipse-summary-result/1","modelKind":"eclipse_100","runTask":"eclipse",
                 "resultContract":"VALID_FULL","caseName":"CASE.DATA",
                 "eclEnd":{"comments":%s,"warnings":%s,"problems":0,"errors":0,"bugs":0},
                 "summary":%s,
                 "outputFiles":[{"name":"CASE.ECLEND","sizeBytes":8,"sha256":"%s"}]}
                """.formatted(comments, warnings, summary, SHA));
    }

    private String series() {
        return "{\"series\":[{\"keyword\":\"FOPR\",\"objectName\":null,\"unit\":null,\"points\":[{\"timeDays\":0.0,\"value\":1.0}]}]}";
    }

}
