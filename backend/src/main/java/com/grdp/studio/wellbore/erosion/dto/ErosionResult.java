package com.grdp.studio.wellbore.erosion.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ErosionResult(
        Double sandFactor, Double liquidHoldupFactor, Double criticalErosionCoefficient,
        Double mixtureDensity, Double actualVelocity, Double criticalVelocity, Double velocityRatio,
        Double criticalGasRate, Status status, boolean applicable, boolean velocityDefinitionVerified,
        String reason, String criticalGasRateReason, String modelVersion) {
    public enum Status { BELOW_LIMIT, AT_LIMIT, ABOVE_LIMIT, NOT_APPLICABLE, CALCULATION_ERROR }
}
