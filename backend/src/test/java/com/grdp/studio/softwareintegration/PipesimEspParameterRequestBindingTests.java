package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.PipesimEspParameterResultValidator;
import com.grdp.studio.softwareintegration.execution.PipesimNetworkResultValidator;
import com.grdp.studio.softwareintegration.execution.PipesimResultValidator;
import com.grdp.studio.softwareintegration.execution.PipesimWellResultValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipesimEspParameterRequestBindingTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PipesimEspParameterResultValidator espValidator = new PipesimEspParameterResultValidator();
    private final PipesimResultValidator validator = new PipesimResultValidator(
            new PipesimWellResultValidator(), new PipesimNetworkResultValidator());

    @ParameterizedTest
    @CsvSource({"1,operatingFrequency,1", "1,operatingFrequency,2", "2,headFactor,2",
            "3,operatingFrequency,1", "3,operatingFrequency,2", "3,headFactor,2"})
    void acceptsMatchingRequestsAndRejectsOtherEspAndTarget(int version, String parameter, int resultVersion) {
        ObjectNode request = request(version, parameter);
        ObjectNode result = result(parameter, resultVersion);
        assertThat(validator.validate("esp-parameter", null, result, request).contract()).isEqualTo("VALID_FULL");

        // A valid calculation for another pump must never satisfy this request.
        ObjectNode wrongEsp = result.deepCopy();
        wrongEsp.put("espContext", "Well_1:OtherESP");
        assertThat(espValidator.validate(wrongEsp).contract()).isEqualTo("VALID_FULL");
        rejects(wrongEsp, request, "espContext");

        // Keep the result internally consistent while returning another scenario's target.
        ObjectNode wrongTarget = result.deepCopy();
        double target = "headFactor".equals(parameter) ? 0.8 : 80;
        wrongTarget.put("targetValue", target).put("appliedValue", target);
        ((ObjectNode) wrongTarget.path("target")).put(resultVersion == 1 ? "frequencyHz" : "parameterValue", target);
        assertThat(espValidator.validate(wrongTarget).contract()).isEqualTo("VALID_FULL");
        rejects(wrongTarget, request, "targetValue");
    }

    @ParameterizedTest
    @CsvSource({"1,operatingFrequency", "2,headFactor", "3,operatingFrequency", "3,headFactor"})
    void rejectsAnotherValidParameterResult(int version, String parameter) {
        String other = "headFactor".equals(parameter) ? "operatingFrequency" : "headFactor";
        rejects(result(other, "headFactor".equals(other) ? 2 : 1), request(version, parameter), "parameter");
    }

    @ParameterizedTest
    @CsvSource({"operatingFrequency,1", "operatingFrequency,2", "headFactor,2"})
    void rejectsWrongProducerForSelectedWell(String parameter, int resultVersion) {
        ObjectNode result = result(parameter, resultVersion);
        result.put("producer", "Well_2");
        assertThat(espValidator.validate(result).contract()).isEqualTo("VALID_FULL");
        rejects(result, request(3, parameter), "wellContext");
    }

    @Test
    void targetBindingDoesNotUseResultReadbackToleranceOrTextCoercion() {
        ObjectNode result = result("operatingFrequency", 1);
        ObjectNode request = request(1, "operatingFrequency");
        request.put("targetValueHz", 70.0000000001);
        rejects(result, request, "targetValue");
        request.put("targetValueHz", "70");
        rejects(result, request, "targetValueHz");
        request.put("targetValueHz", 70.0);
        assertThat(validator.validate("esp-parameter", null, result, request).contract()).isEqualTo("VALID_FULL");
    }

    @Test
    void rejectsMissingOrMalformedRequestInsteadOfFallingBackToContractOnlyValidation() {
        ObjectNode result = result("operatingFrequency", 1);
        for (JsonNode request : new JsonNode[]{null, mapper.readTree("null"), mapper.readTree("{}"),
                mapper.readTree("[]"), mapper.readTree("true")}) {
            rejects(result, request, "request");
        }
        for (String field : new String[]{"schemaVersion", "espContext", "parameter", "targetValueHz"}) {
            ObjectNode request = request(1, "operatingFrequency");
            request.remove(field);
            rejects(result, request, "request");
        }
        ObjectNode request = request(1, "operatingFrequency");
        request.put("schemaVersion", "pipesim-esp-parameter-parameters/99");
        rejects(result, request, "request");
        request = request(1, "operatingFrequency");
        request.put("extra", true);
        rejects(result, request, "request");
        request = request(3, "operatingFrequency");
        request.remove("wellContext");
        rejects(result, request, "request");
    }

    @Test
    void rejectsInvalidRequestParameterVersionAndNumericBounds() {
        for (int version : new int[]{1, 2, 3}) {
            String parameter = version == 2 ? "headFactor" : "operatingFrequency";
            String targetField = version == 1 ? "targetValueHz" : "targetValue";
            for (String value : new String[]{"null", "true", "\"70\"", "0", "-1", "201", "1e999"}) {
                ObjectNode request = request(version, parameter);
                request.set(targetField, mapper.readTree(value));
                rejects(result(parameter, version == 2 ? 2 : 1), request, targetField);
            }
        }
        ObjectNode request = request(2, "headFactor");
        request.put("targetValue", 2.1);
        rejects(result("headFactor", 2), request, "targetValue");
        request = request(1, "operatingFrequency");
        request.put("parameter", "headFactor");
        rejects(result("headFactor", 2), request, "parameter");
        request = request(2, "headFactor");
        request.put("parameter", "operatingFrequency");
        rejects(result("operatingFrequency", 1), request, "parameter");
    }

    @Test
    void contextBindingIsExactAndAutoIsNotAnExplicitRequest() {
        for (String context : new String[]{"well_1:ESP", "Well_1:ESP ", "AUTO", "auto", "../ESP", "ESP\n"}) {
            ObjectNode request = request(1, "operatingFrequency");
            request.put("espContext", context);
            rejects(result("operatingFrequency", 1), request, "espContext");
        }
        ObjectNode request = request(3, "headFactor");
        request.put("wellContext", "Well_1 ");
        rejects(result("headFactor", 2), request, "wellContext");
    }

    @Test
    void historicalContractOnlyOverloadsStillValidateBothResultContracts() {
        for (ObjectNode result : new ObjectNode[]{result("operatingFrequency", 1),
                result("operatingFrequency", 2), result("headFactor", 2)}) {
            assertThat(espValidator.validate(result).contract()).isEqualTo("VALID_FULL");
            assertThat(validator.validate("esp-parameter", null, result).contract()).isEqualTo("VALID_FULL");
        }
    }

    @Test
    void matchingRequestCannotWeakenExistingClosedResultContract() {
        for (String field : new String[]{"unit", "sourceModelUnchanged", "isolatedCopy", "comparison", "baseline", "target"}) {
            ObjectNode result = result("operatingFrequency", 1);
            result.set(field, mapper.readTree("null"));
            rejects(result, request(1, "operatingFrequency"), "");
        }
        ObjectNode result = result("operatingFrequency", 1);
        result.put("appliedValue", 65);
        rejects(result, request(1, "operatingFrequency"), "Applied");
        result = result("operatingFrequency", 1);
        result.put("extra", true);
        rejects(result, request(1, "operatingFrequency"), "shape");
    }

    private void rejects(JsonNode result, JsonNode request, String reason) {
        assertThatThrownBy(() -> validator.validate("esp-parameter", null, result, request))
                .isInstanceOf(PipesimResultValidator.ResultValidationException.class)
                .hasMessageContaining(reason);
    }

    private ObjectNode request(int version, String parameter) {
        ObjectNode request = mapper.createObjectNode();
        request.put("schemaVersion", "pipesim-esp-parameter-parameters/" + version);
        request.put("espContext", "Well_1:ESP").put("parameter", parameter);
        if (version == 3) request.put("wellContext", "Well_1");
        request.put(version == 1 ? "targetValueHz" : "targetValue", "headFactor".equals(parameter) ? 0.9 : 70);
        return request;
    }

    private ObjectNode result(String parameter, int version) {
        ObjectNode result = (ObjectNode) mapper.readTree("""
                {"schemaVersion":"pipesim-esp-parameter-result/1","model_kind":"legacy_well",
                 "runTask":"esp-parameter","resultContract":"VALID_FULL","producer":"Well_1",
                 "espContext":"Well_1:ESP","parameter":"operatingFrequency","unit":"Hz",
                 "originalValue":60,"targetValue":70,"appliedValue":70,"sourceModelUnchanged":true,"isolatedCopy":true,
                 "baseline":{"frequencyHz":60,"ipr":[{"flow":100,"pressure":1200}],"vlp":[{"flow":100,"pressure":1300}],"profile":[{"depth":0,"pressure":1500,"temperature":80}]},
                 "target":{"frequencyHz":70,"ipr":[{"flow":100,"pressure":1250}],"vlp":[{"flow":100,"pressure":1350}],"profile":[{"depth":0,"pressure":1550,"temperature":82}]},
                 "comparison":{"maxIprPressureDelta":null,"maxVlpPressureDelta":50,"maxProfilePressureDelta":50,"maxProfileTemperatureDelta":2}}
                """);
        result.put("schemaVersion", "pipesim-esp-parameter-result/" + version).put("parameter", parameter);
        if (version == 2) {
            boolean head = "headFactor".equals(parameter);
            double original = head ? 2.5 : 60;
            double target = head ? 0.9 : 70;
            result.put("unit", head ? "1" : "Hz").put("originalValue", original)
                    .put("targetValue", target).put("appliedValue", target);
            ObjectNode baseline = (ObjectNode) result.path("baseline");
            baseline.remove("frequencyHz");
            baseline.put("parameterValue", original);
            ObjectNode targetCase = (ObjectNode) result.path("target");
            targetCase.remove("frequencyHz");
            targetCase.put("parameterValue", target);
        }
        return result;
    }
}
