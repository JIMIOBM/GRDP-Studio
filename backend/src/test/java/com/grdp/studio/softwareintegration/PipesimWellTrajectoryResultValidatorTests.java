package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.PipesimWellTrajectoryResultValidator;
import com.grdp.studio.softwareintegration.execution.PipesimWellResultValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipesimWellTrajectoryResultValidatorTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PipesimWellTrajectoryResultValidator validator = new PipesimWellTrajectoryResultValidator();

    @Test
    void acceptsOfficialTrajectoryUnitWithRateDenominator() throws Exception {
        JsonNode result = mapper.readTree("""
                {
                  "schemaVersion":"pipesim-well-trajectory-result/1",
                  "model_kind":"black_oil_liquid",
                  "runTask":"trajectory",
                  "resultContract":"VALID_FULL",
                  "producer":"Well_1",
                  "units":{"measuredDepth":"ft","trueVerticalDepth":"ft","inclination":"deg","azimuth":"deg","maxDogLegSeverity":"deg/100ft"},
                  "points":[
                    {"measuredDepth":0.0,"trueVerticalDepth":0.0,"inclination":0.7,"azimuth":null,"maxDogLegSeverity":null},
                    {"measuredDepth":250.0,"trueVerticalDepth":249.9,"inclination":2.7,"azimuth":null,"maxDogLegSeverity":null}
                  ]
                }
                """);

        var validated = validator.validate(result);

        assertThat(validated.contract()).isEqualTo("VALID_FULL");
        assertThat(validated.terminalStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(validated.result().path("points")).hasSize(2);
    }

    @Test
    void rejectsPathLikeTrajectoryUnit() throws Exception {
        JsonNode result = mapper.readTree("""
                {
                  "schemaVersion":"pipesim-well-trajectory-result/1",
                  "model_kind":"black_oil_liquid",
                  "runTask":"trajectory",
                  "resultContract":"VALID_FULL",
                  "producer":"Well_1",
                  "units":{"measuredDepth":"C:\\\\private","trueVerticalDepth":"ft","inclination":"deg","azimuth":"deg","maxDogLegSeverity":"deg/100ft"},
                  "points":[
                    {"measuredDepth":0.0,"trueVerticalDepth":0.0,"inclination":0.7,"azimuth":null,"maxDogLegSeverity":null},
                    {"measuredDepth":250.0,"trueVerticalDepth":249.9,"inclination":2.7,"azimuth":null,"maxDogLegSeverity":null}
                  ]
                }
                """);

        assertThatThrownBy(() -> validator.validate(result))
                .isInstanceOf(PipesimWellResultValidator.ResultValidationException.class);
    }

    @Test
    void acceptsEditedTrajectoryOnlyWithExactProfileDeltaAndApplicationEvidence() throws Exception {
        String json = """
                {
                  "schemaVersion":"pipesim-well-trajectory-result/2",
                  "model_kind":"black_oil_liquid","runTask":"trajectory","resultContract":"VALID_FULL","producer":"Well_1",
                  "units":{"measuredDepth":"ft","trueVerticalDepth":"ft","inclination":"deg","azimuth":"deg","maxDogLegSeverity":"deg/100ft"},
                  "points":[
                    {"measuredDepth":0,"trueVerticalDepth":0,"inclination":0,"azimuth":null,"maxDogLegSeverity":null},
                    {"measuredDepth":500,"trueVerticalDepth":490,"inclination":26,"azimuth":null,"maxDogLegSeverity":null},
                    {"measuredDepth":1000,"trueVerticalDepth":980,"inclination":30,"azimuth":null,"maxDogLegSeverity":null}
                  ],
                  "application":{"applied":true,"isolatedCopy":true,"sourceModelUnchanged":true,"artifactName":"pipesim-trajectory-applied.pips","stationIndex":1,"originalInclinationDeg":25,"targetInclinationDeg":26,"maxTvdBeforeFt":980,"maxTvdAfterFt":980},
                  "profileComparison":{
                    "method":"exact-depth-match-no-interpolation","baselinePointCount":4,"targetPointCount":3,"matchedPointCount":2,
                    "baseline":[{"depth":0,"pressure":100,"temperature":60},{"depth":1,"pressure":90,"temperature":61},{"depth":1,"pressure":91,"temperature":61.5},{"depth":2,"pressure":80,"temperature":62}],
                    "target":[{"depth":0,"pressure":101,"temperature":60.5},{"depth":1,"pressure":88,"temperature":62},{"depth":2,"pressure":78,"temperature":63}],
                    "matchedPoints":[
                      {"depth":0,"baselinePressure":100,"targetPressure":101,"pressureDelta":1,"baselineTemperature":60,"targetTemperature":60.5,"temperatureDelta":0.5},
                      {"depth":2,"baselinePressure":80,"targetPressure":78,"pressureDelta":-2,"baselineTemperature":62,"targetTemperature":63,"temperatureDelta":1}
                    ],
                    "maxAbsPressureDelta":2,"maxAbsTemperatureDelta":1
                  }
                }
                """;
        var result = mapper.readTree(json);

        assertThat(validator.validate(result).result().path("profileComparison").path("matchedPointCount").asInt()).isEqualTo(2);
        assertThatThrownBy(() -> validator.validate(mapper.readTree(json.replace("\"pressureDelta\":-2", "\"pressureDelta\":3"))))
                .isInstanceOf(PipesimWellResultValidator.ResultValidationException.class);
    }
}
