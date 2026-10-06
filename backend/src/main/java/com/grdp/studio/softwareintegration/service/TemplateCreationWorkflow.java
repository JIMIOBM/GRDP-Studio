package com.grdp.studio.softwareintegration.service;

import com.grdp.studio.softwareintegration.client.WorkerTemplateCreationClient;
import com.grdp.studio.softwareintegration.support.TemplateCreationJobStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.UUID;

/** Internal workflow. Public access requires project authorization before invoking any method. */
@Service
public class TemplateCreationWorkflow {
    private final TemplateCreationJobStore store;
    private final WorkerTemplateCreationClient worker;
    private final SoftwareIntegrationService projects;

    public TemplateCreationWorkflow(TemplateCreationJobStore store, WorkerTemplateCreationClient worker,
                                    SoftwareIntegrationService projects) {
        this.store = store; this.worker = worker; this.projects = projects;
    }

    public TemplateCreationJobStore.Job create(long projectId, UUID id, String well, JsonNode inputs) {
        var claim = store.claim(projectId, id, well, inputs);
        if (!claim.newlyClaimed()) return recover(projectId, id);
        try {
            var reply = worker.create(id, well, inputs);
            store.recover(id, reply.record());
        } catch (RuntimeException exception) {
            // Persist uncertainty, including response parsing/DB failures after native creation.
            // Never automatically POST again, even if a later GET returns 404.
            store.markUncertain(id);
            throw exception;
        }
        return store.find(id);
    }

    public TemplateCreationJobStore.Job recover(long projectId, UUID id) {
        var job = requireProjectJob(projectId, id);
        if (!java.util.Set.of("CLAIMED", "UNCERTAIN", "PREPARING").contains(job.state())) return job;
        JsonNode record = worker.find(id);
        if (record != null) store.recover(id, record);
        return store.find(id);
    }

    public TemplateCreationJobStore.Job cancel(long projectId, UUID id) {
        var job = requireProjectJob(projectId, id);
        if (!java.util.Set.of("CLAIMED", "UNCERTAIN", "PREPARING").contains(job.state())) return job;
        var reply = worker.cancel(id);
        if (reply.statusCode() == 200) store.recover(id, reply.record());
        // An acknowledgement is not persisted as CANCELLED. GET observes the actual terminal state.
        return recover(projectId, id);
    }

    /** Download verification precedes the DB transaction; the upload and binding commit together. */
    public long register(long projectId, UUID id) {
        var job = recover(projectId, id);
        if (job.versionId() != null) return job.versionId();
        byte[] bytes = worker.download(id, job.workerRecord());
        String name = job.request().path("well").asText() + "_" + id.toString().replace("-", "") + ".pips";
        return store.register(id, projectId, locked -> {
            var project = projects.uploadModel(projectId, new CreatedModelFile(name, bytes));
            return project.models().stream().flatMap(model -> model.versions().stream())
                    .filter(version -> name.equals(version.originalName())
                            && locked.workerRecord().path("model").path("sha256").asText().equalsIgnoreCase(version.sha256()))
                    .map(version -> version.id()).max(Long::compareTo)
                    .orElseThrow(() -> new IllegalStateException("Uploaded creation version missing"));
        });
    }

    private TemplateCreationJobStore.Job requireProjectJob(long projectId, UUID id) {
        projects.getProject(projectId);
        var job = store.find(id);
        if (job == null || job.projectId() != projectId) throw new IllegalStateException("Creation not found in project");
        return job;
    }

    private record CreatedModelFile(String name, byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) throws IOException { Files.write(destination.toPath(), bytes); }
    }
}
