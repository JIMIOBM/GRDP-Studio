package com.grdp.studio.softwareintegration.client;

public record WorkerCapabilities(
        Boolean idle,
        WorkerSimulatorCapability pipesimWell,
        WorkerSimulatorCapability pipesimNetwork,
        WorkerSimulatorCapability eclipse100
) {}
