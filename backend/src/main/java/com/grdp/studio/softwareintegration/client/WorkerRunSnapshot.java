package com.grdp.studio.softwareintegration.client;

import tools.jackson.databind.JsonNode;

import java.util.List;

public record WorkerRunSnapshot(
        long runId,
        String state,
        long lastSequence,
        String workerId,
        String generationId,
        List<WorkerRunEvent> events,
        JsonNode result,
        JsonNode error,
        List<WorkerRunArtifact> artifacts,
        JsonNode cleanup
) {
    public boolean cleanupConfirmsProcessExit() {
        return cleanup != null && cleanup.isObject()
                && cleanup.has("processTreeExitConfirmed")
                && cleanup.path("processTreeExitConfirmed").asBoolean(false);
    }
}
