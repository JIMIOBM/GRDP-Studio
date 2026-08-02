package com.grdp.studio.waterpvt.dto;

public record WaterViscosityPoint(
        double pressure,
        double temperature,
        double salinity,
        double viscosity
) {
}
