package com.grdp.studio.dynamicproductivity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** 动态产能稳定流接口使用的请求和响应对象。 */
public final class DynamicStableDtos {
    private DynamicStableDtos() {}

    public record Summary(
            long stableId,
            int stableNo,
            String stableName,
            Long pvtId,
            String pvtName,
            String parameterSource
    ) {}

    public record SaveRequest(
            @Min(1) long projectId,
            @Min(1) long gasReservoirId,
            @NotBlank String wellName,
            Long stableId,
            String stableName,
            @NotBlank String wellType,
            Long pvtId,
            String pvtName,
            @NotBlank String parameterSource,
            @NotBlank String algorithmCode,
            @NotBlank String algorithmName,
            String remark,
            @Valid @NotNull Operation operation
    ) {}

    /** 只修改稳定流显示名称，不触碰参数、计算结果和曲线。 */
    public record RenameRequest(
            @Min(1) long projectId,
            @Min(1) long gasReservoirId,
            @NotBlank String wellName,
            @NotBlank @Size(max = 100) String stableName
    ) {}

    /** 一口井唯一的动态产能默认参数；不代表任何一次稳定流计算。 */
    public record DefaultParameterRequest(
            @Min(1) long projectId,
            @Min(1) long gasReservoirId,
            @NotBlank String wellName,
            @NotBlank String wellType,
            @Valid @NotNull Input input
    ) {}

    public record DefaultParameterDetail(String wellType, Input input) {}

    public record Operation(
            @NotBlank String operationType,
            @Valid @NotNull Input input,
            @NotEmpty List<@Valid Output> outputs
    ) {}

    /** 保存真正参与本次计算的参数快照，而不是以后可能已被修改的 PVT 当前值。 */
    public record Input(
            @NotBlank String gasType,
            @NotNull Double specificGravity,
            @NotNull Double hydrogenSulfide,
            @NotNull Double carbonDioxide,
            @NotNull Double nitrogen,
            @NotBlank String modificationMethod,
            @NotBlank String deviationFactorMethod,
            @NotBlank String viscosityMethod,
            @NotNull Double permeability,
            @NotNull Double formationThickness,
            @NotNull Double skinFactor,
            @NotNull Double drainageRadius,
            @NotNull Double wellboreRadius,
            Double horizontalSectionLength,
            @NotNull Double originalFormationPressure,
            @NotNull Double formationTemperature
    ) {}

    public record Output(
            @NotBlank String pressureMethod,
            @NotNull Double darcySeepageCoefficient,
            @NotNull Double nonDarcySeepageCoefficient,
            @NotNull Double openFlowCapacity,
            Double gradient,
            Double intercept,
            Double rSquared,
            String reliabilityLevel,
            String reliabilityDescription,
            List<@Valid IprPoint> iprPoints
    ) {}

    /**
     * IPR 曲线点。一次压力处理会产生多条曲线，curveNumber 用于保存其所属曲线。
     * Integer 保持可空，以便继续读取修复前只含 x、y 的旧 JSON；旧点按第 1 条曲线处理。
     */
    public record IprPoint(Integer curveNumber, @NotNull Double x, @NotNull Double y) {}

    public record Detail(Summary record, String wellType, Map<String, SavedOperation> operations) {}

    public record SavedOperation(String operationType, Input input, List<Output> outputs) {}
}
