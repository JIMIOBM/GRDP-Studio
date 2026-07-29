package com.grdp.studio.gaspvt.dto;

import java.util.List;

public record GasCurveTwoResponse(
        long volumeFactorToolboxId,
        long densityToolboxId,
        List<GasCurveTwoPoint> items
) {
}
