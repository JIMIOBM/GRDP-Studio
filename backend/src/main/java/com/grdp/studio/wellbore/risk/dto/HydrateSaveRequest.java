package com.grdp.studio.wellbore.risk.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record HydrateSaveRequest(
        String calculationName,
        String remark,
        @Valid @NotNull HydrateRequest calculation
) {}
