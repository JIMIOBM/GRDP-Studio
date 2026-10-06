package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.client.WorkerTemplateCreationClient;
import com.grdp.studio.softwareintegration.controller.TemplateCreationController;
import com.grdp.studio.softwareintegration.service.*;
import com.grdp.studio.softwareintegration.support.TemplateCreationJobStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TemplateCreationControllerTests {
    private final TemplateCreationSession sessions = mock(TemplateCreationSession.class);
    private final TemplateCreationDispatcher dispatcher = mock(TemplateCreationDispatcher.class);
    private final TemplateCreationWorkflow workflow = mock(TemplateCreationWorkflow.class);
    private final TemplateCreationJobStore store = mock(TemplateCreationJobStore.class);
    private final WorkerTemplateCreationClient worker = mock(WorkerTemplateCreationClient.class);
    private final SoftwareIntegrationService projects = mock(SoftwareIntegrationService.class);
    private final TemplateCreationController controller = new TemplateCreationController(sessions, dispatcher, workflow, store, worker, projects);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final UUID id = UUID.randomUUID();
    private TemplateCreationJobStore.Job job(long project, String state) {
        var mapper = new ObjectMapper(); var payload = mapper.createObjectNode().put("well", "Well"); payload.putObject("inputs");
        var record = mapper.createObjectNode().put("status", state).put("internalPath", "must-not-leak");
        record.putObject("result").putArray("profile").addObject().put("depth", 1).put("pressure", 2).put("temperature", 3);
        record.putObject("error").put("code", "TEST_CODE").put("message", "must-not-leak");
        return new TemplateCreationJobStore.Job(id, project, "fp", payload, state, record, null);
    }
    @Test void unauthorizedRequestsCannotInspectOrMutateJobsOrCallWorker() {
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED)).when(sessions).requireMember(request);
        assertThatThrownBy(() -> controller.find(1, id, request)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.list(1, request)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.create(1, new TemplateCreationController.CreationRequest(id, "Well", null), request)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.cancel(1, id, request)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.register(1, id, request)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.download(1, id, request)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(dispatcher, workflow, store, worker, projects);
    }
    @Test void sharedListRequiresMembershipAndProjectAndDoesNotDispatch() {
        var summary = new TemplateCreationJobStore.Summary(id, 1, "SharedWell", "UNCERTAIN", null);
        when(store.list(1)).thenReturn(java.util.List.of(summary));
        assertThat(controller.list(1, request).data()).containsExactly(summary);
        verify(sessions).requireMember(request); verify(projects).getProject(1);
        verifyNoInteractions(dispatcher, workflow, worker);
    }
    @Test void wrongProjectIs404BeforeAnyComputationOrRegistration() {
        when(store.find(id)).thenReturn(job(2, "SUCCEEDED"));
        assertThatThrownBy(() -> controller.register(1, id, request)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> controller.download(1, id, request)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.cancel(1, id, request)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(workflow, worker, dispatcher);
    }
    @Test void publicViewNeverExposesPathsOrRawErrorMessagesAndFailedJobNeverExposesProfile() {
        var failed = job(1, "FAILED"); when(store.find(id)).thenReturn(failed); when(dispatcher.find(1, id)).thenReturn(failed);
        var view = controller.find(1, id, request).data();
        assertThat(view.profile()).isNull(); assertThat(view.errorCode()).isEqualTo("TEST_CODE");
        assertThat(new ObjectMapper().writeValueAsString(view)).doesNotContain("must-not-leak");
    }
    @Test void acceptedCreationIsHttp202WithStandard200EnvelopeAndNoSynchronousNativeCall() {
        var payload = new ObjectMapper().createObjectNode();
        when(dispatcher.submit(1, id, "Well", payload)).thenReturn(job(1, "CLAIMED"));
        var response = controller.create(1, new TemplateCreationController.CreationRequest(id, "Well", payload), request);
        assertThat(response.getStatusCode().value()).isEqualTo(202); assertThat(response.getBody().code()).isEqualTo(200);
        verifyNoInteractions(workflow, worker);
    }
    @Test void nativeArtifactIsDownloadedOnlyAfterProjectBindingAndIntegrityClient() {
        var succeeded = job(1, "SUCCEEDED"); when(store.find(id)).thenReturn(succeeded);
        when(dispatcher.find(1, id)).thenReturn(succeeded); when(worker.download(id, succeeded.workerRecord())).thenReturn(new byte[]{1, 2});
        var response = controller.download(1, id, request);
        assertThat(response.getBody()).containsExactly(1, 2);
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("Well.pips");
    }
}
