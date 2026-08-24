package com.grdp.studio.pvtstorage.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record PvtSaveRequest(
        @Min(value = 1, message = "项目ID必须大于0") long projectId,
        @Min(value = 1, message = "气藏ID必须大于0") long gasReservoirId,
        @NotBlank(message = "井名不能为空") @Size(max = 255) String wellName,
        @Min(value = 1, message = "PVT编号必须大于0") int pvtNo,
        @NotBlank(message = "PVT名称不能为空") @Size(max = 100) String pvtName,
        @NotBlank(message = "性质类型不能为空") String propertyKind,
        @NotBlank(message = "保存区域不能为空") String section,
        String sourceType,
        @Valid GasInput gasInput,
        @Valid WaterInput waterInput,
        @Valid RockInput rockInput,
        Map<String, Object> settings,
        List<@Valid GasResultPoint> gasResults,
        List<@Valid WaterResultPoint> waterResults,
        List<@Valid RockResultPoint> rockResults
) {
    public record GasInput(
            @NotBlank(message = "天然气类型不能为空") String gasType,
            @NotNull(message = "天然气比重不能为空") Double specificGravity,
            @NotNull(message = "H2S不能为空") Double hydrogenSulfide,
            @NotNull(message = "CO2不能为空") Double carbonDioxide,
            @NotNull(message = "N2不能为空") Double nitrogen,
            Double condensateOilDensity
    ) {
    }

    public record WaterInput(
            @NotNull(message = "地层压力不能为空") Double formationPressure,
            @NotNull(message = "地层温度不能为空") Double formationTemperature,
            @NotNull(message = "矿化度不能为空") Double salinity
    ) {
    }

    public record RockInput(
            @NotNull(message = "岩石孔隙度不能为空") Double porosity,
            String rockType,
            String calculationMethod
    ) {
    }

    public record GasResultPoint(
            @NotNull(message = "压力不能为空") Double pressure,
            @NotNull(message = "温度不能为空") Double temperature,
            Double deviationFactor,
            Double pseudoPressure,
            Double volumeFactor,
            Double density,
            Double compressibility,
            Double viscosity
    ) {
    }

    public record WaterResultPoint(
            @NotNull(message = "压力不能为空") Double pressure,
            @NotNull(message = "温度不能为空") Double temperature,
            @NotNull(message = "矿化度不能为空") Double salinity,
            Double gasSolubility,
            Double volumeFactor,
            Double density,
            Double isothermalCompressibility,
            Double viscosity
    ) {
    }

    public record RockResultPoint(
            @NotBlank(message = "岩石曲线类型不能为空") String curveType,
            @NotNull(message = "岩石结果点序号不能为空") @Min(value = 1, message = "岩石结果点序号必须大于0") Integer pointNo,
            @NotNull(message = "岩石孔隙度不能为空") Double porosity,
            @NotNull(message = "岩石压缩系数不能为空") Double compressibilityFactor
    ) {
    }
}
