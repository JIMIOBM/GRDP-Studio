package com.grdp.studio.softwareintegration.dto.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record SoftwareIntegrationRunEventResponse(
        Long id,
        Long sequence,
        Long workerSequence,
        String type,
        String status,
        String message,
        JsonNode error,
        LocalDateTime occurredAt
) {}
