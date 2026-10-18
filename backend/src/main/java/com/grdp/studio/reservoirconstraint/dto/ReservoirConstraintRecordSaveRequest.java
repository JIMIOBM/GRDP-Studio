package com.grdp.studio.reservoirconstraint.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 库级（storageId 维度）多周期预测约束条件保存请求。
 * 字段对应数据表 storage_prediction_constraint。
 */
public record ReservoirConstraintRecordSaveRequest(
        @NotNull Long projectId,
        @NotNull Long gasReservoirId,
        @NotNull Long storageId,
        String title,
        String state,
        @NotNull Object formJson,
        Object wellResultsJson,
        Object boundariesJson,
        String createdBy,
        String updatedBy
) {}