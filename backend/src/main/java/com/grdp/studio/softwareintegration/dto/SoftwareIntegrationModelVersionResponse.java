package com.grdp.studio.softwareintegration.dto;

import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;

import java.time.LocalDateTime;
import java.util.List;

public record SoftwareIntegrationModelVersionResponse(
        Long id, Integer versionNo, String originalName, Long sizeBytes, String sha256,
        String status, String validationMessage, List<String> studies, LocalDateTime createdAt
) {
    public static SoftwareIntegrationModelVersionResponse from(SoftwareIntegrationModelVersionEntity entity) {
        List<String> studies = entity.getStudiesJson() == null || entity.getStudiesJson().isBlank()
                ? List.of() : List.of(entity.getStudiesJson().split("\\n"));
        return new SoftwareIntegrationModelVersionResponse(entity.getId(), entity.getVersionNo(), entity.getOriginalName(), entity.getSizeBytes(), entity.getSha256(), entity.getStatus(), entity.getValidationMessage(), studies, entity.getCreatedAt());
    }
}
