package com.grdp.studio.storagescheme.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 库级多周期预测方案保存请求，对应数据表 storage_prediction_scheme。
 */
public record StoragePredictionSchemeSaveRequest(
        @NotNull Long projectId,
        @NotNull Long gasReservoirId,
        @NotNull Long storageId,
        String title,
        Long constraintRecordId,
        @NotNull Long originalPredictionId,
        @NotNull Object wellNamesJson,
        @NotNull Object calcInputJson,
        Object boundariesJson,
        @NotNull Object chartsJson,
        String forecastStartTime,
        String createdBy,
        String updatedBy
) {}