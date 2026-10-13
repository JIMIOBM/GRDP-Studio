package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.NetworkConnectionParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkConnectionParametersTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void matchesOnlyOneExistingEdgeAndAnExistingReplacementNodeOnAFullBaseline() throws Exception {
        var parameters = mapper.readTree("""
                {"schemaVersion":"pipesim-network-connection-parameters/1","baselineRunId":41,
                 "source":"Flowline-1","sourcePort":"","originalDestination":"Junction-1","newDestination":"Junction-2"}
                """);
        var result = mapper.readTree("""
                {"schemaVersion":"pipesim-network-result/1","model_kind":"network","runTask":"network","resultContract":"VALID_FULL",
                 "topology":{"nodes":[{"id":"Flowline-1","componentType":"Flowline"},
                   {"id":"Junction-1","componentType":"Junction"},{"id":"Junction-2","componentType":"Junction"}],
                   "edges":[{"source":"Flowline-1","sourcePort":"","destination":"Junction-1"}]}}
                """);

        assertTrue(NetworkConnectionParameters.valid(parameters));
        assertTrue(NetworkConnectionParameters.matchesBaseline(parameters, result));
        assertFalse(NetworkConnectionParameters.matchesBaseline(parameters, mapper.readTree(result.toString().replace(
                "{\"source\":\"Flowline-1\",\"sourcePort\":\"\",\"destination\":\"Junction-1\"}",
                "{\"source\":\"Flowline-1\",\"sourcePort\":\"\",\"destination\":\"Junction-2\"}"))));
        assertFalse(NetworkConnectionParameters.matchesBaseline(parameters, mapper.readTree(result.toString().replace(
                "\"VALID_FULL\"", "\"VALID_PARTIAL\""))));
    }

    @Test
    void rejectsExtraFieldsEqualEndpointsAndInvalidSeparatorPorts() throws Exception {
        var valid = """
                {"schemaVersion":"pipesim-network-connection-parameters/1","baselineRunId":41,
                 "source":"Separator-1","sourcePort":"Top Port","originalDestination":"Flowline-1","newDestination":"Sink-1"}
                """;
        assertTrue(NetworkConnectionParameters.valid(mapper.readTree(valid)));
        assertFalse(NetworkConnectionParameters.valid(mapper.readTree(valid.replace("Top Port", "Side Port"))));
        assertFalse(NetworkConnectionParameters.valid(mapper.readTree(valid.replace("\"newDestination\":\"Sink-1\"", "\"newDestination\":\"Flowline-1\""))));
        assertFalse(NetworkConnectionParameters.valid(mapper.readTree(valid.replace("\"newDestination\":\"Sink-1\"", "\"newDestination\":\"Sink-1\",\"extra\":true"))));
    }

    @Test
    void rejectsOccupiedSingleInletTargetsAndOriginalFlowlineInletLoss() throws Exception {
        var parameters = mapper.readTree("""
                {"schemaVersion":"pipesim-network-connection-parameters/1","baselineRunId":41,
                 "source":"Flowline-1","sourcePort":"","originalDestination":"Junction-1","newDestination":"Choke-1"}
                """);
        var result = mapper.readTree("""
                {"schemaVersion":"pipesim-network-result/1","model_kind":"network","runTask":"network","resultContract":"VALID_FULL",
                 "topology":{"nodes":[{"id":"Flowline-1","componentType":"Flowline"},
                   {"id":"Junction-1","componentType":"Junction"},{"id":"Choke-1","componentType":"Choke"}],
                   "edges":[{"source":"Flowline-1","sourcePort":"","destination":"Junction-1"},
                     {"source":"Junction-1","sourcePort":"","destination":"Choke-1"}]}}
                """);

        assertTrue(NetworkConnectionParameters.targetInletUnavailable(parameters, result));
        assertFalse(NetworkConnectionParameters.matchesBaseline(parameters, result));

        var flowlineParameters = mapper.readTree("""
                {"schemaVersion":"pipesim-network-connection-parameters/1","baselineRunId":41,
                 "source":"Source-1","sourcePort":"","originalDestination":"Flowline-1","newDestination":"Junction-1"}
                """);
        var flowlineResult = mapper.readTree("""
                {"schemaVersion":"pipesim-network-result/1","model_kind":"network","runTask":"network","resultContract":"VALID_FULL",
                 "topology":{"nodes":[{"id":"Source-1","componentType":"Source"},
                   {"id":"Flowline-1","componentType":"Flowline"},{"id":"Junction-1","componentType":"Junction"}],
                   "edges":[{"source":"Source-1","sourcePort":"","destination":"Flowline-1"},
                     {"source":"Flowline-1","sourcePort":"","destination":"Junction-1"}]}}
                """);
        assertTrue(NetworkConnectionParameters.originalFlowlineInletWouldBeDisconnected(flowlineParameters, flowlineResult));
        assertFalse(NetworkConnectionParameters.matchesBaseline(flowlineParameters, flowlineResult));
    }
}
