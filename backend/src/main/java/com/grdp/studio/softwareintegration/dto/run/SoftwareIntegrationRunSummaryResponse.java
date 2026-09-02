package com.grdp.studio.softwareintegration.dto.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record SoftwareIntegrationRunSummaryResponse(
        Long id,
        Long projectId,
        Long modelId,
        Long modelVersionId,
        String modelName,
        Integer versionNo,
        String study,
        String runType,
        JsonNode parameters,
        String status,
        LocalDateTime createdAt,
        LocalDateTime queuedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long elapsedMillis,
        boolean cancellable
) {}
