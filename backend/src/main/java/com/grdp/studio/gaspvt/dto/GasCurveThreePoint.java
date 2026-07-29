package com.grdp.studio.gaspvt.dto;

public record GasCurveThreePoint(
        double pressure,
        double temperature,
        double compressibility
) {
}
