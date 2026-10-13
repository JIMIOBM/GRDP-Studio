package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.NetworkFlowlineDiameterParameters;
import com.grdp.studio.softwareintegration.support.PipesimNetworkInspectionValidator;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkFlowlineDiameterParametersTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void bindsTargetToInspectedFlowlineOriginalValueAndUnit() throws Exception {
        var inspectionInput = mapper.readTree("""
                {"schemaVersion":"pipesim-network-inspection/5","studies":[{"study":"Study 1","boundaries":[{"node":"Source_1","boundaryNodeType":"Source","isActive":true,"isSurfaceCondition":true,"flowRateType":null,"pressure":100,"temperature":80,"gasFlowRate":null,"liquidFlowRate":null,"massFlowRate":null}]}],"chokes":[],"packageFiles":[{"relativePath":"model.pips","sizeBytes":12,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"wellContexts":[],"flowlineContexts":["FL-2"],"flowlines":[{"name":"FL-2","innerDiameter":4.0,"unit":"in"}]}
                """);
        var inspection = PipesimNetworkInspectionValidator.validateNode(inspectionInput);
        var valid = mapper.readTree("""
                {"schemaVersion":"pipesim-network-flowline-diameter-parameters/1","baselineRunId":1001,"flowline":"FL-2","originalInnerDiameter":4.0,"unit":"in","targetInnerDiameter":5.0}
                """);
        assertThat(NetworkFlowlineDiameterParameters.valid(valid, inspection)).isTrue();
        assertThat(NetworkFlowlineDiameterParameters.valid(mapper.readTree(valid.toString().replace("\"originalInnerDiameter\":4.0", "\"originalInnerDiameter\":3.5")), inspection)).isFalse();
        assertThat(NetworkFlowlineDiameterParameters.valid(mapper.readTree(valid.toString().replace("\"unit\":\"in\"", "\"unit\":\"mm\"")), inspection)).isFalse();
        assertThat(NetworkFlowlineDiameterParameters.valid(mapper.readTree(valid.toString().replace("\"targetInnerDiameter\":5.0", "\"targetInnerDiameter\":4.0")), inspection)).isFalse();
    }
}
