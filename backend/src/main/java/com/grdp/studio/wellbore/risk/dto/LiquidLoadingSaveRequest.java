package com.grdp.studio.wellbore.risk.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record LiquidLoadingSaveRequest(
        String calculationName,
        String remark,
        @Valid @NotNull LiquidLoadingRequest calculation
) {}
