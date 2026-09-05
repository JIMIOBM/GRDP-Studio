package com.grdp.studio.diagnostic.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public final class DiagnosticCurveModels {

    private DiagnosticCurveModels() {
    }

    public record ProductionDataItem(
            int sequence,
            String time,

            @NotNull
            Double gas,
            String cycle
    ) {
    }

    /**
     * PVT表中的 Z(P) 点。
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
     * 所选PVT表对应的Z信息。
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

            Double inventory,

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

    /**
     * 计算结果。
     */
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
            Double upperPressureLimit
    ) {
    }
}
