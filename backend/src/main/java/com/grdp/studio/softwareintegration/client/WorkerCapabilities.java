package com.grdp.studio.softwareintegration.client;

public record WorkerCapabilities(
        Boolean idle,
        WorkerSimulatorCapability pipesimWell,
        WorkerSimulatorCapability pipesimNetwork,
        WorkerSimulatorCapability eclipse100,
        WorkerSimulatorCapability eclipse300
) {
    public WorkerCapabilities(Boolean idle, WorkerSimulatorCapability pipesimWell, WorkerSimulatorCapability pipesimNetwork, WorkerSimulatorCapability eclipse100) {
        this(idle, pipesimWell, pipesimNetwork, eclipse100, null);
    }
}
