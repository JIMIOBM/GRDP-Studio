package com.grdp.studio.softwareintegration.dto;

import java.util.List;

public record SoftwareIntegrationCapabilitiesResponse(Eclipse100Capability eclipse100) {
    public record Eclipse100Capability(
            String version,
            String status,
            String reasonCode,
            List<String> runTasks,
            int maxTimeoutSeconds
    ) {}
}
