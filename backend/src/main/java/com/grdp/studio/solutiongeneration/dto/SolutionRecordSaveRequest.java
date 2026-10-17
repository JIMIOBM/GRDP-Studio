package com.grdp.studio.solutiongeneration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record SolutionRecordSaveRequest(
        @NotNull Long projectId,
        @NotNull Long gasReservoirId,
        @NotBlank String wellName,
        String recordName,
        BigDecimal targetProduction,
        BigDecimal exportPressure,
        Long constraintRecordId,
        String sourceRequestJson,
        String chartDataJson,
        String boundaryReportJson
) {}