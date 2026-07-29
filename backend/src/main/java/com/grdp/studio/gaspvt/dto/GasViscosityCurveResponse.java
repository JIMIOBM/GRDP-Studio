package com.grdp.studio.gaspvt.dto;

import java.util.List;

public record GasViscosityCurveResponse(
        long toolboxId,
        List<GasViscosityPoint> items
) {
}
