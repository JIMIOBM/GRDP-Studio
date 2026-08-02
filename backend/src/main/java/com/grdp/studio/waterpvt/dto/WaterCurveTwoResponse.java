package com.grdp.studio.waterpvt.dto;

import java.util.List;

public record WaterCurveTwoResponse(
        long volumeFactorToolboxId,
        long densityToolboxId,
        List<WaterCurveTwoPoint> items
) {
}
