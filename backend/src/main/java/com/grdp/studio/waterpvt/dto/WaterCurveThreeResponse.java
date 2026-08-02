package com.grdp.studio.waterpvt.dto;

import java.util.List;

public record WaterCurveThreeResponse(
        long toolboxId,
        List<WaterCurveThreePoint> items
) {
}
