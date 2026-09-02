package com.grdp.studio.softwareintegration.dto.run;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record SoftwareIntegrationArtifactResponse(
        Long id,
        String name,
        String type,
        String contentType,
        Long sizeBytes,
        String sha256,
        LocalDateTime createdAt,
        LocalDateTime expiresAt
) {}
