package com.grdp.studio.softwareintegration.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.softwareintegration.client.WorkerTemplateCreationClient;
import com.grdp.studio.softwareintegration.service.*;
import com.grdp.studio.softwareintegration.support.TemplateCreationJobStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.UUID;
import java.util.List;
import java.util.function.Supplier;

/** Only this new feature's routes use shared-team authorization; legacy modules are unchanged. */
@RestController
@RequestMapping("/software-integration/projects/{projectId}/template-creations")
public class TemplateCreationController {
    private final TemplateCreationSession sessions;
    private final TemplateCreationDispatcher dispatcher;
    private final TemplateCreationWorkflow workflow;
    private final TemplateCreationJobStore store;
    private final WorkerTemplateCreationClient worker;
    private final SoftwareIntegrationService projects;

    public TemplateCreationController(TemplateCreationSession sessions, TemplateCreationDispatcher dispatcher,
            TemplateCreationWorkflow workflow, TemplateCreationJobStore store,
            WorkerTemplateCreationClient worker, SoftwareIntegrationService projects) {
        this.sessions = sessions; this.dispatcher = dispatcher; this.workflow = workflow;
        this.store = store; this.worker = worker; this.projects = projects;
    }

    public record CreationRequest(UUID requestId, String well, JsonNode inputs) { }
    public record CreationView(UUID requestId, long projectId, String well, String state,
            Long versionId, boolean cancellationRequested, JsonNode profile, String resultUnits, String errorCode) { }

    public record Capabilities(String template, String unitsSystem, int executionBudgetSeconds, int maxProjectClaims) { }

    @GetMapping
    public ApiResponse<List<TemplateCreationJobStore.Summary>> list(@PathVariable long projectId,
            HttpServletRequest request) {
        authorize(projectId, request);
        return ApiResponse.success(store.list(projectId));
    }

    @GetMapping("/capabilities")
    public ApiResponse<Capabilities> capabilities(@PathVariable long projectId, HttpServletRequest request) {
        authorize(projectId, request);
        return ApiResponse.success(new Capabilities("Simple vertical", "PIPESIM_FIELD",
                checked(worker::executionBudgetSeconds), 50));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<CreationView>> create(@PathVariable long projectId,
            @RequestBody CreationRequest input, HttpServletRequest request) {
        authorize(projectId, request);
        var job = checked(() -> dispatcher.submit(projectId, input.requestId(), input.well(), input.inputs()));
        return ResponseEntity.accepted().body(ApiResponse.success(view(job)));
    }

    @GetMapping("/{id}")
    public ApiResponse<CreationView> find(@PathVariable long projectId, @PathVariable UUID id,
            HttpServletRequest request) {
        authorizeJob(projectId, id, request);
        return ApiResponse.success(view(checked(() -> dispatcher.find(projectId, id))));
    }

    @PostMapping(path = "/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<CreationView> cancel(@PathVariable long projectId, @PathVariable UUID id,
            HttpServletRequest request) {
        authorizeJob(projectId, id, request);
        return ApiResponse.success(view(checked(() -> dispatcher.cancel(projectId, id))));
    }

    @PostMapping(path = "/{id}/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Long> register(@PathVariable long projectId, @PathVariable UUID id,
            HttpServletRequest request) {
        authorizeJob(projectId, id, request);
        return ApiResponse.success(checked(() -> workflow.register(projectId, id)));
    }

    @GetMapping("/{id}/model")
    public ResponseEntity<byte[]> download(@PathVariable long projectId, @PathVariable UUID id,
            HttpServletRequest request) {
        authorizeJob(projectId, id, request);
        var job = checked(() -> dispatcher.find(projectId, id));
        if (!"SUCCEEDED".equals(job.state())) throw new ResponseStatusException(HttpStatus.CONFLICT, "只有原生计算成功的工程可下载");
        byte[] bytes = checked(() -> worker.download(id, job.workerRecord()));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(job.request().path("well").asText() + ".pips").build().toString()).body(bytes);
    }

    private void authorize(long projectId, HttpServletRequest request) {
        sessions.requireMember(request);
        if (projectId < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "项目 ID 无效");
        projects.getProject(projectId);
    }

    private void authorizeJob(long projectId, UUID id, HttpServletRequest request) {
        authorize(projectId, request);
        var job = checked(() -> store.find(id));
        if (job == null || job.projectId() != projectId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前项目没有该建井记录");
        }
    }

    private CreationView view(TemplateCreationJobStore.Job job) {
        JsonNode result = job.workerRecord() == null ? null : job.workerRecord().path("result");
        boolean success = "SUCCEEDED".equals(job.state());
        String code = job.workerRecord() == null ? null : job.workerRecord().path("error").path("code").asText("");
        if ("REJECTED".equals(job.state())) code = "CREATION_NOT_DISPATCHED";
        return new CreationView(job.requestId(), job.projectId(), job.request().path("well").asText(),
                job.state(), job.versionId(), dispatcher.cancellationRequested(job.requestId()),
                success && result != null ? result.path("profile") : null,
                success && result != null ? result.path("resultUnits").asText() : null, code);
    }

    private <T> T checked(Supplier<T> operation) {
        try { return operation.get(); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage()); }
        catch (IllegalStateException exception) { throw new ResponseStatusException(HttpStatus.CONFLICT, "建井状态或项目配额不允许该操作"); }
        catch (WorkerTemplateCreationClient.CreationTransportException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法读取计算服务状态；请保留请求 ID 后查询，不要重复建井");
        }
    }
}
