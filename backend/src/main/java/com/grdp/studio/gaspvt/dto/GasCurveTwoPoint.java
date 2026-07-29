package com.grdp.studio.gaspvt.dto;

public record GasCurveTwoPoint(
        double pressure,
        double temperature,
        double volumeFactor,
        double density
) {
}
