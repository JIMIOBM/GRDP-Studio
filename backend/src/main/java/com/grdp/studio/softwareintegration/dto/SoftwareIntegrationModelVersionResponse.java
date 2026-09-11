package com.grdp.studio.softwareintegration.dto;

import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationDiagnosticSanitizer;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

public record SoftwareIntegrationModelVersionResponse(
        Long id, Integer versionNo, String originalName, Long sizeBytes, String sha256,
        String status, String modelKind, String validationMessage, List<String> studies, JsonNode inspection, LocalDateTime createdAt
) {
    public static SoftwareIntegrationModelVersionResponse from(SoftwareIntegrationModelVersionEntity entity) {
        List<String> studies = entity.getStudiesJson() == null || entity.getStudiesJson().isBlank()
                ? List.of() : Arrays.stream(entity.getStudiesJson().split("\\n"))
                .map(SoftwareIntegrationDiagnosticSanitizer::sanitize).toList();
        JsonNode inspection = "READY".equals(entity.getStatus()) && "eclipse_100".equals(entity.getModelKind())
                ? EclipseDataInspectionValidator.parsePersisted(entity.getInspectionJson()) : null;
        return new SoftwareIntegrationModelVersionResponse(entity.getId(), entity.getVersionNo(), entity.getOriginalName(), entity.getSizeBytes(), entity.getSha256(), entity.getStatus(), entity.getModelKind(),
                SoftwareIntegrationDiagnosticSanitizer.sanitize(entity.getValidationMessage()), studies, inspection, entity.getCreatedAt());
    }
}
