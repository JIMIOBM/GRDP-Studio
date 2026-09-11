package com.grdp.studio.diagnostic.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public final class DiagnosticCurveModels {

    private DiagnosticCurveModels() {
    }

    /**
     * Excel 导入的一行注采数据。
     *
     * <p>前端当前统一约定：
     * 注气通常为负值，采气通常为正值。
     * 后端不会直接拿原始正负号累加，而是先判断注/采方向，
     * 再使用绝对气量计算库存变化。</p>
     */
    public record ProductionDataItem(
            int sequence,
            String time,

            @NotNull
            Double gas,

            String cycle
    ) {
    }

    /**
     * PVT 表中的一个 Pressure-Z 数据点。
     */
    public record PvtZPoint(
            @NotNull
            @Positive
            Double pressure,

            @NotNull
            @Positive
            Double zFactor
    ) {
    }

    /**
     * 所选 PVT 数据。
     *
     * <p>优先使用 zCurve：
     * 选定的是同一张 PVT 表，但 Z 可以随压力 P 变化。
     * 仅当 PVT 确实只有一个 Z 时才使用 fixedZ。</p>
     */
    public record PvtData(
            Double fixedZ,
            List<@Valid PvtZPoint> zCurve
    ) {
    }

    /**
     * 计算请求。
     */
    public record CalculateRequest(
            @Positive
            long projectId,

            @Positive
            long gasReservoirId,

            @NotEmpty
            String wellName,

            @Positive
            long pvtId,

            @NotNull
            @Positive
            Double upperLimit,

            @NotNull
            @Positive
            Double lowerLimit,

            @NotNull
            @Valid
            PvtData pvt,

            @NotEmpty
            List<@Valid ProductionDataItem> productionData
    ) {
    }

    public record ChartPoint(
            Double inventory,
            Double pressureOverZ
    ) {
    }

    public record RunningPoint(
            int sequence,
            String time,
            String cycle,
            String direction,
            Double gas,
            Double cumulativeNetGas,
            Double inventory,
            Double stablePressureOverZ,
            Double estimatedPressure,
            Double zFactor,
            Double pressureOverZ,
            boolean synthetic
    ) {
    }

    /**
     * 一个完整周期单独一条曲线。
     */
    public record CycleCurve(
            String cycle,
            List<RunningPoint> points
    ) {
    }
    public record CalculateResponse(
            List<CycleCurve> cycleCurves,
            List<RunningPoint> runningCurve,
            List<ChartPoint> standardLine,
            Double baseInventory,
            Double minInventory,
            Double maxInventory,
            Double minPressureOverZ,
            Double maxPressureOverZ,
            Double standardLineSlope,
            Double lowerPressureLimit,
            Double upperPressureLimit,
            Double lowerZ,
            Double upperZ,
            Double lowerPressureOverZ,
            Double upperPressureOverZ,
            String pvtMode
    ) {
    }
}
