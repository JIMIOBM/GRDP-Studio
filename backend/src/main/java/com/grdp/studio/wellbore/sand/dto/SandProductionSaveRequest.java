package com.grdp.studio.wellbore.sand.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record SandProductionSaveRequest(
        String calculationName,
        String remark,
        @Valid @NotNull SandProductionRequest calculation
) {}
