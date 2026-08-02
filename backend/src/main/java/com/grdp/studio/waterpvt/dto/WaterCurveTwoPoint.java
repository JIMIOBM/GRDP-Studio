package com.grdp.studio.waterpvt.dto;

public record WaterCurveTwoPoint(
        double pressure,
        double temperature,
        double salinity,
        double volumeFactor,
        double density
) {
}
