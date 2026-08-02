package com.grdp.studio.waterpvt.dto;

import java.util.List;

public record WaterViscosityCurveResponse(
        long toolboxId,
        List<WaterViscosityPoint> items
) {
}
