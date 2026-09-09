package com.grdp.studio.wellbore.risk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Map;

public record HydrateRequest(
        Long projectId,
        Long gasReservoirId,
        @NotBlank(message = "井名不能为空") String wellName,
        Long pvtId,
        Long temperatureId,
        Long pressureConversionId,
        @NotEmpty(message = "气体组分不能为空") Map<String, Double> composition,
        @NotNull @Positive Double pressureMpa,
        @NotNull Double actualTemperatureC,
        @Positive Double fugacityScale
) {
    public double effectiveFugacityScale() {
        return fugacityScale == null ? 2.0 : fugacityScale;
    }
}
