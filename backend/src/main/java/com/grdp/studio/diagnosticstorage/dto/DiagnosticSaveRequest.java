package com.grdp.studio.diagnosticstorage.dto;

import com.grdp.studio.diagnostic.dto.DiagnosticCurveModels;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 保存诊断方案的完整请求。
 */
public record DiagnosticSaveRequest(
        Long diagnosticId,

        @Min(value = 1, message = "项目ID必须大于0")
        long projectId,

        @Min(value = 1, message = "气藏ID必须大于0")
        long gasReservoirId,

        @NotBlank(message = "井名不能为空")
        @Size(max = 255, message = "井名长度不能超过255")
        String wellName,

        @Size(max = 200, message = "诊断方案名称长度不能超过200")
        String diagnosticName,

        String status,

        Long pvtId,

        @Valid
        DiagnosticCurveModels.PvtData pvtSnapshot,

        Double upperPressureLimit,

        Double lowerPressureLimit,

        @Size(max = 1000, message = "备注长度不能超过1000")
        String remark,

        List<DiagnosticCurveModels.@Valid ProductionDataItem> productionData,

        @Valid
        DiagnosticCurveModels.CalculateResponse result
) {
}
