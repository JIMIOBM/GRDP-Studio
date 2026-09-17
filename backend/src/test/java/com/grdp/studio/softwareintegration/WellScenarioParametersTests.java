package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.WellScenarioParameters;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WellScenarioParametersTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void rejectsInvalidValuesAndUnsupportedModels() {
        for (String value : new String[]{"true", "\"4000\"", "0", "-1", "100001", "null"}) {
            assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"pipesim-well-parameters/1\",\"reservoirPressurePsi\":" + value + "}"), "basic_gas", "nodal")).isFalse();
        }
        var valid = mapper.readTree("{\"schemaVersion\":\"pipesim-well-parameters/1\",\"reservoirPressurePsi\":4000}");
        assertThat(WellScenarioParameters.valid(valid, "basic_gas", "nodal")).isTrue();
        assertThat(WellScenarioParameters.valid(valid, "network", "nodal")).isFalse();
        for (String task : new String[]{"nodal", "profile", "combined", "network", "eclipse"}) {
            assertThat(WellScenarioParameters.valid(null, "basic_gas", task)).isTrue();
            assertThat(WellScenarioParameters.valid(valid, "basic_gas", task)).isEqualTo(java.util.Set.of("nodal", "profile", "combined").contains(task));
            assertThat(WellScenarioParameters.valid(valid, "black_oil_liquid", task)).isEqualTo("nodal".equals(task));
            assertThat(WellScenarioParameters.valid(valid, "legacy_well", task)).isFalse();
        }
    }
}
