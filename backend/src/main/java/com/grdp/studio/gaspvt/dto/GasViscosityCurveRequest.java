package com.grdp.studio.gaspvt.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record GasViscosityCurveRequest(
        @NotNull @Positive Long projectId,
        @NotNull Integer gasType,
        @NotNull @Positive Double specificGravity,
        @NotNull @DecimalMin("0") Double h2SMoleFraction,
        @NotNull @DecimalMin("0") Double co2MoleFraction,
        @NotNull @DecimalMin("0") Double n2MoleFraction,
        @NotNull Double temperature,
        @NotNull @DecimalMin("0") Double pressureStart,
        @NotNull @DecimalMin("0") Double pressureEnd,
        @NotNull @Positive Double pressureStep,
        @NotNull Integer modificationMethod,
        @NotNull Integer deviationFactorMethod,
        @NotNull Integer viscosityMethod
) {
}
