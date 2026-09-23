package com.grdp.studio.softwareintegration.dto;

import java.util.List;

public record SoftwareIntegrationCapabilitiesResponse(
        WorkerCapability worker,
        SimulatorCapability pipesimWell,
        SimulatorCapability pipesimNetwork,
        SimulatorCapability eclipse100
) {
    public record WorkerCapability(String status, boolean idle, String reasonCode) {}

    public record SimulatorCapability(
            String version,
            String status,
            String reasonCode,
            List<String> runTasks,
            int maxTimeoutSeconds
    ) {}
}
