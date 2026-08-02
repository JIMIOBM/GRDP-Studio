package com.grdp.studio.waterpvt.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record WaterPvtCurveRequest(
        @NotNull @Positive Long projectId,
        @NotNull @DecimalMin("0") Double salinity,
        @NotNull @Positive Double originalPressure,
        @NotNull Double temperature,
        @NotNull @DecimalMin("0") Double pressureStart,
        @NotNull @DecimalMin("0") Double pressureEnd,
        @NotNull @Positive Double pressureStep,
        @NotNull Integer volumeFactorMethod,
        @NotNull Integer compressibilityMethod
) {
}
