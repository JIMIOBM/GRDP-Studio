package com.grdp.studio.softwareintegration.execution;

import java.util.EnumSet;
import java.util.Set;

public enum SoftwareIntegrationRunStatus {
    CREATED,
    QUEUED,
    CLAIMED,
    PREPARING,
    RUNNING_NODAL,
    RUNNING_PROFILE,
    COLLECTING,
    SUCCEEDED,
    PARTIAL_SUCCEEDED,
    FAILED,
    CANCEL_REQUESTED,
    CANCELLED,
    TIMED_OUT,
    WORKER_LOST;

    private static final Set<SoftwareIntegrationRunStatus> ACTIVE = EnumSet.of(
            CLAIMED, PREPARING, RUNNING_NODAL, RUNNING_PROFILE, COLLECTING, CANCEL_REQUESTED);
    private static final Set<SoftwareIntegrationRunStatus> TERMINAL = EnumSet.of(
            SUCCEEDED, PARTIAL_SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, WORKER_LOST);

    public boolean isActive() { return ACTIVE.contains(this); }
    public boolean isTerminal() { return TERMINAL.contains(this); }
}
