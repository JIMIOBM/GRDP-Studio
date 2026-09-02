package com.grdp.studio.softwareintegration.execution;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class SoftwareIntegrationRunStateMachine {
    private static final Map<SoftwareIntegrationRunStatus, Set<SoftwareIntegrationRunStatus>> ALLOWED =
            new EnumMap<>(SoftwareIntegrationRunStatus.class);

    static {
        allow(SoftwareIntegrationRunStatus.CREATED, SoftwareIntegrationRunStatus.QUEUED, SoftwareIntegrationRunStatus.CANCELLED);
        allow(SoftwareIntegrationRunStatus.QUEUED, SoftwareIntegrationRunStatus.CLAIMED, SoftwareIntegrationRunStatus.CANCELLED);
        allow(SoftwareIntegrationRunStatus.CLAIMED, SoftwareIntegrationRunStatus.QUEUED, SoftwareIntegrationRunStatus.PREPARING,
                SoftwareIntegrationRunStatus.CANCEL_REQUESTED, SoftwareIntegrationRunStatus.FAILED, SoftwareIntegrationRunStatus.WORKER_LOST);
        allow(SoftwareIntegrationRunStatus.PREPARING, SoftwareIntegrationRunStatus.RUNNING_NODAL,
                SoftwareIntegrationRunStatus.RUNNING_PROFILE, SoftwareIntegrationRunStatus.COLLECTING,
                SoftwareIntegrationRunStatus.CANCEL_REQUESTED, SoftwareIntegrationRunStatus.FAILED, SoftwareIntegrationRunStatus.WORKER_LOST);
        allow(SoftwareIntegrationRunStatus.RUNNING_NODAL, SoftwareIntegrationRunStatus.RUNNING_PROFILE,
                SoftwareIntegrationRunStatus.COLLECTING, SoftwareIntegrationRunStatus.CANCEL_REQUESTED,
                SoftwareIntegrationRunStatus.FAILED, SoftwareIntegrationRunStatus.WORKER_LOST);
        allow(SoftwareIntegrationRunStatus.RUNNING_PROFILE, SoftwareIntegrationRunStatus.COLLECTING,
                SoftwareIntegrationRunStatus.CANCEL_REQUESTED, SoftwareIntegrationRunStatus.FAILED, SoftwareIntegrationRunStatus.WORKER_LOST);
        allow(SoftwareIntegrationRunStatus.COLLECTING, SoftwareIntegrationRunStatus.SUCCEEDED,
                SoftwareIntegrationRunStatus.PARTIAL_SUCCEEDED, SoftwareIntegrationRunStatus.FAILED,
                SoftwareIntegrationRunStatus.CANCEL_REQUESTED, SoftwareIntegrationRunStatus.WORKER_LOST);
        allow(SoftwareIntegrationRunStatus.CANCEL_REQUESTED, SoftwareIntegrationRunStatus.FAILED,
                SoftwareIntegrationRunStatus.CANCELLED, SoftwareIntegrationRunStatus.TIMED_OUT,
                SoftwareIntegrationRunStatus.WORKER_LOST);
    }

    private SoftwareIntegrationRunStateMachine() {}

    public static boolean allows(SoftwareIntegrationRunStatus from, SoftwareIntegrationRunStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void requireAllowed(SoftwareIntegrationRunStatus from, SoftwareIntegrationRunStatus to) {
        if (!allows(from, to)) throw new IllegalStateException("Disallowed run transition " + from + " -> " + to);
    }

    private static void allow(SoftwareIntegrationRunStatus from, SoftwareIntegrationRunStatus... targets) {
        ALLOWED.put(from, EnumSet.copyOf(java.util.List.of(targets)));
    }
}
