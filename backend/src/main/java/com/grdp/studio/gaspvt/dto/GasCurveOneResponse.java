package com.grdp.studio.gaspvt.dto;

import java.util.List;

public record GasCurveOneResponse(
        long deviationFactorToolboxId,
        long pseudoPressureToolboxId,
        List<GasCurveOnePoint> items
) {
}
