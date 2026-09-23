package com.grdp.studio.softwareintegration.client;

import java.util.List;

public record WorkerSimulatorCapability(
        String version,
        String status,
        String reasonCode,
        List<String> runTasks,
        Integer maxTimeoutSeconds
) {}
