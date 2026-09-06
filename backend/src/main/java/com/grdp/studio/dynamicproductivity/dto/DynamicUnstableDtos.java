package com.grdp.studio.dynamicproductivity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** 动态产能不稳定流的计算、保存和历史快照对象。 */
public final class DynamicUnstableDtos {
    private DynamicUnstableDtos() {}

    public record Summary(long unstableId, int unstableNo, String unstableName,
                          Long pvtId, String pvtName, String parameterSource) {}

    /** 前端只提交可编辑参数；天然气物性和 A/B 均由后端计算。 */
    public record CalculateRequest(
            @Min(1) long projectId,
            @Min(1) long gasReservoirId,
            @NotBlank String wellName,
            @NotBlank String wellType,
            @NotBlank String operationType,
            @Valid @NotNull Input input
    ) {}

    public record SaveRequest(
            @Min(1) long projectId,
            @Min(1) long gasReservoirId,
            @NotBlank String wellName,
            Long unstableId,
            String unstableName,
            @NotBlank String wellType,
            Long pvtId,
            String pvtName,
            @NotBlank String parameterSource,
            @Valid @NotNull CalculatedOperation operation
    ) {}

    public record RenameRequest(
            @Min(1) long projectId,
            @Min(1) long gasReservoirId,
            @NotBlank String wellName,
            @NotBlank @Size(max = 100) String unstableName
    ) {}

    /** 一口井唯一的不稳定流默认参数，不占用“不稳定流N”的业务编号。 */
    public record DefaultParameterRequest(
            @Min(1) long projectId,
            @Min(1) long gasReservoirId,
            @NotBlank String wellName,
            @NotBlank String wellType,
            @Valid @NotNull Input input
    ) {}

    public record DefaultParameterDetail(String wellType, Input input) {}

    /** 单位采用页面单位：压力 MPa、温度 ℃、渗透率 mD、时间 d、总压缩系数 MPa^-1。 */
    public record Input(
            @NotBlank String gasType,
            @NotNull @Positive Double specificGravity,
            @NotNull Double hydrogenSulfide,
            @NotNull Double carbonDioxide,
            @NotNull Double nitrogen,
            @NotBlank String modificationMethod,
            @NotBlank String deviationFactorMethod,
            @NotBlank String viscosityMethod,
            @NotNull @Positive Double permeability,
            @NotNull @Positive Double formationThickness,
            @NotNull Double skinFactor,
            @NotNull @Positive Double porosity,
            @NotNull @Positive Double totalCompressibility,
            @NotNull @Positive Double flowTime,
            @NotNull @Positive Double drainageRadius,
            @NotNull @Positive Double wellboreRadius,
            Double horizontalSectionLength,
            @NotNull @Positive Double originalFormationPressure,
            @NotNull Double formationTemperature
    ) {}

    /** 保存工具箱结果和时间函数，确保以后可复现当次历史计算。 */
    public record Derived(
            double initialGasViscosity,
            double initialGasDeviationFactor,
            double standardGasDensity,
            double nonDarcyCoefficientBeta,
            Double diffusivity,
            String transientFunctionType,
            double transientFunctionValue
    ) {}

    public record Output(
            String pressureMethod,
            double darcySeepageCoefficient,
            double nonDarcySeepageCoefficient,
            Double openFlowCapacity,
            Double rSquared,
            List<IprPoint> iprPoints
    ) {}

    /** x 为气量(10^4m3/d)，y 为井底压力(MPa)。 */
    public record IprPoint(Integer curveNumber, @NotNull Double x, @NotNull Double y) {}

    public record CalculatedOperation(@NotBlank String operationType,
                                      @Valid @NotNull Input input,
                                      @NotNull Derived derived,
                                      @NotNull List<@Valid Output> outputs) {}

    public record CalculationResult(String wellType, CalculatedOperation operation) {}

    public record Detail(Summary record, String wellType,
                         Map<String, CalculatedOperation> operations) {}
}
