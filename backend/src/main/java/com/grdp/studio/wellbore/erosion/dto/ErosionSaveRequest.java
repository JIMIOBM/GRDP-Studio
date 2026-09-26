package com.grdp.studio.wellbore.erosion.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ErosionSaveRequest(@Size(max = 100) String calculationName,
                                 @NotNull @Valid ErosionRequest calculation,
                                 @Size(max = 500) String remark) {}
