package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.PipesimGasLiftDiagnosticsResultValidator;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class PipesimGasLiftDiagnosticsResultValidatorTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PipesimGasLiftDiagnosticsResultValidator validator = new PipesimGasLiftDiagnosticsResultValidator();

    @Test
    void acceptsOfficialValveDiagnosticsWithUnavailableOptionalFields() {
        var result = objectMapper.readTree("""
                {
                  "schemaVersion":"pipesim-gas-lift-diagnostics-result/1",
                  "model_kind":"black_oil_liquid",
                  "runTask":"gas-lift-diagnostics",
                  "resultContract":"VALID_FULL",
                  "producer":"Well_1",
                  "outletPressurePsi":151,
                  "surfaceInjectionTemperatureF":110,
                  "targetInjectionRateMmscfd":1.1,
                  "reservoirPressurePsi":1700,
                  "gorScfPerStb":400,
                  "waterCutPercent":80,
                  "diagnosticType":"FIXEDINJECTION",
                  "throttling":"ON",
                  "usePhaseRatio":true,
                  "injectionUnit":"mmscf/d",
                  "liquidRateUnit":"STB/d",
                  "cases":[{
                    "caseName":"INJGASRATE=0.5 mmscfd Flowrate=986.524 sbbl/day",
                    "injectionRateMmscfd":0.5,
                    "liquidRateStbPerDay":986.5239826290447,
                    "valves":[{
                      "valveName":"GLI 1","positionStatus":"Open","status":"Throttling",
                      "gasRateNoThrottlingMmscfd":0.3395,"portDiameterIn":0.1875,"domeTemperatureF":82.97,
                      "closingPressurePsi":310.0,"openingPressurePsi":281.6,"ptroPsi":342.0,
                      "dischargeCoefficient":0.65,"portToBellowArea":0.093548,"operationMode":"PPO","portType":"SPRING"
                    },{
                      "valveName":"GLI","positionStatus":"Closed","status":null,
                      "gasRateNoThrottlingMmscfd":0.0,"portDiameterIn":0.1875,"domeTemperatureF":71.47,
                      "closingPressurePsi":360.7,"openingPressurePsi":338.7,"ptroPsi":398.0,
                      "dischargeCoefficient":0.65,"portToBellowArea":0.093548,"operationMode":"PPO","portType":"SPRING"
                    }]
                  },{
                    "caseName":"INJGASRATE=0.75 mmscfd Flowrate=1400.512 sbbl/day",
                    "injectionRateMmscfd":0.75,
                    "liquidRateStbPerDay":1400.5123998673212,
                    "valves":[{
                      "valveName":"GLI 1","positionStatus":"Closed","status":null,
                      "gasRateNoThrottlingMmscfd":0.0,"portDiameterIn":0.1875,"domeTemperatureF":96.84,
                      "closingPressurePsi":310.0,"openingPressurePsi":279.4,"ptroPsi":342.0,
                      "dischargeCoefficient":0.65,"portToBellowArea":0.093548,"operationMode":"PPO","portType":"SPRING"
                    },{
                      "valveName":"GLI","positionStatus":"Closed","status":null,
                      "gasRateNoThrottlingMmscfd":0.0,"portDiameterIn":0.1875,"domeTemperatureF":85.45,
                      "closingPressurePsi":360.7,"openingPressurePsi":336.5,"ptroPsi":398.0,
                      "dischargeCoefficient":0.65,"portToBellowArea":0.093548,"operationMode":"PPO","portType":"SPRING"
                    }]
                  }]
                }
                """);

        var validated = validator.validate(result);

        assertThat(validated.terminalStatus()).isEqualTo(SoftwareIntegrationRunStatus.SUCCEEDED);
        assertThat(validated.contract()).isEqualTo("VALID_FULL");
        assertThat(validated.result()).isSameAs(result);
    }
}
