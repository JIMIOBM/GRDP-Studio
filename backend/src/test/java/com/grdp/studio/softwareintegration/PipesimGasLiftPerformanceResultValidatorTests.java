package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.PipesimGasLiftPerformanceResultValidator;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class PipesimGasLiftPerformanceResultValidatorTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PipesimGasLiftPerformanceResultValidator validator =
            new PipesimGasLiftPerformanceResultValidator();

    @Test
    void acceptsOfficialGasLiftCaseNamesWithUnitSlash() {
        var result = objectMapper.readTree("""
                {
                  "schemaVersion":"pipesim-gas-lift-performance-result/1",
                  "model_kind":"black_oil_liquid",
                  "runTask":"gas-lift-performance",
                  "resultContract":"VALID_FULL",
                  "producer":"Well_1",
                  "outletPressurePsi":151,
                  "surfaceInjectionTemperatureF":110,
                  "targetInjectionRateMmscfd":1.1,
                  "reservoirPressurePsi":1700,
                  "gorScfPerStb":400,
                  "waterCutPercent":80,
                  "scanVariable":"gasLiftInjectionRate",
                  "scanUnit":"mmscf/d",
                  "productionUnit":"STB/d",
                  "cases":[
                    {"caseName":"INJGASRATE=0.55 mmscfd Flowrate=987.4514 sbbl/day","injectionRateMmscfd":0.55,"liquidRateStbPerDay":987.4513984865446},
                    {"caseName":"INJGASRATE=0.65 mmscfd Flowrate=1330.814 sbbl/day","injectionRateMmscfd":0.65,"liquidRateStbPerDay":1330.8137384764686}
                  ]
                }
                """);

        var validated = validator.validate(result);

        assertThat(validated.terminalStatus()).isEqualTo(SoftwareIntegrationRunStatus.SUCCEEDED);
        assertThat(validated.contract()).isEqualTo("VALID_FULL");
        assertThat(validated.result()).isSameAs(result);
    }
}
