package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.PipesimNetworkOptimizerResultValidator;
import com.grdp.studio.softwareintegration.execution.PipesimWellResultValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipesimNetworkOptimizerResultValidatorTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PipesimNetworkOptimizerResultValidator validator = new PipesimNetworkOptimizerResultValidator();

    @Test
    void acceptsOfficialSparseGroupsAndEngineeringUnits() throws Exception {
        JsonNode result = mapper.readTree("""
                {
                  "schemaVersion":"pipesim-network-optimizer-result/1",
                  "model_kind":"network",
                  "runTask":"network-optimizer",
                  "resultContract":"VALID_FULL",
                  "simulationState":"Completed",
                  "summary":{"info":[],"warnings":[],"errors":[]},
                  "messages":[],
                  "variables":[
                    {"key":"OptimizerLiquid_rate","label":"Liquid rate","unit":"STB/d"},
                    {"key":"OptimizerGas_lift_rate","label":"Gas lift rate","unit":"mmscf/d"},
                    {"key":"OptimizerWell_is_shut_off","label":"Well is shut off","unit":""}
                  ],
                  "wells":[{"name":"Well_1","values":[
                    {"key":"OptimizerLiquid_rate","value":123.4},
                    {"key":"OptimizerWell_is_shut_off","value":false}
                  ]}],
                  "flowlines":[{"name":"B_1","values":[
                    {"key":"OptimizerLiquid_rate","value":null}
                  ]}],
                  "sinks":[{"name":"CPF","values":[
                    {"key":"OptimizerGas_lift_rate","value":0.5}
                  ]}],
                  "quality":[{"path":"flowlines.B_1.OptimizerLiquid_rate","code":"UNAVAILABLE"}]
                }
                """);

        var validated = validator.validate(result);

        assertThat(validated.contract()).isEqualTo("VALID_FULL");
        assertThat(validated.terminalStatus().name()).isEqualTo("SUCCEEDED");
    }

    @Test
    void rejectsVariablesOutsideMetadataAndQualityMismatch() throws Exception {
        JsonNode result = mapper.readTree("""
                {
                  "schemaVersion":"pipesim-network-optimizer-result/1","model_kind":"network",
                  "runTask":"network-optimizer","resultContract":"VALID_FULL","simulationState":"Completed",
                  "summary":{"info":[],"warnings":[],"errors":[]},"messages":[],
                  "variables":[{"key":"OptimizerLiquid_rate","label":"Liquid rate","unit":"STB/d"}],
                  "wells":[{"name":"Well_1","values":[{"key":"Unknown","value":1.0}]}],
                  "flowlines":[{"name":"B_1","values":[{"key":"OptimizerLiquid_rate","value":1.0}]}],
                  "sinks":[{"name":"CPF","values":[{"key":"OptimizerLiquid_rate","value":1.0}]}],
                  "quality":[]
                }
                """);

        assertThatThrownBy(() -> validator.validate(result))
                .isInstanceOf(PipesimWellResultValidator.ResultValidationException.class);
    }

    @Test
    void acceptsAppliedResultsAuditForAnIsolatedModelCopy() throws Exception {
        JsonNode result = mapper.readTree("""
                {
                  "schemaVersion":"pipesim-network-optimizer-result/2","model_kind":"network",
                  "runTask":"network-optimizer","resultContract":"VALID_FULL","simulationState":"Completed",
                  "summary":{"info":[],"warnings":[],"errors":[]},"messages":[],
                  "variables":[{"key":"OptimizerGas_lift_rate","label":"Gas lift rate","unit":"mmscf/d"}],
                  "wells":[{"name":"Well_1","values":[{"key":"OptimizerGas_lift_rate","value":0.5663}]}],
                  "flowlines":[{"name":"B_1","values":[{"key":"OptimizerGas_lift_rate","value":null}]}],
                  "sinks":[{"name":"CPF","values":[{"key":"OptimizerGas_lift_rate","value":null}]}],
                  "quality":[
                    {"path":"flowlines.B_1.OptimizerGas_lift_rate","code":"UNAVAILABLE"},
                    {"path":"sinks.CPF.OptimizerGas_lift_rate","code":"UNAVAILABLE"}
                  ],
                  "application":{
                    "requested":true,"applied":true,"scope":"isolated-model-copy","sourceModelUnchanged":true,
                    "artifactName":"pipesim-network-optimizer-applied.pips",
                    "changes":[{"context":"Well_1:Tubing_Gas lift injection","parameter":"GasRate","unit":"mmscf/d","before":0.4488,"after":0.5663}]
                  }
                }
                """);

        assertThat(validator.validate(result).contract()).isEqualTo("VALID_FULL");
    }
}
