package com.grdp.studio.softwareintegration.client;

public record WorkerRunAccepted(long runId, String state, String workerId, String generationId) {}
