package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.client.WorkerTemplateCreationClient;
import com.grdp.studio.softwareintegration.dto.*;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationService;
import com.grdp.studio.softwareintegration.service.TemplateCreationWorkflow;
import com.grdp.studio.softwareintegration.support.TemplateCreationJobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TemplateCreationWorkflowTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID id = UUID.randomUUID();
    private final TemplateCreationJobStore store = mock(TemplateCreationJobStore.class);
    private final WorkerTemplateCreationClient worker = mock(WorkerTemplateCreationClient.class);
    private final SoftwareIntegrationService projects = mock(SoftwareIntegrationService.class);
    private final TemplateCreationWorkflow workflow = new TemplateCreationWorkflow(store, worker, projects);
    private TemplateCreationJobStore.Job job;

    @BeforeEach void setup() {
        var request = mapper.createObjectNode().put("well", "Well"); request.putObject("inputs");
        var record = mapper.createObjectNode().put("requestId", id.toString()).put("status", "SUCCEEDED");
        record.putObject("model").put("sha256", "a".repeat(64));
        job = new TemplateCreationJobStore.Job(id, 1, "fingerprint", request, "SUCCEEDED", record, null);
        when(store.find(id)).thenReturn(job);
    }

    @Test void lostResponseMarksUncertainAndNeverAutomaticallyRedispatches() {
        var inputs = mapper.createObjectNode();
        when(store.claim(1, id, "Well", inputs)).thenReturn(new TemplateCreationJobStore.Claim(job, true), new TemplateCreationJobStore.Claim(job, false));
        when(worker.create(id, "Well", inputs)).thenThrow(new WorkerTemplateCreationClient.CreationTransportException("lost", 0, true));
        assertThatThrownBy(() -> workflow.create(1, id, "Well", inputs)).isInstanceOf(RuntimeException.class);
        verify(store).markUncertain(id);
        assertThat(workflow.create(1, id, "Well", inputs)).isEqualTo(job);
        verify(worker, times(1)).create(id, "Well", inputs);
    }

    @Test void recoveryOnlyGetsRecordAndMissingWorkerRecordKeepsClaim() {
        var uncertain = new TemplateCreationJobStore.Job(id, 1, "fp", job.request(), "UNCERTAIN", null, null);
        when(store.find(id)).thenReturn(uncertain); when(worker.find(id)).thenReturn(null);
        assertThat(workflow.recover(1, id)).isEqualTo(uncertain);
        verify(worker).find(id); verify(store, never()).recover(any(), any());
        verify(worker, never()).create(any(), any(), any());
    }

    @Test void wrongProjectNeverContactsWorker() {
        assertThatThrownBy(() -> workflow.recover(2, id)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(worker);
    }

    @Test void registeredJobDoesNotDownloadOrUploadAgain() {
        when(store.find(id)).thenReturn(new TemplateCreationJobStore.Job(id, 1, "fp", job.request(), "SUCCEEDED", job.workerRecord(), 10L));
        assertThat(workflow.register(1, id)).isEqualTo(10);
        verifyNoInteractions(worker); verify(projects, never()).uploadModel(anyLong(), any(MultipartFile.class));
    }

    @Test void registrationUsesExistingUploadWithStableNameAndSourceHash() throws Exception {
        when(worker.download(id, job.workerRecord())).thenReturn(new byte[]{1, 2, 3});
        String filename = "Well_" + id.toString().replace("-", "") + ".pips";
        var version = mock(SoftwareIntegrationModelVersionResponse.class);
        when(version.id()).thenReturn(10L); when(version.originalName()).thenReturn(filename); when(version.sha256()).thenReturn("a".repeat(64));
        var model = mock(SoftwareIntegrationModelResponse.class); when(model.versions()).thenReturn(List.of(version));
        var detail = mock(SoftwareIntegrationProjectDetailResponse.class); when(detail.models()).thenReturn(List.of(model));
        when(projects.uploadModel(eq(1L), any(MultipartFile.class))).thenAnswer(invocation -> {
            MultipartFile file = invocation.getArgument(1);
            assertThat(file.getOriginalFilename()).isEqualTo(filename); assertThat(file.getInputStream().readAllBytes()).containsExactly(1, 2, 3);
            return detail;
        });
        when(store.register(eq(id), eq(1L), any())).thenAnswer(invocation -> {
            Function<TemplateCreationJobStore.Job, Long> callback = invocation.getArgument(2); return callback.apply(job);
        });
        assertThat(workflow.register(1, id)).isEqualTo(10);
        verify(projects).uploadModel(eq(1L), any(MultipartFile.class));
    }

    @Test void badArtifactNeverReachesUploadOrRegistration() {
        when(worker.download(id, job.workerRecord())).thenThrow(new IllegalStateException("hash mismatch"));
        assertThatThrownBy(() -> workflow.register(1, id)).isInstanceOf(IllegalStateException.class);
        verify(store, never()).register(any(), anyLong(), any());
        verify(projects, never()).uploadModel(anyLong(), any(MultipartFile.class));
    }

    @Test void completedOrWrongProjectCannotSendCancellation() {
        assertThat(workflow.cancel(1, id)).isEqualTo(job);
        assertThatThrownBy(() -> workflow.cancel(2, id)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(worker);
    }

    @Test void cancellationAcknowledgementIsNotPersistedAsTerminalSuccess() {
        var preparing = new TemplateCreationJobStore.Job(id, 1, "fp", job.request(), "PREPARING", null, null);
        when(store.find(id)).thenReturn(preparing);
        var acknowledgement = mapper.createObjectNode().put("requestId", id.toString()).put("status", "CANCEL_REQUESTED");
        when(worker.cancel(id)).thenReturn(new WorkerTemplateCreationClient.Reply(202, acknowledgement));
        when(worker.find(id)).thenReturn(null);
        assertThat(workflow.cancel(1, id)).isEqualTo(preparing);
        verify(store, never()).recover(any(), any()); verify(worker).find(id);
    }
}
