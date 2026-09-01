package com.grdp.studio.productivitystorage.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.util.List;

public final class IsochronalTestDtos {
    private IsochronalTestDtos() {}

    public record Summary(long testId, int testNo, String testName, String wellName,
                          long pvtId, int pvtNo, String pressureMethod, String status,
                          String operationType) {}

    public record SaveRequest(
            @Min(1) long projectId,
            @Min(1) long gasReservoirId,
            @NotBlank String wellName,
            Long testId,
            @Min(1) int pvtNo,
            @NotBlank String pvtName,
            @NotBlank String operationType,
            LocalDate testDate,
            @Valid @NotNull Input input,
            @NotBlank String pressureMethod,
            @Valid @NotNull Result result
    ) {}

    public record Input(
            @NotNull Double maximumFormationPressure,
            @NotNull Double formationTemperature,
            Double onePointAlpha,
            @NotBlank String gasType,
            @NotNull @Positive Double specificGravity,
            Double hydrogenSulfide,
            Double carbonDioxide,
            Double nitrogen,
            Double condensateOilDensity,
            String modificationMethod,
            String deviationFactorMethod,
            String viscosityMethod,
            @NotEmpty List<@Valid InputPoint> points
    ) {}

    public record InputPoint(@Min(1) int pointNumber, @NotNull Double gasProduction,
                             @NotNull Double reservoirPressure, @NotNull Double flowPressure) {}

    public record Result(
            @NotBlank String calculationResultType,
            Double darcyCoefficient,
            Double nonDarcyCoefficient,
            Double productivityCoefficient,
            Double productivityExponent,
            @NotNull Double openFlowCapacity,
            Double gradient,
            Double intercept,
            Double rSquared,
            Integer reliabilityLevel,
            String reliabilityDescription,
            List<@Valid CurvePoint> analysisPoints,
            List<@Valid CurvePoint> regressionLine,
            List<@Valid CurvePoint> transientLine,
            List<@Valid IprCurve> iprCurves
    ) {}

    public record CurvePoint(@NotNull Double x, @NotNull Double y, String label) {}
    public record IprCurve(Double formationPressure, List<@Valid IprPoint> points) {}
    public record IprPoint(@NotNull Double gasProduction,
                           @NotNull Double bottomHoleFlowingPressure, String label) {}

    public record Detail(Summary record, Input input, String pressureMethod, Result result) {}
}
