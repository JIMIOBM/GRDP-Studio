package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.service.TemplateCreationDispatcher;
import com.grdp.studio.softwareintegration.client.WorkerTemplateCreationClient;
import com.grdp.studio.softwareintegration.service.TemplateCreationWorkflow;
import com.grdp.studio.softwareintegration.support.TemplateCreationJobStore;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class TemplateCreationDispatcherTests {
    private final TemplateCreationJobStore store = mock(TemplateCreationJobStore.class);
    private final TemplateCreationWorkflow workflow = mock(TemplateCreationWorkflow.class);
    private final TemplateCreationDispatcher dispatcher = new TemplateCreationDispatcher(store, workflow);
    private final UUID id = UUID.randomUUID();
    private JsonNode inputs;
    private TemplateCreationJobStore.Job job;
    private final CountDownLatch release = new CountDownLatch(1);
    @BeforeEach void setup() {
        var mapper = new ObjectMapper();
        inputs = mapper.readTree("""
                {"schemaVersion":"pipesim-template-profile-inputs/1","unitsSystem":"PIPESIM_FIELD","study":"Study 1",
                "oilApi":35,"gasSpecificGravity":0.65,"waterSpecificGravity":1.05,"gorScfStb":200,"waterCutPercent":20,
                "reservoirPressurePsia":4000,"reservoirTemperatureDegF":150,"outletPressurePsia":250,"liquidRateStbDay":1000}
                """);
        var request = mapper.createObjectNode().put("well", "Well"); request.set("inputs", inputs);
        job = new TemplateCreationJobStore.Job(id, 1, "fp", request, "CLAIMED", null, null);
    }
    @AfterEach void cleanup() { release.countDown(); dispatcher.close(); }
    @Test void boundedDispatchReturnsDurableClaimBeforeCalculationCompletesAndNeverReplaysExistingId() throws Exception {
        var claim = new TemplateCreationJobStore.Claim(job, true);
        var started = new CountDownLatch(1);
        when(store.claim(1, id, "Well", inputs, 50)).thenReturn(claim);
        when(workflow.executeClaim(claim)).thenAnswer(invocation -> {
            started.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return job;
        });
        assertThat(dispatcher.submit(1, id, "Well", inputs)).isEqualTo(job);
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> dispatcher.submit(1, UUID.randomUUID(), "Well", inputs)).hasMessageContaining("已有建井任务");
        when(store.find(id)).thenReturn(job);
        when(store.claim(1, id, "Well", inputs)).thenReturn(new TemplateCreationJobStore.Claim(job, false));
        assertThat(dispatcher.submit(1, id, "Well", inputs)).isEqualTo(job);
        when(store.requestCancellation(1, id)).thenReturn(true);
        when(store.cancellationRequested(id)).thenReturn(true);
        assertThat(dispatcher.cancel(1, id)).isEqualTo(job);
        assertThat(dispatcher.cancellationRequested(id)).isTrue();
        verify(workflow, times(1)).executeClaim(claim);
        verify(store, never()).claim(eq(1L), any(), any(), any(), eq(Integer.MAX_VALUE));
    }
    @Test void invalidScalarNeverClaimsOrDispatches() {
        ((tools.jackson.databind.node.ObjectNode) inputs).put("waterCutPercent", 101);
        assertThatThrownBy(() -> dispatcher.submit(1, id, "Well", inputs)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store, workflow);
    }
    @Test void restartRecoveryDoesNotRedispatchEvenWhenWorkerRecordIsMissing() {
        when(store.find(id)).thenReturn(job);
        when(store.claim(1, id, "Well", inputs)).thenReturn(new TemplateCreationJobStore.Claim(job, false));
        assertThat(dispatcher.submit(1, id, "Well", inputs)).isEqualTo(job);
        verifyNoInteractions(workflow);
    }

    @Test void freshDispatcherRecoveryFollowsDurableCancelIntentWithoutRecreatingModel() {
        when(store.find(id)).thenReturn(job);
        when(store.cancellationRequested(id)).thenReturn(true);
        when(workflow.cancel(1, id)).thenReturn(job);
        assertThat(dispatcher.find(1, id)).isEqualTo(job);
        assertThat(dispatcher.cancellationRequested(id)).isTrue();
        verify(workflow).cancel(1, id);
        verify(workflow, never()).executeClaim(any());
        verify(workflow, never()).recover(anyLong(), any());
        assertThatThrownBy(() -> dispatcher.find(2, id)).isInstanceOf(IllegalStateException.class);
        verify(workflow, never()).cancel(eq(2L), any());
    }

    @Test void cancelIntentPersistsBeforeWorkerFailureAndTerminalJobsNeverSendCancel() {
        when(store.find(id)).thenReturn(job);
        when(store.requestCancellation(1, id)).thenReturn(true);
        when(workflow.cancel(1, id)).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> dispatcher.cancel(1, id)).hasMessage("offline");
        var order = inOrder(store, workflow);
        order.verify(store).requestCancellation(1, id);
        order.verify(workflow).cancel(1, id);
        when(store.requestCancellation(1, id)).thenReturn(false);
        assertThat(dispatcher.cancel(1, id)).isEqualTo(job);
        verify(workflow, times(1)).cancel(1, id);
    }

    @Test void missingCancelEndpointStillQueriesRealWorkerStateWithoutDroppingIntent() {
        when(store.find(id)).thenReturn(job);
        when(store.cancellationRequested(id)).thenReturn(true);
        when(workflow.cancel(1, id)).thenThrow(new WorkerTemplateCreationClient.CreationTransportException("missing", 404, false));
        when(workflow.recover(1, id)).thenReturn(job);
        assertThat(dispatcher.find(1, id)).isEqualTo(job);
        verify(workflow).recover(1, id);
        verify(workflow, never()).executeClaim(any());
        verify(store, never()).markRejected(any());
        assertThat(dispatcher.cancellationRequested(id)).isTrue();
    }
}
