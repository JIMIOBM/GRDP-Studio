package com.grdp.studio.softwareintegration.service;

import com.grdp.studio.softwareintegration.client.HttpWorkerRunClient.WorkerClientException;
import com.grdp.studio.softwareintegration.client.WorkerEclipseCapability;
import com.grdp.studio.softwareintegration.client.WorkerRunClient;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationCapabilitiesResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class SoftwareIntegrationCapabilityService {
    private static final Set<String> REASON_CODES = Set.of(
            "WORKER_UNREACHABLE", "ECLIPSE_UNAVAILABLE", "ECLIPSE_VERSION_MISMATCH");
    private final WorkerRunClient workerClient;

    public SoftwareIntegrationCapabilityService(WorkerRunClient workerClient) {
        this.workerClient = workerClient;
    }

    public SoftwareIntegrationCapabilitiesResponse get() {
        try {
            return response(normalize(workerClient.eclipseCapability()));
        } catch (WorkerClientException exception) {
            return response(unavailable("WORKER_UNREACHABLE"));
        }
    }

    public boolean eclipseAvailable() {
        try {
            return normalize(workerClient.eclipseCapability()).isAvailableForEclipse();
        } catch (WorkerClientException exception) {
            return false;
        }
    }

    private static WorkerEclipseCapability normalize(WorkerEclipseCapability capability) {
        if (capability == null) return unavailable("ECLIPSE_UNAVAILABLE");
        if (!"AVAILABLE".equals(capability.status())) {
            String reason = REASON_CODES.contains(capability.reasonCode())
                    ? capability.reasonCode() : "ECLIPSE_UNAVAILABLE";
            return unavailable(reason);
        }
        if (!"2024.1".equals(capability.version())) return unavailable("ECLIPSE_VERSION_MISMATCH");
        if (!capability.isAvailableForEclipse()) return unavailable("ECLIPSE_UNAVAILABLE");
        return new WorkerEclipseCapability("2024.1", "AVAILABLE", null, List.of("eclipse"), 1800);
    }

    private static WorkerEclipseCapability unavailable(String reasonCode) {
        return new WorkerEclipseCapability(null, "UNAVAILABLE", reasonCode, List.of(), 1800);
    }

    private static SoftwareIntegrationCapabilitiesResponse response(WorkerEclipseCapability capability) {
        return new SoftwareIntegrationCapabilitiesResponse(
                new SoftwareIntegrationCapabilitiesResponse.Eclipse100Capability(
                        capability.version(), capability.status(), capability.reasonCode(),
                        capability.runTasks(), capability.maxTimeoutSeconds()));
    }
}
