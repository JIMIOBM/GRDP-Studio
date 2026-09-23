package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.NetworkChokeBeanSizeParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkChokeBeanSizeParametersTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsChangedBeanSizeBoundToInspectedChoke() throws Exception {
        JsonNode parameters = mapper.readTree("""
                {"schemaVersion":"pipesim-network-choke-bean-size-parameters/1","baselineRunId":1001,"choke":"Choke","originalBeanSize":2.0,"targetBeanSize":3.0}
                """);
        JsonNode inspection = mapper.readTree("""
                {"schemaVersion":"pipesim-network-inspection/3","studies":[{"study":"Study 1","boundaries":[{"node":"Source-1","boundaryNodeType":"Source","isActive":true,"isSurfaceCondition":true,"flowRateType":"GasFlowRate","pressure":120,"temperature":80,"gasFlowRate":5,"liquidFlowRate":null,"massFlowRate":null}]}],"chokes":[{"name":"Choke","beanSize":2.0,"unit":"in"}],"packageFiles":[{"relativePath":"model.pips","sizeBytes":12,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}
                """);

        assertThat(NetworkChokeBeanSizeParameters.valid(parameters)).isTrue();
        assertThat(NetworkChokeBeanSizeParameters.matchesInspection(parameters, inspection)).isTrue();
    }

    @Test
    void rejectsSameValueOrWrongInspectedOriginal() throws Exception {
        JsonNode same = mapper.readTree("""
                {"schemaVersion":"pipesim-network-choke-bean-size-parameters/1","baselineRunId":1001,"choke":"Choke","originalBeanSize":2.0,"targetBeanSize":2.0}
                """);
        JsonNode wrongOriginal = mapper.readTree("""
                {"schemaVersion":"pipesim-network-choke-bean-size-parameters/1","baselineRunId":1001,"choke":"Choke","originalBeanSize":2.5,"targetBeanSize":3.0}
                """);
        JsonNode inspection = mapper.readTree("""
                {"schemaVersion":"pipesim-network-inspection/3","studies":[{"study":"Study 1","boundaries":[{"node":"Source-1","boundaryNodeType":"Source","isActive":true,"isSurfaceCondition":true,"flowRateType":null,"pressure":120,"temperature":80,"gasFlowRate":null,"liquidFlowRate":null,"massFlowRate":null}]}],"chokes":[{"name":"Choke","beanSize":2.0,"unit":"in"}],"packageFiles":[{"relativePath":"model.pips","sizeBytes":12,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}
                """);

        assertThat(NetworkChokeBeanSizeParameters.valid(same)).isFalse();
        assertThat(NetworkChokeBeanSizeParameters.valid(wrongOriginal)).isTrue();
        assertThat(NetworkChokeBeanSizeParameters.matchesInspection(wrongOriginal, inspection)).isFalse();
    }
}
