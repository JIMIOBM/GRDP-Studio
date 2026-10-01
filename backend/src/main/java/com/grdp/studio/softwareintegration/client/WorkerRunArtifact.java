package com.grdp.studio.softwareintegration.client;

public record WorkerRunArtifact(
        String storageKey,
        long size,
        String sha256,
        String contentType
) {}
