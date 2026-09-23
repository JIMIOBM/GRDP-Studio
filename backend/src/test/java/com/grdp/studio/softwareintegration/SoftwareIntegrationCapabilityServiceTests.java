package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.client.HttpWorkerRunClient.WorkerClientException;
import com.grdp.studio.softwareintegration.client.WorkerAvailability;
import com.grdp.studio.softwareintegration.client.WorkerCapabilities;
import com.grdp.studio.softwareintegration.client.WorkerEclipseCapability;
import com.grdp.studio.softwareintegration.client.WorkerRunAccepted;
import com.grdp.studio.softwareintegration.client.WorkerRunClient;
import com.grdp.studio.softwareintegration.client.WorkerRunExecuteRequest;
import com.grdp.studio.softwareintegration.client.WorkerRunSnapshot;
import com.grdp.studio.softwareintegration.client.WorkerSimulatorCapability;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationCapabilityService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SoftwareIntegrationCapabilityServiceTests {
    private final StubWorker client = new StubWorker();
    private final SoftwareIntegrationCapabilityService service = new SoftwareIntegrationCapabilityService(client);

    @Test
    void exposesAllNormalizedCapabilitiesWithoutWorkerIdentifiers() {
        var response = service.get();
        assertThat(response.worker().status()).isEqualTo("AVAILABLE");
        assertThat(response.worker().idle()).isTrue();
        assertThat(response.worker().reasonCode()).isNull();
        assertThat(response.pipesimWell().runTasks()).containsExactly("nodal", "profile", "combined", "sensitivity", "gas-lift-performance", "gas-lift-diagnostics", "vfp-tables", "esp-curves", "trajectory");
        assertThat(response.pipesimNetwork().runTasks()).containsExactly("network", "system-analysis", "network-optimizer");
        assertThat(response.eclipse100().runTasks()).containsExactly("eclipse");
    }

    @Test
    void busyWorkerKeepsSimulatorCapabilitiesAvailable() {
        client.capabilities = available(false);
        var response = service.get();
        assertThat(response.worker().status()).isEqualTo("AVAILABLE");
        assertThat(response.worker().idle()).isFalse();
        assertThat(response.worker().reasonCode()).isEqualTo("WORKER_BUSY");
        assertThat(response.pipesimWell().status()).isEqualTo("AVAILABLE");
        assertThat(response.pipesimNetwork().status()).isEqualTo("AVAILABLE");
        assertThat(response.eclipse100().status()).isEqualTo("AVAILABLE");
    }

    @Test
    void unreachableWorkerMakesEveryCapabilityUnavailable() {
        client.error = new WorkerClientException("unreachable");
        var response = service.get();
        assertThat(response.worker().status()).isEqualTo("UNAVAILABLE");
        assertThat(response.worker().reasonCode()).isEqualTo("WORKER_UNREACHABLE");
        assertThat(List.of(response.pipesimWell(), response.pipesimNetwork(), response.eclipse100()))
                .allSatisfy(capability -> {
                    assertThat(capability.status()).isEqualTo("UNAVAILABLE");
                    assertThat(capability.reasonCode()).isEqualTo("WORKER_UNREACHABLE");
                    assertThat(capability.runTasks()).isEmpty();
                });
    }

    @Test
    void normalizesMissingFieldsWrongVersionsUnknownStatusesTasksAndTimeouts() {
        client.capabilities = new WorkerCapabilities(true,
                new WorkerSimulatorCapability(null, "AVAILABLE", null,
                        List.of("nodal", "profile", "combined", "sensitivity", "gas-lift-performance", "gas-lift-diagnostics", "vfp-tables", "esp-curves", "trajectory"), 600),
                new WorkerSimulatorCapability("2022.1", "MYSTERY", "private-detail",
                        List.of("network"), 600),
                new WorkerSimulatorCapability("2024.1", "AVAILABLE", null,
                        List.of("wrong-task"), -1));
        var response = service.get();
        assertThat(response.pipesimWell().reasonCode()).isEqualTo("PIPESIM_VERSION_MISMATCH");
        assertThat(response.pipesimNetwork().reasonCode()).isEqualTo("PIPESIM_UNAVAILABLE");
        assertThat(response.eclipse100().reasonCode()).isEqualTo("ECLIPSE_UNAVAILABLE");
        assertThat(response.eclipse100().maxTimeoutSeconds()).isEqualTo(1800);
    }

    private static WorkerCapabilities available(boolean idle) {
        return new WorkerCapabilities(idle,
                new WorkerSimulatorCapability("2022.1", "AVAILABLE", null,
                        List.of("nodal", "profile", "combined", "sensitivity", "gas-lift-performance", "gas-lift-diagnostics", "vfp-tables", "esp-curves", "trajectory"), 600),
                new WorkerSimulatorCapability("2022.1", "AVAILABLE", null, List.of("network", "system-analysis", "network-optimizer"), 600),
                new WorkerSimulatorCapability("2024.1", "AVAILABLE", null, List.of("eclipse"), 1800));
    }

    private static final class StubWorker implements WorkerRunClient {
        private WorkerCapabilities capabilities = available(true);
        private WorkerClientException error;

        @Override public WorkerCapabilities capabilities() {
            if (error != null) throw error;
            return capabilities;
        }
        @Override public WorkerEclipseCapability eclipseCapability() {
            var value = capabilities.eclipse100();
            return new WorkerEclipseCapability(value.version(), value.status(), value.reasonCode(),
                    value.runTasks(), value.maxTimeoutSeconds());
        }
        @Override public WorkerAvailability availability() { throw new UnsupportedOperationException(); }
        @Override public WorkerRunAccepted execute(WorkerRunExecuteRequest request) { throw new UnsupportedOperationException(); }
        @Override public WorkerRunSnapshot get(long runId, long afterSequence) { throw new UnsupportedOperationException(); }
        @Override public void cancel(long runId) { throw new UnsupportedOperationException(); }
    }
}
