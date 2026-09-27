package com.grdp.studio.wellbore.sand.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** 临界出砂产量预测请求。公式来自殷洪川等《页岩气井临界出砂产量预测方法》（特种油气藏，2023）。 */
public record SandProductionRequest(
        Long projectId,
        Long gasReservoirId,
        @NotBlank(message = "井名不能为空") String wellName,
        @NotBlank(message = "支撑剂类型不能为空") String proppantType,
        @NotNull(message = "支撑剂质量不能为空") @Positive Double massT,
        @Positive Double densityGCm3,
        @NotNull(message = "平均裂缝半长不能为空") @Positive Double halfLengthM,
        @NotNull(message = "闭合压力不能为空") @Positive Double closurePressureMpa,
        @Positive Double criticalVelocityOverrideMS,
        @PositiveOrZero Double actualRate1e4M3d
) {
    public SandProductionRequest(Long projectId, Long gasReservoirId, String wellName, String proppantType,
            Double massT, Double densityGCm3, Double halfLengthM, Double closurePressureMpa) {
        this(projectId, gasReservoirId, wellName, proppantType, massT, densityGCm3, halfLengthM,
                closurePressureMpa, null, null);
    }
}
