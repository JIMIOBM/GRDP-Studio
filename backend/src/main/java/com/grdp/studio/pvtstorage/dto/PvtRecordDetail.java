package com.grdp.studio.pvtstorage.dto;

import com.grdp.studio.pvtstorage.entity.PvtGasInputEntity;
import com.grdp.studio.pvtstorage.entity.PvtGasResultEntity;
import com.grdp.studio.pvtstorage.entity.PvtRockInputEntity;
import com.grdp.studio.pvtstorage.entity.PvtRockResultEntity;
import com.grdp.studio.pvtstorage.entity.PvtWaterInputEntity;
import com.grdp.studio.pvtstorage.entity.PvtWaterResultEntity;

import java.util.List;
import java.util.Map;

/** 左侧已有 PVT 节点进入修改页面时所需的完整数据。 */
public record PvtRecordDetail(
        PvtRecordSummary record,
        GasInput gasInput,
        WaterInput waterInput,
        RockInput rockInput,
        Map<String, String> settings,
        List<GasResultPoint> gasResults,
        List<WaterResultPoint> waterResults,
        List<RockResultPoint> rockResults
) {
    public record GasInput(
            String gasType,
            Double specificGravity,
            Double hydrogenSulfide,
            Double carbonDioxide,
            Double nitrogen,
            Double condensateOilDensity
    ) {
        public static GasInput from(PvtGasInputEntity entity) {
            return entity == null ? null : new GasInput(
                    entity.getGasType(), entity.getSpecificGravity(), entity.getHydrogenSulfide(),
                    entity.getCarbonDioxide(), entity.getNitrogen(), entity.getCondensateOilDensity());
        }
    }

    public record WaterInput(
            Double formationPressure,
            Double formationTemperature,
            Double salinity
    ) {
        public static WaterInput from(PvtWaterInputEntity entity) {
            return entity == null ? null : new WaterInput(
                    entity.getFormationPressure(), entity.getFormationTemperature(), entity.getSalinity());
        }
    }

    public record RockInput(Double porosity, String rockType, String calculationMethod) {
        public static RockInput from(PvtRockInputEntity entity) {
            return entity == null ? null : new RockInput(
                    entity.getPorosity(), entity.getRockType(), entity.getCalculationMethod());
        }
    }

    public record GasResultPoint(
            Integer pointNo,
            Double pressure,
            Double temperature,
            Double deviationFactor,
            Double pseudoPressure,
            Double volumeFactor,
            Double density,
            Double compressibility,
            Double viscosity
    ) {
        public static GasResultPoint from(PvtGasResultEntity entity) {
            return new GasResultPoint(
                    entity.getPointNo(), entity.getPressure(), entity.getTemperature(),
                    entity.getDeviationFactor(), entity.getPseudoPressure(), entity.getVolumeFactor(),
                    entity.getDensity(), entity.getCompressibility(), entity.getViscosity());
        }
    }

    public record WaterResultPoint(
            Integer pointNo,
            Double pressure,
            Double temperature,
            Double salinity,
            Double gasSolubility,
            Double volumeFactor,
            Double density,
            Double isothermalCompressibility,
            Double viscosity
    ) {
        public static WaterResultPoint from(PvtWaterResultEntity entity) {
            return new WaterResultPoint(
                    entity.getPointNo(), entity.getPressure(), entity.getTemperature(), entity.getSalinity(),
                    entity.getGasSolubility(), entity.getVolumeFactor(), entity.getDensity(),
                    entity.getIsothermalCompressibility(), entity.getViscosity());
        }
    }

    public record RockResultPoint(
            String curveType,
            Integer pointNo,
            Double porosity,
            Double compressibilityFactor
    ) {
        public static RockResultPoint from(PvtRockResultEntity entity) {
            return new RockResultPoint(
                    entity.getCurveType(), entity.getPointNo(), entity.getPorosity(),
                    entity.getCompressibilityFactor());
        }
    }
}
