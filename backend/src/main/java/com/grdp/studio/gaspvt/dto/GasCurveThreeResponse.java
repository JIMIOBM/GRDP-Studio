package com.grdp.studio.gaspvt.dto;

import java.util.List;

public record GasCurveThreeResponse(
        long toolboxId,
        List<GasCurveThreePoint> items
) {
}
