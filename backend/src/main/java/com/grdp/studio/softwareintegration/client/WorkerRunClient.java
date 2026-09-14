package com.grdp.studio.softwareintegration.client;

public interface WorkerRunClient {
    WorkerAvailability availability();
    WorkerCapabilities capabilities();
    WorkerEclipseCapability eclipseCapability();
    WorkerRunAccepted execute(WorkerRunExecuteRequest request);
    WorkerRunSnapshot get(long runId, long afterSequence);
    void cancel(long runId);
}
