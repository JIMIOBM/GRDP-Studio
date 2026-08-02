package com.grdp.studio.waterpvt.dto;

public record WaterCurveOnePoint(
        double pressure,
        double temperature,
        double salinity,
        double gasSolubilityInWater
) {
}
