package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.PipesimNetworkResultValidator;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        ObjectNode partial = validResult();
        partial.put("resultContract", "VALID_PARTIAL");
        assertRejected(partial);

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

    private void assertRejected(JsonNode result) {
        assertThatThrownBy(() -> validator.validate("network", "Network Study", result))
                .isInstanceOf(PipesimNetworkResultValidator.ResultValidationException.class);
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
}
