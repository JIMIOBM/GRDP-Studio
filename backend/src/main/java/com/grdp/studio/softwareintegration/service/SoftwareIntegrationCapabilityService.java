package com.grdp.studio.softwareintegration.service;

import com.grdp.studio.softwareintegration.client.HttpWorkerRunClient.WorkerClientException;
import com.grdp.studio.softwareintegration.client.WorkerEclipseCapability;
import com.grdp.studio.softwareintegration.client.WorkerCapabilities;
import com.grdp.studio.softwareintegration.client.WorkerRunClient;
import com.grdp.studio.softwareintegration.client.WorkerSimulatorCapability;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationCapabilitiesResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class SoftwareIntegrationCapabilityService {
    private static final Set<String> ECLIPSE_REASON_CODES = Set.of(
            "WORKER_UNREACHABLE", "ECLIPSE_UNAVAILABLE", "ECLIPSE_VERSION_MISMATCH");
    private final WorkerRunClient workerClient;

    public SoftwareIntegrationCapabilityService(WorkerRunClient workerClient) {
        this.workerClient = workerClient;
    }

    public SoftwareIntegrationCapabilitiesResponse get() {
        try {
            WorkerCapabilities capabilities = workerClient.capabilities();
            boolean idle = Boolean.TRUE.equals(capabilities.idle());
            return response("AVAILABLE", idle, idle ? null : "WORKER_BUSY",
                    normalize(capabilities.pipesimWell(), "2022.1", List.of("nodal", "profile", "combined", "sensitivity", "gas-lift-performance", "gas-lift-diagnostics", "vfp-tables", "esp-curves", "trajectory"),
                            "PIPESIM_UNAVAILABLE", "PIPESIM_VERSION_MISMATCH"),
                    normalize(capabilities.pipesimNetwork(), "2022.1", List.of("network", "system-analysis", "network-optimizer"),
                            "PIPESIM_UNAVAILABLE", "PIPESIM_VERSION_MISMATCH"),
                    normalize(capabilities.eclipse100(), "2024.1", List.of("eclipse"),
                            "ECLIPSE_UNAVAILABLE", "ECLIPSE_VERSION_MISMATCH"));
        } catch (WorkerClientException exception) {
            return response("UNAVAILABLE", false, "WORKER_UNREACHABLE",
                    unavailable("WORKER_UNREACHABLE", 600),
                    unavailable("WORKER_UNREACHABLE", 600),
                    unavailable("WORKER_UNREACHABLE", 1800));
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
            String reason = ECLIPSE_REASON_CODES.contains(capability.reasonCode())
                    ? capability.reasonCode() : "ECLIPSE_UNAVAILABLE";
            return unavailable(reason);
        }
        if (!"2024.1".equals(capability.version())) return unavailable("ECLIPSE_VERSION_MISMATCH");
        if (!capability.isAvailableForEclipse()) return unavailable("ECLIPSE_UNAVAILABLE");
        return new WorkerEclipseCapability("2024.1", "AVAILABLE", null, List.of("eclipse"), 1800);
    }

    private static WorkerSimulatorCapability normalize(WorkerSimulatorCapability capability, String version,
                                                       List<String> tasks, String unavailableReason,
                                                       String versionReason) {
        int fallbackTimeout = "2024.1".equals(version) ? 1800 : 600;
        if (capability == null) return unavailable(unavailableReason, fallbackTimeout);
        if (!"AVAILABLE".equals(capability.status())) return unavailable(unavailableReason, fallbackTimeout);
        if (!version.equals(capability.version())) return unavailable(versionReason, fallbackTimeout);
        if (capability.runTasks() == null || !capability.runTasks().containsAll(tasks)
                || capability.maxTimeoutSeconds() == null || capability.maxTimeoutSeconds() <= 0) {
            return unavailable(unavailableReason, fallbackTimeout);
        }
        return new WorkerSimulatorCapability(version, "AVAILABLE", null, tasks, capability.maxTimeoutSeconds());
    }

    private static WorkerSimulatorCapability unavailable(String reasonCode, int timeout) {
        return new WorkerSimulatorCapability(null, "UNAVAILABLE", reasonCode, List.of(), timeout);
    }

    private static WorkerEclipseCapability unavailable(String reasonCode) {
        return new WorkerEclipseCapability(null, "UNAVAILABLE", reasonCode, List.of(), 1800);
    }

    private static SoftwareIntegrationCapabilitiesResponse response(String workerStatus, boolean idle, String workerReason,
                                                                    WorkerSimulatorCapability well,
                                                                    WorkerSimulatorCapability network,
                                                                    WorkerSimulatorCapability eclipse) {
        return new SoftwareIntegrationCapabilitiesResponse(
                new SoftwareIntegrationCapabilitiesResponse.WorkerCapability(workerStatus, idle, workerReason),
                response(well), response(network), response(eclipse));
    }

    private static SoftwareIntegrationCapabilitiesResponse.SimulatorCapability response(WorkerSimulatorCapability capability) {
        return new SoftwareIntegrationCapabilitiesResponse.SimulatorCapability(capability.version(), capability.status(),
                capability.reasonCode(), capability.runTasks(), capability.maxTimeoutSeconds());
    }
}
