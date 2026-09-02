package com.grdp.studio.softwareintegration.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.grdp.studio.softwareintegration.client.HttpWorkerRunClient.WorkerClientException;
import com.grdp.studio.softwareintegration.client.WorkerRunClient;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationArtifactResponse;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationCreateRunRequest;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationRunDetailResponse;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationRunEventResponse;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationRunSummaryResponse;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationProjectEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStatus;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStore;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelVersionMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationProjectMapper;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationRunService;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationRunExceptionHandler.RunException;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationStorageKeyNormalizer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Service
public class SoftwareIntegrationRunServiceImpl implements SoftwareIntegrationRunService {
    private static final Set<String> RUN_TYPES = Set.of("nodal", "profile", "combined");
    private final SoftwareIntegrationRunStore runStore;
    private final SoftwareIntegrationModelVersionMapper versionMapper;
    private final SoftwareIntegrationModelMapper modelMapper;
    private final SoftwareIntegrationProjectMapper projectMapper;
    private final SoftwareIntegrationStorageKeyNormalizer normalizer;
    private final SoftwareIntegrationProperties properties;
    private final WorkerRunClient workerClient;
    private final ObjectMapper objectMapper;

    public SoftwareIntegrationRunServiceImpl(SoftwareIntegrationRunStore runStore,
                                             SoftwareIntegrationModelVersionMapper versionMapper,
                                             SoftwareIntegrationModelMapper modelMapper,
                                             SoftwareIntegrationProjectMapper projectMapper,
                                             SoftwareIntegrationStorageKeyNormalizer normalizer,
                                             SoftwareIntegrationProperties properties,
                                             WorkerRunClient workerClient,
                                             ObjectMapper objectMapper) {
        this.runStore = runStore;
        this.versionMapper = versionMapper;
        this.modelMapper = modelMapper;
        this.projectMapper = projectMapper;
        this.normalizer = normalizer;
        this.properties = properties;
        this.workerClient = workerClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public SoftwareIntegrationRunSummaryResponse create(long versionId, SoftwareIntegrationCreateRunRequest request) {
        if (!request.isParametersProvided() || request.getParameters() != null) {
            throw new RunException(HttpStatus.BAD_REQUEST, "parameters 必须显式为 null");
        }
        String runType = request.getRunType();
        if (!RUN_TYPES.contains(runType)) throw new RunException(HttpStatus.BAD_REQUEST, "runType 必须为 nodal、profile 或 combined");
        SoftwareIntegrationModelVersionEntity version = requireVersion(versionId);
        if (!"READY".equals(version.getStatus())) throw new RunException(HttpStatus.CONFLICT, "只有 READY 模型版本可以创建运行");
        String study = request.getStudy();
        boolean studyExists = version.getStudiesJson() != null && Arrays.stream(version.getStudiesJson().split("\\n", -1))
                .anyMatch(study::equals);
        if (!studyExists) throw new RunException(HttpStatus.BAD_REQUEST, "Study 不存在或名称不精确匹配");
        SoftwareIntegrationModelEntity model = modelMapper.selectById(version.getModelId());
        if (model == null || model.getDeletedAt() != null) throw new RunException(HttpStatus.NOT_FOUND, "模型不存在");
        SoftwareIntegrationProjectEntity project = projectMapper.selectById(model.getProjectId());
        if (project == null || project.getDeletedAt() != null) throw new RunException(HttpStatus.NOT_FOUND, "软件集成项目不存在");
        normalizeStoredKey(version);
        SoftwareIntegrationRunEntity run = runStore.createQueued(project.getId(), model.getId(), version.getId(),
                study, runType, properties.getDefaultRunTimeoutSeconds());
        return summary(run, model, version);
    }

    @Override
    public SoftwareIntegrationRunDetailResponse get(long runId) {
        SoftwareIntegrationRunEntity run = requireRun(runId);
        SoftwareIntegrationModelVersionEntity version = requireVersion(run.getModelVersionId());
        SoftwareIntegrationModelEntity model = modelMapper.selectById(run.getModelId());
        return new SoftwareIntegrationRunDetailResponse(
                run.getId(), run.getProjectId(), run.getModelId(), run.getModelVersionId(), model.getName(), version.getVersionNo(),
                run.getStatus(), run.getStudyName(), run.getRunType(), nullNode(), run.getCreatedAt(), run.getQueuedAt(),
                run.getClaimedAt(), run.getStartedAt(), run.getDeadlineAt(), run.getFinishedAt(), run.getTimeoutSeconds(),
                elapsed(run), cancellable(run), parse(run.getErrorJson()), parse(run.getCleanupJson()), run.getResultContract(),
                parse(run.getResultJson()),
                runStore.events(runId).stream().map(event -> new SoftwareIntegrationRunEventResponse(
                        event.getId(), event.getEventSequence(), event.getWorkerSequence(), event.getEventType(), event.getStatus(),
                        event.getMessage(), parse(event.getErrorJson()), event.getOccurredAt())).toList(),
                runStore.artifacts(runId).stream().map(artifact -> new SoftwareIntegrationArtifactResponse(
                        artifact.getId(), artifact.getArtifactName(), artifact.getArtifactType(), artifact.getContentType(),
                        artifact.getSizeBytes(), artifact.getSha256(), artifact.getCreatedAt(), artifact.getExpiresAt())).toList());
    }

    @Override
    public List<SoftwareIntegrationRunSummaryResponse> list(long versionId, int limit) {
        SoftwareIntegrationModelVersionEntity version = requireVersion(versionId);
        SoftwareIntegrationModelEntity model = modelMapper.selectById(version.getModelId());
        if (model == null) throw new RunException(HttpStatus.NOT_FOUND, "模型不存在");
        return runStore.listByVersion(versionId, limit).stream().map(run -> summary(run, model, version)).toList();
    }

    @Override
    public CancelResult cancel(long runId) {
        SoftwareIntegrationRunStore.CancelDecision decision = runStore.requestCancel(runId);
        if (decision.outcome() == SoftwareIntegrationRunStore.CancelOutcome.NOT_FOUND) {
            throw new RunException(HttpStatus.NOT_FOUND, "运行不存在");
        }
        if (decision.outcome() == SoftwareIntegrationRunStore.CancelOutcome.CONFLICT) {
            throw new RunException(HttpStatus.CONFLICT, "终态运行不能取消");
        }
        SoftwareIntegrationRunEntity responseRun = decision.run();
        boolean uncertainAcceptance = responseRun.getAcceptanceUncertainAt() != null;
        if (decision.outcome() == SoftwareIntegrationRunStore.CancelOutcome.REQUESTED
                && (responseRun.getWorkerId() != null || uncertainAcceptance)) {
            try {
                workerClient.cancel(runId);
            } catch (WorkerClientException exception) {
                if (!uncertainAcceptance) {
                    JsonNode error = runStore.error("WORKER_LOST", "运行已被 Worker 接受，但取消时无法确认 Worker 状态");
                    SoftwareIntegrationRunEntity lost = runStore.transition(runId, SoftwareIntegrationRunStatus.WORKER_LOST, patch -> {
                        patch.setErrorCode("WORKER_LOST");
                        patch.setErrorJson(error.toString());
                    }, "取消调用失败，运行标记为 WORKER_LOST", error);
                    if (lost != null) responseRun = lost;
                }
            }
        }
        SoftwareIntegrationModelVersionEntity version = requireVersion(responseRun.getModelVersionId());
        SoftwareIntegrationModelEntity model = modelMapper.selectById(responseRun.getModelId());
        HttpStatus status = decision.outcome() == SoftwareIntegrationRunStore.CancelOutcome.REQUESTED
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return new CancelResult(status, summary(responseRun, model, version));
    }

    private SoftwareIntegrationRunSummaryResponse summary(SoftwareIntegrationRunEntity run,
                                                          SoftwareIntegrationModelEntity model,
                                                          SoftwareIntegrationModelVersionEntity version) {
        return new SoftwareIntegrationRunSummaryResponse(run.getId(), run.getProjectId(), run.getModelId(), run.getModelVersionId(),
                model.getName(), version.getVersionNo(), run.getStudyName(), run.getRunType(), nullNode(), run.getStatus(),
                run.getCreatedAt(), run.getQueuedAt(), run.getStartedAt(), run.getFinishedAt(), elapsed(run), cancellable(run));
    }

    private boolean cancellable(SoftwareIntegrationRunEntity run) {
        SoftwareIntegrationRunStatus status = SoftwareIntegrationRunStatus.valueOf(run.getStatus());
        return status == SoftwareIntegrationRunStatus.CREATED || status == SoftwareIntegrationRunStatus.QUEUED
                || (status.isActive() && status != SoftwareIntegrationRunStatus.CANCEL_REQUESTED);
    }

    private Long elapsed(SoftwareIntegrationRunEntity run) {
        if (run.getElapsedMillis() != null) return run.getElapsedMillis();
        LocalDateTime start = run.getStartedAt() != null ? run.getStartedAt()
                : (run.getClaimedAt() != null ? run.getClaimedAt() : run.getCreatedAt());
        return Math.max(0, Duration.between(start, LocalDateTime.now()).toMillis());
    }

    private SoftwareIntegrationRunEntity requireRun(long id) {
        SoftwareIntegrationRunEntity run = runStore.find(id);
        if (run == null) throw new RunException(HttpStatus.NOT_FOUND, "运行不存在");
        return run;
    }

    private SoftwareIntegrationModelVersionEntity requireVersion(long id) {
        SoftwareIntegrationModelVersionEntity version = versionMapper.selectById(id);
        if (version == null) throw new RunException(HttpStatus.NOT_FOUND, "模型版本不存在");
        return version;
    }

    private void normalizeStoredKey(SoftwareIntegrationModelVersionEntity version) {
        final String normalized;
        try { normalized = normalizer.normalizeStoredKey(version.getStorageKey()); }
        catch (IllegalArgumentException exception) {
            throw new RunException(HttpStatus.CONFLICT, "模型存储键不在受控存储根目录内");
        }
        if (!normalized.equals(version.getStorageKey())) {
            int updated = versionMapper.update(null, new LambdaUpdateWrapper<SoftwareIntegrationModelVersionEntity>()
                    .eq(SoftwareIntegrationModelVersionEntity::getId, version.getId())
                    .eq(SoftwareIntegrationModelVersionEntity::getStorageKey, version.getStorageKey())
                    .set(SoftwareIntegrationModelVersionEntity::getStorageKey, normalized));
            if (updated != 1) throw new RunException(HttpStatus.CONFLICT, "模型存储键并发更新，请重试");
            version.setStorageKey(normalized);
        }
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return nullNode();
        try { return objectMapper.readTree(json); }
        catch (RuntimeException exception) { return nullNode(); }
    }

    private JsonNode nullNode() { return objectMapper.readTree("null"); }
}
