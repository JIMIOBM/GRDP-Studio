package com.grdp.studio.diagnosticstorage.dto;

public record DiagnosticSaveResponse(
        long diagnosticId,
        int diagnosticNo,
        String diagnosticName,
        int savedRows,
        String status,
        boolean hasResult,
        String calculationVersion
) {
}
