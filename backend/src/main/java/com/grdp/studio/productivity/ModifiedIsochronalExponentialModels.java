package com.grdp.studio.productivity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public final class ModifiedIsochronalExponentialModels {
    private ModifiedIsochronalExponentialModels() {}

    public record CalculateRequest(
            @Positive long projectId,
            @Positive long gasReservoirId,
            @NotBlank String wellName,
            @Positive long pvtId,
            @NotBlank String operationType,
            @NotBlank String pressureMethod,
            @NotNull Double maximumFormationPressure,
            @Valid @NotEmpty List<InputPoint> inputItems,
            List<@Valid PseudoPressurePoint> pseudoPressurePoints,
            List<@Valid PressureFunctionDifference> pressureFunctionDifferences,
            List<@Valid PressureFunctionCurve> pressureFunctionCurves
    ) {
        public CalculateRequest(long projectId, long gasReservoirId, String wellName, long pvtId,
                                String operationType, String pressureMethod, Double maximumFormationPressure,
                                List<InputPoint> inputItems, List<PseudoPressurePoint> pseudoPressurePoints) {
            this(projectId, gasReservoirId, wellName, pvtId, operationType, pressureMethod,
                    maximumFormationPressure, inputItems, pseudoPressurePoints, null, null);
        }
    }

    public record InputPoint(@Positive int testPointNumber,
                             @NotNull Double testDailyGasProduction,
                             @NotNull Double reservoirPressure,
                             @NotNull Double testFlowPressure) {}

    public record PseudoPressurePoint(@NotNull Double pressure,
                                      @NotNull Double pseudoPressure) {}

    /** 原平台按所选 PVT 计算得到的测试点压力函数差。 */
    public record PressureFunctionDifference(@Positive int testPointNumber,
                                             @NotNull Double pressureFunctionDifference) {}

    /** 原平台 IPR 网格对应的压力函数差，用于仅替换指数式公式后重算 IPR。 */
    public record PressureFunctionCurve(@NotNull Double formationPressure,
                                        @Valid @NotEmpty List<PressureFunctionCurvePoint> points) {}

    public record PressureFunctionCurvePoint(@NotNull Double bottomHoleFlowingPressure,
                                             @NotNull Double pressureFunctionDifference) {}

    public record CurvePoint(double x, double y, String label) {}
    public record IprPoint(double gasProduction, double bottomHoleFlowingPressure, String label) {}
    public record IprCurve(double formationPressure, List<IprPoint> points) {}

    public record CalculateResponse(
            String calculationResultType,
            String pressureMethod,
            double productivityCoefficient,
            double productivityExponent,
            double transientProductivityCoefficient,
            double openFlowCapacity,
            double rSquared,
            String reliabilityDescription,
            String equation,
            List<CurvePoint> analysisPoints,
            List<CurvePoint> regressionLine,
            List<CurvePoint> transientLine,
            List<IprCurve> iprCurves
    ) {}
}
