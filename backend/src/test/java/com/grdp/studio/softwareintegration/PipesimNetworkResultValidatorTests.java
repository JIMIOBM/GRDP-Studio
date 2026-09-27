package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.PipesimNetworkResultValidator;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class PipesimNetworkResultValidatorTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PipesimNetworkResultValidator validator = new PipesimNetworkResultValidator();

    @Test
    void acceptsFullNetworkResultWithExplicitCleanedNulls() {
        JsonNode result = validResult();

        var validated = validator.validate("network", "Network Study", result);

        assertThat(validated.terminalStatus()).isEqualTo(SoftwareIntegrationRunStatus.SUCCEEDED);
        assertThat(validated.contract()).isEqualTo("VALID_FULL");
        assertThat(validated.result()).isSameAs(result);
        assertThat(result.path("node").get(0).path("values").get(0).has("value")).isTrue();
        assertThat(result.path("node").get(0).path("values").get(0).path("value").isNull()).isTrue();

        ((ObjectNode) result.path("quality").get(0)).put("code", "UNAVAILABLE");
        assertThat(validator.validate("network", "Network Study", result).terminalStatus())
                .isEqualTo(SoftwareIntegrationRunStatus.SUCCEEDED);
    }

    @Test
    void rejectsSchemaRunTaskContractAndSimulationMismatches() {
        ObjectNode wrongSchema = validResult();
        wrongSchema.put("schemaVersion", "pipesim-network-result/2");
        assertRejected(wrongSchema);

        ObjectNode wrongKind = validResult();
        wrongKind.put("model_kind", "black_oil_liquid");
        assertRejected(wrongKind);

        ObjectNode wrongTask = validResult();
        wrongTask.put("runTask", "profile");
        assertRejected(wrongTask);

        ObjectNode notCompleted = validResult();
        notCompleted.put("simulationState", "Failed");
        assertRejected(notCompleted);

        ObjectNode wrongStudy = validResult();
        wrongStudy.put("study", "Other Study");
        assertRejected(wrongStudy);

        assertThatThrownBy(() -> validator.validate("profile", "Network Study", validResult()))
                .isInstanceOf(PipesimNetworkResultValidator.ResultValidationException.class);
    }

    @Test
    void acceptsLimitedPartialNetworkResultWithoutFullProfileOrQualityValidation() {
        ObjectNode partial = validResult();
        partial.put("resultContract", "VALID_PARTIAL");
        partial.remove(List.of("system", "node", "profiles", "summary", "messages", "quality"));

        var validated = validator.validate("network", "Network Study", partial);

        assertThat(validated.terminalStatus()).isEqualTo(SoftwareIntegrationRunStatus.PARTIAL_SUCCEEDED);
        assertThat(validated.contract()).isEqualTo("VALID_PARTIAL");
        assertThat(validated.result()).isSameAs(partial);
    }

    @Test
    void acceptsPartialNetworkResultWithEmptyOptionalUiSectionsAndAnEmptySourcePort() {
        ObjectNode partial = partialResult();
        ((ObjectNode) partial.path("topology").path("edges").get(0)).put("sourcePort", "");
        partial.set("system", objectMapper.createArrayNode());
        partial.set("node", objectMapper.createArrayNode());
        partial.set("profiles", objectMapper.createArrayNode());
        partial.set("summary", objectMapper.createObjectNode()
                .set("info", objectMapper.createArrayNode())
                .set("warnings", objectMapper.createArrayNode())
                .set("errors", objectMapper.createArrayNode()));
        partial.set("messages", objectMapper.createArrayNode());
        partial.set("quality", objectMapper.createArrayNode());

        assertThat(validator.validate("network", "Network Study", partial).terminalStatus())
                .isEqualTo(SoftwareIntegrationRunStatus.PARTIAL_SUCCEEDED);
    }

    @Test
    void rejectsUnsafeOrUnstructuredPartialUiData() {
        ObjectNode unknownRootField = partialResult();
        unknownRootField.put("unexpected", "data");
        assertRejected(unknownRootField);

        ObjectNode unsafeSummary = validResult();
        unsafeSummary.put("resultContract", "VALID_PARTIAL");
        ((ObjectNode) unsafeSummary.path("summary")).withArray("errors").add("C:\\private\\result.log");
        assertRejected(unsafeSummary);

        ObjectNode nonFiniteSystemValue = validResult();
        nonFiniteSystemValue.put("resultContract", "VALID_PARTIAL");
        ((ObjectNode) nonFiniteSystemValue.path("system").get(0).path("values").get(0)).put("value", Double.NaN);
        assertRejected(nonFiniteSystemValue);

        ObjectNode textualNodeValue = validResult();
        textualNodeValue.put("resultContract", "VALID_PARTIAL");
        ((ObjectNode) textualNodeValue.path("node").get(0).path("values").get(0)).put("value", "100");
        assertRejected(textualNodeValue);

        ObjectNode unsafeProfileVariable = validResult();
        unsafeProfileVariable.put("resultContract", "VALID_PARTIAL");
        ((ObjectNode) unsafeProfileVariable.path("profiles").get(0).path("variables").get(0))
                .put("variable", "C:\\private\\Pressure");
        assertRejected(unsafeProfileVariable);

        ObjectNode textualProfileValue = validResult();
        textualProfileValue.put("resultContract", "VALID_PARTIAL");
        ((ArrayNode) textualProfileValue.path("profiles").get(0).path("variables").get(0).path("values"))
                .set(0, objectMapper.getNodeFactory().textNode("0"));
        assertRejected(textualProfileValue);
    }

    @Test
    void rejectsPartialNetworkResultWithoutSafeTopology() {
        ObjectNode partial = validResult();
        partial.put("resultContract", "VALID_PARTIAL");
        ((ArrayNode) partial.path("topology").path("edges")).removeAll();

        assertRejected(partial);
    }

    @Test
    void rejectsPartialNetworkResultWithInvalidTopologyShapes() {
        ObjectNode missingComponentType = partialResult();
        ((ObjectNode) missingComponentType.path("topology").path("nodes").get(0)).remove("componentType");
        assertRejected(missingComponentType);

        ObjectNode blankNodeId = partialResult();
        ((ObjectNode) blankNodeId.path("topology").path("nodes").get(0)).put("id", " ");
        assertRejected(blankNodeId);

        ObjectNode unsafeSourcePort = partialResult();
        ((ObjectNode) unsafeSourcePort.path("topology").path("edges").get(0)).put("sourcePort", "\u0000");
        assertRejected(unsafeSourcePort);

        ObjectNode missingCounts = partialResult();
        ((ObjectNode) missingCounts.path("topology")).remove("counts");
        assertRejected(missingCounts);
    }

    @Test
    void rejectsPartialNetworkResultWithUnknownEdgeNodeReferences() {
        ObjectNode unknownSource = partialResult();
        ((ObjectNode) unknownSource.path("topology").path("edges").get(0)).put("source", "Unknown Source");
        assertRejected(unknownSource);

        ObjectNode unknownDestination = partialResult();
        ((ObjectNode) unknownDestination.path("topology").path("edges").get(0)).put("destination", "Unknown Sink");
        assertRejected(unknownDestination);
    }

    @Test
    void rejectsPartialNetworkResultWithInvalidTopologyCounts() {
        ObjectNode negativeCount = partialResult();
        ((ObjectNode) negativeCount.path("topology").path("counts")).put("sources", -1);
        assertRejected(negativeCount);

        ObjectNode tooFewNodes = partialResult();
        ((ObjectNode) tooFewNodes.path("topology").path("counts")).put("nodes", 1);
        assertRejected(tooFewNodes);

        ObjectNode tooFewEdges = partialResult();
        ((ObjectNode) tooFewEdges.path("topology").path("counts")).put("edges", 0);
        assertRejected(tooFewEdges);
    }

    @Test
    void rejectsFullNetworkResultWithoutPartialTopologyInvariants() {
        ObjectNode duplicateNodeId = validResult();
        ((ObjectNode) duplicateNodeId.path("topology").path("nodes").get(1)).put("id", "Source 1");
        assertRejected(duplicateNodeId);

        ObjectNode blankComponentType = validResult();
        ((ObjectNode) blankComponentType.path("topology").path("nodes").get(0)).put("componentType", " ");
        assertRejected(blankComponentType);

        ObjectNode unknownSource = validResult();
        ((ObjectNode) unknownSource.path("topology").path("edges").get(0)).put("source", "Unknown Source");
        assertRejected(unknownSource);

        ObjectNode blankDestination = validResult();
        ((ObjectNode) blankDestination.path("topology").path("edges").get(0)).put("destination", " ");
        assertRejected(blankDestination);

        ObjectNode unsafeSourcePort = validResult();
        ((ObjectNode) unsafeSourcePort.path("topology").path("edges").get(0)).put("sourcePort", "\u0000");
        assertRejected(unsafeSourcePort);

        ObjectNode negativeCount = validResult();
        ((ObjectNode) negativeCount.path("topology").path("counts")).put("flowlines", -1);
        assertRejected(negativeCount);

        ObjectNode tooFewNodes = validResult();
        ((ObjectNode) tooFewNodes.path("topology").path("counts")).put("nodes", 1);
        assertRejected(tooFewNodes);

        ObjectNode tooFewEdges = validResult();
        ((ObjectNode) tooFewEdges.path("topology").path("counts")).put("edges", 0);
        assertRejected(tooFewEdges);
    }

    @Test
    void requiresTopologyProfilesAndEqualDistancePressureArrays() {
        ObjectNode noNodes = validResult();
        ((ArrayNode) noNodes.path("topology").path("nodes")).removeAll();
        assertRejected(noNodes);

        ObjectNode noEdges = validResult();
        ((ArrayNode) noEdges.path("topology").path("edges")).removeAll();
        assertRejected(noEdges);

        ObjectNode noProfiles = validResult();
        ((ArrayNode) noProfiles.path("profiles")).removeAll();
        assertRejected(noProfiles);

        ObjectNode missingPressure = validResult();
        ((ArrayNode) missingPressure.path("profiles").get(0).path("variables")).remove(1);
        assertRejected(missingPressure);

        ObjectNode unequalRequiredArrays = validResult();
        ((ArrayNode) unequalRequiredArrays.path("profiles").get(0).path("variables").get(1).path("values")).remove(1);
        assertRejected(unequalRequiredArrays);

        ObjectNode missingNamedValue = validResult();
        ((ObjectNode) missingNamedValue.path("system").get(0).path("values").get(0)).remove("value");
        assertRejected(missingNamedValue);

        ObjectNode missingQuality = validResult();
        ((ArrayNode) missingQuality.path("quality")).remove(0);
        assertRejected(missingQuality);

        ObjectNode orphanQuality = validResult();
        ((ArrayNode) orphanQuality.path("quality")).addObject()
                .put("path", "system.Pressure.Network")
                .put("code", "UNAVAILABLE");
        assertRejected(orphanQuality);

        ObjectNode textualNumericValue = validResult();
        ((ObjectNode) textualNumericValue.path("system").get(0).path("values").get(0)).put("value", "100");
        assertRejected(textualNumericValue);

        ObjectNode duplicateNullPath = validResult();
        ((ArrayNode) duplicateNullPath.path("node").get(0).path("values")).add(
                duplicateNullPath.path("node").get(0).path("values").get(0).deepCopy());
        assertRejected(duplicateNullPath);
    }

    @Test
    void rejectsEmptyNumericContainersInFullAndPartialResults() {
        ObjectNode emptyFullObject = validResult();
        ((ObjectNode) emptyFullObject.path("system").get(0).path("values").get(0))
                .set("value", objectMapper.createObjectNode());
        assertRejected(emptyFullObject);

        ObjectNode emptyFullArray = validResult();
        ((ObjectNode) emptyFullArray.path("system").get(0).path("values").get(0))
                .set("value", objectMapper.createArrayNode());
        assertRejected(emptyFullArray);

        ObjectNode emptyPartialObject = validResult();
        emptyPartialObject.put("resultContract", "VALID_PARTIAL");
        ((ObjectNode) emptyPartialObject.path("system").get(0).path("values").get(0))
                .set("value", objectMapper.createObjectNode());
        assertRejected(emptyPartialObject);

        ObjectNode emptyPartialArray = validResult();
        emptyPartialArray.put("resultContract", "VALID_PARTIAL");
        ((ObjectNode) emptyPartialArray.path("system").get(0).path("values").get(0))
                .set("value", objectMapper.createArrayNode());
        assertRejected(emptyPartialArray);
    }

    @Test
    void classifiesRejectedNetworkContractsWithoutExposingValidationText() {
        ObjectNode wrongSchema = validResult();
        wrongSchema.put("schemaVersion", "pipesim-network-result/2");
        assertThat(rejectionReason(wrongSchema)).isEqualTo("schema");

        ObjectNode noNodes = validResult();
        ((ArrayNode) noNodes.path("topology").path("nodes")).removeAll();
        assertThat(rejectionReason(noNodes)).isEqualTo("topology");

        ObjectNode noProfiles = validResult();
        ((ArrayNode) noProfiles.path("profiles")).removeAll();
        assertThat(rejectionReason(noProfiles)).isEqualTo("profile");

        ObjectNode missingQuality = validResult();
        ((ArrayNode) missingQuality.path("quality")).remove(0);
        assertThat(rejectionReason(missingQuality)).isEqualTo("quality");

        ObjectNode wrongStudy = validResult();
        wrongStudy.put("study", "C:\\private\\model.pips");
        assertThat(rejectionReason(wrongStudy)).isEqualTo("study");

        ObjectNode textualNumericValue = validResult();
        ((ObjectNode) textualNumericValue.path("system").get(0).path("values").get(0)).put("value", "100");
        assertThat(rejectionReason(textualNumericValue)).isEqualTo("numeric");
    }

    private void assertRejected(JsonNode result) {
        assertThatThrownBy(() -> validator.validate("network", "Network Study", result))
                .isInstanceOf(PipesimNetworkResultValidator.ResultValidationException.class);
    }

    private String rejectionReason(JsonNode result) {
        var resultValidator = new com.grdp.studio.softwareintegration.execution.PipesimResultValidator(
                new com.grdp.studio.softwareintegration.execution.PipesimWellResultValidator(), validator);
        Throwable rejection = catchThrowable(() -> resultValidator.validate("network", "Network Study", result));
        assertThat(rejection).isInstanceOf(
                com.grdp.studio.softwareintegration.execution.PipesimResultValidator.ResultValidationException.class);
        return ((com.grdp.studio.softwareintegration.execution.PipesimResultValidator.ResultValidationException) rejection)
                .networkReasonClass();
    }

    private ObjectNode validResult() {
        return (ObjectNode) objectMapper.readTree("""
                {"schemaVersion":"pipesim-network-result/1","model_kind":"network","runTask":"network",
                 "resultContract":"VALID_FULL","study":"Network Study","simulationState":"Completed",
                 "topology":{"nodes":[{"id":"Source 1","componentType":"SOURCE"},{"id":"Sink 1","componentType":"SINK"}],
                   "edges":[{"source":"Source 1","destination":"Sink 1","sourcePort":"OUTLET"}],
                   "counts":{"nodes":2,"edges":1,"sources":1,"sinks":1,"flowlines":1}},
                 "system":[{"variable":"Pressure","unit":"bar","values":[{"name":"Network","value":{"average":100.0}}]}],
                 "node":[{"variable":"Pressure","unit":"bar","values":[{"name":"Sink 1","value":null}]}],
                 "profiles":[{"branch":"Source 1 -> Sink 1","pointCount":2,"variables":[
                   {"variable":"TotalDistance","unit":"m","values":[0.0,null]},
                   {"variable":"Pressure","unit":"bar","values":[100.0,null]},
                   {"variable":"Temperature","unit":"degC","values":[20.0]}]}],
                 "summary":{"info":["completed"],"warnings":[],"errors":[]},"messages":["done"],
                  "quality":[
                    {"path":"node.Pressure.Sink 1","code":"UNAVAILABLE"},
                    {"path":"profiles.Source 1 -> Sink 1.TotalDistance[1]","code":"UNAVAILABLE"},
                    {"path":"profiles.Source 1 -> Sink 1.Pressure[1]","code":"NON_FINITE"}]}
                """);
    }

    private ObjectNode partialResult() {
        ObjectNode partial = validResult();
        partial.put("resultContract", "VALID_PARTIAL");
        partial.remove(List.of("system", "node", "profiles", "summary", "messages", "quality"));
        return partial;
    }
}
