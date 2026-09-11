package com.grdp.studio.diagnosticstorage.dto;

import com.grdp.studio.diagnosticstorage.entity.DiagnosticEntity;

import java.time.LocalDateTime;

/** 左侧“诊断曲线1/诊断曲线2...”节点展示所需的最小数据。 */
public record DiagnosticRecordSummary(
        long diagnosticId,
        int diagnosticNo,
        String diagnosticName,
        String status,
        Long pvtId,
        String pvtName,
        LocalDateTime updatedAt
) {
    public static DiagnosticRecordSummary from(DiagnosticEntity entity) {
        return new DiagnosticRecordSummary(
                entity.getId(),
                entity.getDiagnosticNo(),
                entity.getDiagnosticName(),
                entity.getStatus(),
                entity.getPvtId(),
                entity.getPvtName(),
                entity.getUpdatedAt()
        );
    }
}
