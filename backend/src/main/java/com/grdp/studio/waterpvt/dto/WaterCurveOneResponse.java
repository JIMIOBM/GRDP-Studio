package com.grdp.studio.waterpvt.dto;

import java.util.List;

public record WaterCurveOneResponse(
        long toolboxId,
        List<WaterCurveOnePoint> items
) {
}
