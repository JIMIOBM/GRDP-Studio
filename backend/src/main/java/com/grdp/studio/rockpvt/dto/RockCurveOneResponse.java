package com.grdp.studio.rockpvt.dto;

import java.util.List;

public record RockCurveOneResponse(
        Long toolboxId,
        List<RockCurveOnePoint> items
) {
}