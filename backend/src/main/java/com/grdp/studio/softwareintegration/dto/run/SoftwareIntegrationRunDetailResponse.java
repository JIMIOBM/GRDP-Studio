package com.grdp.studio.softwareintegration.dto.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record SoftwareIntegrationRunDetailResponse(
        Long id,
        Long projectId,
        Long modelId,
        Long modelVersionId,
        String modelName,
        Integer versionNo,
        String status,
        String study,
        String runType,
        JsonNode parameters,
        LocalDateTime createdAt,
        LocalDateTime queuedAt,
        LocalDateTime claimedAt,
        LocalDateTime startedAt,
        LocalDateTime deadlineAt,
        LocalDateTime finishedAt,
        Integer timeoutSeconds,
        Long elapsedMillis,
        boolean cancellable,
        JsonNode error,
        JsonNode cleanup,
        String resultContract,
        JsonNode result,
        List<SoftwareIntegrationRunEventResponse> events,
        List<SoftwareIntegrationArtifactResponse> artifacts
) {}
