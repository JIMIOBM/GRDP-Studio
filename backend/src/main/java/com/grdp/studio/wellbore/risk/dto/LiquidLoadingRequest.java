package com.grdp.studio.wellbore.risk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record LiquidLoadingRequest(
        Long projectId,
        Long gasReservoirId,
        @NotBlank(message = "井名不能为空") String wellName,
        @NotNull @PositiveOrZero Double qg,
        @PositiveOrZero Double qw,
        @NotNull @Positive Double pressureMpa,
        @NotNull Double temperatureC,
        Double gasSpecificGravity,
        Double liquidDensityKgM3,
        @NotNull @Positive Double surfaceTensionMnM,
        @NotNull @Positive Double tubingIdMm,
        Long pvtId,
        java.util.Map<String, Object> pvtSnapshot
) {
    public LiquidLoadingRequest(Long projectId, Long gasReservoirId, String wellName, Double qg, Double qw,
            Double pressureMpa, Double temperatureC, Double gasSpecificGravity, Double liquidDensityKgM3,
            Double surfaceTensionMnM, Double tubingIdMm) {
        this(projectId, gasReservoirId, wellName, qg, qw, pressureMpa, temperatureC, gasSpecificGravity,
                liquidDensityKgM3, surfaceTensionMnM, tubingIdMm, null, null);
    }
}
