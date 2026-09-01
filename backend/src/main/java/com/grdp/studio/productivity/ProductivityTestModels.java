package com.grdp.studio.productivity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.util.List;

public final class ProductivityTestModels {
    private ProductivityTestModels() {}

    public record Summary(long id, int testNo, String testName, LocalDate testDate,
                          String operationType, String testMethod, String status,
                          List<String> pressureMethods) {}

    public record EvaluationReference(String pressureMethod, Long evaluationId) {}

    public record InputItem(@Positive int testPointNumber, @NotNull Double testDailyGasProduction,
                            @NotNull Double reservoirPressure, @NotNull Double testFlowPressure) {}

    public record ChartPoint(@NotBlank String curveType, @Positive int pointNumber,
                             Integer sourcePointNumber,
                             @NotNull Double xValue, @NotNull Double yValue,
                             boolean deleted, String dataLabel) {}

    public record IprPoint(@Positive int curveNumber, @Positive int pointNumber,
                           Double formationPressure,
                           @NotNull Double gasProduction, @NotNull Double bottomHoleFlowingPressure,
                           boolean deleted, String dataLabel) {}

    public record Result(String calculationResultType, String pressureMethod, Long evaluationId,
                         Double darcySeepageCoefficient,
                         Double nonDarcySeepageCoefficient, Double openFlowCapacity,
                         Double productivityCoefficient, Double productivityExponent,
                         Double gradient, Double intercept, Double rSquared,
                         Integer reliabilityLevel, String reliabilityDescription,
                         List<ChartPoint> chartPoints, List<IprPoint> iprPoints) {}

    public record Input(Double maximumFormationPressure, Double formationTemperature,
                        Double onePointAlpha, @NotBlank String gasType,
                        @NotNull @Positive Double specificGravity,
                        Double hydrogenSulfide, Double carbonDioxide, Double nitrogen,
                        Double condensateOilDensity, String modificationMethod,
                        String deviationFactorMethod, String viscosityMethod) {}

    public record Detail(long id, long pvtId, int testNo, String testName, LocalDate testDate,
                         String operationType, String testMethod, String wellName, String wellType,
                         String status, Input input, List<InputItem> inputItems, Result result,
                         List<EvaluationReference> evaluations, List<Result> results) {}

    public record SaveRequest(Long testId, @Positive long projectId, @Positive long gasReservoirId,
                              @NotBlank String wellName, @Positive long pvtId,
                              @NotBlank String operationType, @NotBlank String testMethod,
                              Integer testNo, @NotNull LocalDate testDate, String wellType,
                              boolean replaceInput, @Valid @NotNull Input input,
                              @Valid @NotEmpty List<InputItem> inputItems,
                              @Valid @NotNull Result result) {}

    public record SaveResponse(long testId, int testNo, String testName) {}

    public record ImportedRows(List<InputItem> rows) {}
}
