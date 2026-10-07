package com.grdp.studio.softwareintegration.service;

import com.grdp.studio.softwareintegration.support.TemplateCreationInputs;
import com.grdp.studio.softwareintegration.client.WorkerTemplateCreationClient;
import com.grdp.studio.softwareintegration.support.TemplateCreationJobStore;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One bounded in-process dispatch slot. Persistent claims recover by GET, never by replaying POST. */
@Service
public class TemplateCreationDispatcher {
    private final TemplateCreationJobStore store;
    private final TemplateCreationWorkflow workflow;
    private final ScheduledExecutorService execution = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService cancellation = Executors.newSingleThreadScheduledExecutor();
    private Active active;
    private boolean closed;
    private static final class Active {
        final long projectId;
        final UUID id;
        volatile boolean cancelRequested;
        Active(long projectId, UUID id) { this.projectId = projectId; this.id = id; }
    }

    public TemplateCreationDispatcher(TemplateCreationJobStore store, TemplateCreationWorkflow workflow) {
        this.store = store; this.workflow = workflow;
        cancellation.scheduleWithFixedDelay(this::sendPendingCancellation, 250, 500, TimeUnit.MILLISECONDS);
    }

    public synchronized TemplateCreationJobStore.Job submit(long projectId, UUID id, String well, JsonNode inputs) {
        TemplateCreationInputs.validate(well, inputs);
        if (id == null || id.equals(new UUID(0, 0))) throw new IllegalArgumentException("创建请求 ID 无效");
        if (store.find(id) != null) return store.claim(projectId, id, well, inputs).job();
        if (closed || active != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "已有建井任务，请等待完成后再创建");
        var claim = store.claim(projectId, id, well, inputs, 50);
        if (!claim.newlyClaimed()) return claim.job();
        var task = new Active(projectId, id);
        active = task;
        execution.execute(() -> {
            try { workflow.executeClaim(claim); }
            catch (RuntimeException exception) { store.markUncertain(id); }
            finally { synchronized (this) { if (active == task) active = null; } }
        });
        return claim.job();
    }

    public TemplateCreationJobStore.Job find(long projectId, UUID id) {
        requireJob(projectId, id);
        // After restart, explicit recovery follows durable cancellation intent, never replays creation.
        if (store.cancellationRequested(id)) {
            try { return workflow.cancel(projectId, id); }
            catch (WorkerTemplateCreationClient.CreationTransportException exception) {
                // A missing/rejected cancel signal does not prove termination; still read real state.
                return workflow.recover(projectId, id);
            }
        }
        return workflow.recover(projectId, id);
    }

    public TemplateCreationJobStore.Job cancel(long projectId, UUID id) {
        if (!store.requestCancellation(projectId, id)) return requireJob(projectId, id);
        synchronized (this) {
            if (active != null && active.id.equals(id) && active.projectId == projectId) {
                active.cancelRequested = true;
                return requireJob(projectId, id);
            }
        }
        return workflow.cancel(projectId, id);
    }

    public synchronized boolean cancellationRequested(UUID id) {
        return store.cancellationRequested(id);
    }

    private TemplateCreationJobStore.Job requireJob(long projectId, UUID id) {
        var job = store.find(id);
        if (job == null || job.projectId() != projectId) throw new IllegalStateException("Creation not found in project");
        return job;
    }

    private void sendPendingCancellation() {
        Active task;
        synchronized (this) { task = active; }
        if (task == null || !task.cancelRequested) return;
        try { workflow.cancel(task.projectId, task.id); }
        catch (RuntimeException exception) {
            // Worker may not have persisted its claim yet. Retry only cancellation, never creation.
        }
    }

    @PreDestroy public synchronized void close() {
        closed = true;
        cancellation.shutdownNow(); execution.shutdownNow();
    }
}
