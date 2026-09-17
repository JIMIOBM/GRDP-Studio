package com.grdp.studio.wellbore.pressure.dto;

import com.grdp.studio.wellbore.pressure.method.PressureCalculator;

public record PressureCalculateResponse(PressureCalculator.Result result) {
    public static PressureCalculateResponse from(PressureCalculator.Result result) {
        return new PressureCalculateResponse(result);
    }
}
