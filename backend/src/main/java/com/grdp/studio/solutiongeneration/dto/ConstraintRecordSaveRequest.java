package com.grdp.studio.solutiongeneration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Map;

public record ConstraintRecordSaveRequest(
        @NotNull Long projectId,
        @NotNull Long gasReservoirId,
        @NotBlank String wellName,
        String recordName,
        @NotNull Integer wellboreStructure,
        @NotNull BigDecimal tubingInnerDiameterMm,
        BigDecimal erosionLimit,
        @NotNull BigDecimal sandProductionLimit,
        @NotNull BigDecimal liquidLoadingLimit,
        @NotNull BigDecimal wellheadExportPressure,
        @NotNull BigDecimal lowerBound,
        @NotNull BigDecimal upperBound,
        @NotNull Boolean complete,
        @NotNull Boolean feasible,
        @NotNull Boolean usableForPlan,
        Integer inputSchemaVersion,
        @NotNull Map<String, Object> inputsJson,
        @NotNull Map<String, Object> selectedConstraintsJson,
        @NotNull Map<String, Object> constraintResultsJson,
        @NotNull Map<String, Object> boundaryConditionsJson,
        @NotNull Map<String, Object> calculationMetaJson,
        Map<String, Object> algorithmRequestsJson,
        @NotBlank String calculatedAt
) {}
