package com.grdp.studio.softwareintegration.client;

import java.util.List;

public record WorkerEclipseCapability(
        String version,
        String status,
        String reasonCode,
        List<String> runTasks,
        int maxTimeoutSeconds
) {
    public boolean isAvailableForEclipse() {
        return "2024.1".equals(version)
                && "AVAILABLE".equals(status)
                && runTasks != null
                && runTasks.contains("eclipse")
                && maxTimeoutSeconds >= 1800;
    }
}
