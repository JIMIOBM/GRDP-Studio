package com.grdp.studio.softwareintegration.client;

public record WorkerRunExecuteRequest(
        long runId,
        String modelStorageKey,
        String expectedModelSha256,
        String study,
        String runTask,
        Object parameters,
        int timeoutSeconds
) {}
