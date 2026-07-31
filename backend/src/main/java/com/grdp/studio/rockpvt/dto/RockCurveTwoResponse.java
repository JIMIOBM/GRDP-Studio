package com.grdp.studio.rockpvt.dto;

import java.util.List;

public record RockCurveTwoResponse(
        Long toolboxId,
        List<RockCurveTwoPoint> items
) {
}