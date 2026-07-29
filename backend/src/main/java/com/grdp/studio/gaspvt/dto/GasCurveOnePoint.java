package com.grdp.studio.gaspvt.dto;

public record GasCurveOnePoint(
        double pressure,
        double temperature,
        double deviationFactor,
        double pseudoPressure
) {
}
