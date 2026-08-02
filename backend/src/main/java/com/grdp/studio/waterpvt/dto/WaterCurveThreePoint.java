package com.grdp.studio.waterpvt.dto;

public record WaterCurveThreePoint(
        double pressure,
        double temperature,
        double salinity,
        double isothermalCompressionCoefficient
) {
}
