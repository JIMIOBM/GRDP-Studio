package com.grdp.studio.softwareintegration.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.grdp.studio.softwareintegration.artifact.SoftwareIntegrationArtifactPublisher.PublishedArtifacts;
import com.grdp.studio.softwareintegration.client.WorkerRunEvent;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationArtifactEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEventEntity;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationArtifactMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationRunEventMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationRunMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

@Component
public class SoftwareIntegrationRunStore {
    private static final List<String> ACTIVE_STATUSES = List.of(
            "CLAIMED", "PREPARING", "RUNNING_NODAL", "RUNNING_PROFILE", "COLLECTING", "CANCEL_REQUESTED");
    private final SoftwareIntegrationRunMapper runMapper;
    private final SoftwareIntegrationRunEventMapper eventMapper;
    private final SoftwareIntegrationArtifactMapper artifactMapper;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;

    public SoftwareIntegrationRunStore(SoftwareIntegrationRunMapper runMapper,
                                       SoftwareIntegrationRunEventMapper eventMapper,
                                       SoftwareIntegrationArtifactMapper artifactMapper,
                                       ObjectMapper objectMapper,
                                       JdbcTemplate jdbcTemplate,
                                       PlatformTransactionManager transactionManager) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.artifactMapper = artifactMapper;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public SoftwareIntegrationRunEntity createQueued(long projectId, long modelId, long versionId,
                                                      String study, String runType, int timeoutSeconds) {
        return transactions.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            SoftwareIntegrationRunEntity run = new SoftwareIntegrationRunEntity();
            run.setProjectId(projectId);
            run.setModelId(modelId);
            run.setModelVersionId(versionId);
            run.setStudyName(study);
            run.setRunType(runType);
            run.setParametersJson("null");
            run.setStatus(SoftwareIntegrationRunStatus.CREATED.name());
            run.setStatusVersion(0);
            run.setTimeoutSeconds(timeoutSeconds);
            run.setLastWorkerSequence(0L);
            run.setCreatedAt(now);
            run.setCreatedBy("administrator");
            run.setUpdatedBy("administrator");
            run.setUpdatedAt(now);
            runMapper.insert(run);
            appendLocalEvent(run.getId(), "STATE", SoftwareIntegrationRunStatus.CREATED, "运行已创建", null, now);
            SoftwareIntegrationRunEntity queued = transitionLocked(run, SoftwareIntegrationRunStatus.QUEUED, patch -> {
                patch.setQueuedAt(now);
            }, "运行已进入持久队列", null, now);
            return queued;
        });
    }

    public SoftwareIntegrationRunEntity find(long runId) { return runMapper.selectById(runId); }

    public List<SoftwareIntegrationRunEntity> listByVersion(long versionId, int limit) {
        return runMapper.selectList(new LambdaQueryWrapper<SoftwareIntegrationRunEntity>()
                .eq(SoftwareIntegrationRunEntity::getModelVersionId, versionId)
                .orderByDesc(SoftwareIntegrationRunEntity::getId).last("LIMIT " + limit));
    }

    public List<SoftwareIntegrationRunEventEntity> events(long runId) {
        return eventMapper.selectList(new LambdaQueryWrapper<SoftwareIntegrationRunEventEntity>()
                .eq(SoftwareIntegrationRunEventEntity::getRunId, runId)
                .orderByAsc(SoftwareIntegrationRunEventEntity::getEventSequence));
    }

    public List<SoftwareIntegrationArtifactEntity> artifacts(long runId) {
        return artifactMapper.selectList(new LambdaQueryWrapper<SoftwareIntegrationArtifactEntity>()
                .eq(SoftwareIntegrationArtifactEntity::getRunId, runId)
                .orderByAsc(SoftwareIntegrationArtifactEntity::getId));
    }

    public SoftwareIntegrationRunEntity claimOldest(String dispatcherId) {
        try {
            return transactions.execute(status -> {
                SoftwareIntegrationRunEntity candidate = runMapper.selectOldestQueued();
                if (candidate == null) return null;
                if (!lockActiveProject(candidate.getProjectId())) {
                    SoftwareIntegrationRunEntity queued = runMapper.selectForUpdate(candidate.getId());
                    if (queued != null && SoftwareIntegrationRunStatus.QUEUED.name().equals(queued.getStatus())) {
                        transitionLocked(queued, SoftwareIntegrationRunStatus.CANCELLED,
                                patch -> patch.setCancellationReason("PROJECT_DELETED"),
                                "所属项目已删除，排队运行取消", null, LocalDateTime.now());
                    }
                    return null;
                }
                SoftwareIntegrationRunEntity queued = runMapper.selectForUpdate(candidate.getId());
                if (queued == null) return null;
                if (!SoftwareIntegrationRunStatus.QUEUED.name().equals(queued.getStatus())) return null;
                long active = runMapper.selectCount(new LambdaQueryWrapper<SoftwareIntegrationRunEntity>()
                        .in(SoftwareIntegrationRunEntity::getStatus, ACTIVE_STATUSES));
                if (active != 0) return null;
                LocalDateTime now = LocalDateTime.now();
                return transitionLocked(queued, SoftwareIntegrationRunStatus.CLAIMED, patch -> {
                    patch.setClaimedAt(now);
                    patch.setDispatcherId(dispatcherId);
                }, "运行已由调度器认领", null, now);
            });
        } catch (DataIntegrityViolationException exception) {
            return null;
        }
    }

    public SoftwareIntegrationRunEntity acceptWorker(long runId, String workerId, String generationId) {
        return transactions.execute(status -> {
            SoftwareIntegrationRunEntity current = runMapper.selectForUpdate(runId);
            if (current == null || SoftwareIntegrationRunStatus.valueOf(current.getStatus()) != SoftwareIntegrationRunStatus.CLAIMED) {
                throw new IllegalStateException("Run is no longer CLAIMED");
            }
            LocalDateTime now = LocalDateTime.now();
            SoftwareIntegrationRunEntity patch = new SoftwareIntegrationRunEntity();
            patch.setStatus(SoftwareIntegrationRunStatus.CLAIMED.name());
            patch.setStatusVersion(current.getStatusVersion() + 1);
            patch.setWorkerId(workerId);
            patch.setGenerationId(generationId);
            // Worker acquires the global coordinator lease before returning HTTP 202; this timestamp is the practical PREPARING/timeout origin.
            LocalDateTime startedAt = current.getStartedAt() == null ? now : current.getStartedAt();
            patch.setStartedAt(startedAt);
            patch.setDeadlineAt(current.getDeadlineAt() == null
                    ? startedAt.plusSeconds(current.getTimeoutSeconds()) : current.getDeadlineAt());
            patch.setUpdatedBy("administrator");
            patch.setUpdatedAt(now);
            if (casUpdate(current, patch) != 1) throw new IllegalStateException("Worker acceptance CAS failed");
            runMapper.update(null, new LambdaUpdateWrapper<SoftwareIntegrationRunEntity>()
                    .eq(SoftwareIntegrationRunEntity::getId, runId)
                    .eq(SoftwareIntegrationRunEntity::getStatusVersion, patch.getStatusVersion())
                    .set(SoftwareIntegrationRunEntity::getAcceptanceUncertainAt, null)
                    .set(SoftwareIntegrationRunEntity::getAcceptanceRecoveryDeadlineAt, null)
                    .set(SoftwareIntegrationRunEntity::getErrorCategory, null)
                    .set(SoftwareIntegrationRunEntity::getErrorCode, null)
                    .set(SoftwareIntegrationRunEntity::getErrorJson, null));
            appendLocalEvent(runId, "WORKER_ACCEPTED", SoftwareIntegrationRunStatus.CLAIMED,
                    "Worker 已接受运行", null, now);
            return runMapper.selectById(runId);
        });
    }

    public SoftwareIntegrationRunEntity acceptWorkerForCancellation(long runId, String workerId, String generationId,
                                                                     Duration recoveryWindow) {
        if (workerId == null || workerId.isBlank() || generationId == null || generationId.isBlank()) {
            throw new IllegalArgumentException("Worker identity is required");
        }
        return transactions.execute(status -> {
            SoftwareIntegrationRunEntity current = runMapper.selectForUpdate(runId);
            if (current == null
                    || !SoftwareIntegrationRunStatus.CANCEL_REQUESTED.name().equals(current.getStatus())
                    || current.getWorkerId() != null
                    || (current.getGenerationId() != null && !current.getGenerationId().equals(generationId))) {
                throw new IllegalStateException("Run is no longer an uncertain cancellation");
            }
            LocalDateTime now = LocalDateTime.now();
            SoftwareIntegrationRunEntity patch = new SoftwareIntegrationRunEntity();
            patch.setStatus(SoftwareIntegrationRunStatus.CANCEL_REQUESTED.name());
            patch.setStatusVersion(current.getStatusVersion() + 1);
            patch.setWorkerId(workerId);
            patch.setGenerationId(generationId);
            patch.setAcceptanceUncertainAt(current.getAcceptanceUncertainAt() == null
                    ? now : current.getAcceptanceUncertainAt());
            patch.setAcceptanceRecoveryDeadlineAt(current.getAcceptanceRecoveryDeadlineAt() == null
                    ? now.plus(recoveryWindow) : current.getAcceptanceRecoveryDeadlineAt());
            LocalDateTime startedAt = current.getStartedAt() == null ? now : current.getStartedAt();
            patch.setStartedAt(startedAt);
            patch.setDeadlineAt(current.getDeadlineAt() == null
                    ? startedAt.plusSeconds(current.getTimeoutSeconds()) : current.getDeadlineAt());
            patch.setUpdatedBy("administrator");
            patch.setUpdatedAt(now);
            if (casUpdate(current, patch) != 1) throw new IllegalStateException("Cancellation acceptance CAS failed");
            appendLocalEvent(runId, "WORKER_ACCEPTED", SoftwareIntegrationRunStatus.CANCEL_REQUESTED,
                    "取消期间确认 Worker 已接受运行，继续等待清理", null, now);
            return runMapper.selectById(runId);
        });
    }

    public SoftwareIntegrationRunEntity markAcceptanceUncertain(long runId, String generationId, Duration recoveryWindow) {
        return transactions.execute(status -> {
            SoftwareIntegrationRunEntity current = runMapper.selectForUpdate(runId);
            if (current == null || current.getWorkerId() != null) return current;
            SoftwareIntegrationRunStatus currentStatus = SoftwareIntegrationRunStatus.valueOf(current.getStatus());
            if (currentStatus != SoftwareIntegrationRunStatus.CLAIMED
                    && currentStatus != SoftwareIntegrationRunStatus.CANCEL_REQUESTED) return current;
            LocalDateTime now = LocalDateTime.now();
            JsonNode error = error("WORKER_ACCEPTANCE_UNCERTAIN", "Worker execute 响应丢失，正在按 runId 探测");
            SoftwareIntegrationRunEntity patch = new SoftwareIntegrationRunEntity();
            patch.setStatus(currentStatus.name());
            patch.setStatusVersion(current.getStatusVersion() + 1);
            patch.setGenerationId(generationId);
            patch.setAcceptanceUncertainAt(now);
            patch.setAcceptanceRecoveryDeadlineAt(now.plus(recoveryWindow));
            patch.setStartedAt(now);
            patch.setDeadlineAt(now.plusSeconds(current.getTimeoutSeconds()));
            patch.setUpdatedBy("administrator");
            patch.setUpdatedAt(now);
            applyErrorFields(patch, error);
            if (casUpdate(current, patch) != 1) throw new IllegalStateException("Acceptance uncertainty CAS failed");
            appendLocalEvent(runId, "WORKER_ACCEPTANCE_UNCERTAIN", currentStatus,
                    "Worker execute 响应不确定，保留活动槽并探测 runId", error, now);
            return runMapper.selectById(runId);
        });
    }

    public SoftwareIntegrationRunEntity requeueClaimed(long runId, String message, JsonNode eventError) {
        return transactions.execute(status -> {
            SoftwareIntegrationRunEntity current = runMapper.selectForUpdate(runId);
            if (current == null || !SoftwareIntegrationRunStatus.CLAIMED.name().equals(current.getStatus())
                    || current.getWorkerId() != null) return current;
            SoftwareIntegrationRunStateMachine.requireAllowed(SoftwareIntegrationRunStatus.CLAIMED, SoftwareIntegrationRunStatus.QUEUED);
            LocalDateTime now = LocalDateTime.now();
            int updated = runMapper.update(null, new LambdaUpdateWrapper<SoftwareIntegrationRunEntity>()
                    .eq(SoftwareIntegrationRunEntity::getId, runId)
                    .eq(SoftwareIntegrationRunEntity::getStatus, current.getStatus())
                    .eq(SoftwareIntegrationRunEntity::getStatusVersion, current.getStatusVersion())
                    .set(SoftwareIntegrationRunEntity::getStatus, SoftwareIntegrationRunStatus.QUEUED.name())
                    .set(SoftwareIntegrationRunEntity::getStatusVersion, current.getStatusVersion() + 1)
                    .set(SoftwareIntegrationRunEntity::getQueuedAt, now)
                    .set(SoftwareIntegrationRunEntity::getClaimedAt, null)
                    .set(SoftwareIntegrationRunEntity::getDispatcherId, null)
                    .set(SoftwareIntegrationRunEntity::getWorkerId, null)
                    .set(SoftwareIntegrationRunEntity::getGenerationId, null)
                    .set(SoftwareIntegrationRunEntity::getAcceptanceUncertainAt, null)
                    .set(SoftwareIntegrationRunEntity::getAcceptanceRecoveryDeadlineAt, null)
                    .set(SoftwareIntegrationRunEntity::getStartedAt, null)
                    .set(SoftwareIntegrationRunEntity::getDeadlineAt, null)
                    .set(SoftwareIntegrationRunEntity::getErrorCategory, null)
                    .set(SoftwareIntegrationRunEntity::getErrorCode, null)
                    .set(SoftwareIntegrationRunEntity::getErrorJson, null)
                    .set(SoftwareIntegrationRunEntity::getUpdatedBy, "administrator")
                    .set(SoftwareIntegrationRunEntity::getUpdatedAt, now));
            if (updated != 1) throw new IllegalStateException("Run requeue CAS failed");
            appendLocalEvent(runId, "REQUEUED", SoftwareIntegrationRunStatus.QUEUED, message, eventError, now);
            return runMapper.selectById(runId);
        });
    }

    public SoftwareIntegrationRunEntity transition(long runId, SoftwareIntegrationRunStatus target,
                                                    Consumer<SoftwareIntegrationRunEntity> mutation,
                                                    String message, JsonNode error) {
        return transactions.execute(status -> {
            SoftwareIntegrationRunEntity current = runMapper.selectForUpdate(runId);
            if (current == null) return null;
            if (SoftwareIntegrationRunStatus.valueOf(current.getStatus()) == target) return current;
            return transitionLocked(current, target, mutation, message, error, LocalDateTime.now());
        });
    }

    public void persistWorkerEvents(long runId, List<WorkerRunEvent> workerEvents) {
        if (workerEvents == null || workerEvents.isEmpty()) return;
        transactions.executeWithoutResult(status -> {
            SoftwareIntegrationRunEntity run = runMapper.selectForUpdate(runId);
            if (run == null || SoftwareIntegrationRunStatus.valueOf(run.getStatus()).isTerminal()) return;
            long last = run.getLastWorkerSequence() == null ? 0 : run.getLastWorkerSequence();
            for (WorkerRunEvent event : workerEvents.stream().sorted(Comparator.comparingLong(WorkerRunEvent::sequence)).toList()) {
                if (event.sequence() <= 0) throw new IllegalArgumentException("Worker event sequence must be positive");
                if (event.sequence() <= last) continue;
                long duplicate = eventMapper.selectCount(new LambdaQueryWrapper<SoftwareIntegrationRunEventEntity>()
                        .eq(SoftwareIntegrationRunEventEntity::getRunId, runId)
                        .eq(SoftwareIntegrationRunEventEntity::getWorkerSequence, event.sequence()));
                if (duplicate == 0) {
                    appendEvent(runId, event.sequence(), "STATE", event.state(),
                            event.message(), null, LocalDateTime.ofInstant(event.occurredAtUtc(), ZoneOffset.UTC));
                }
                last = Math.max(last, event.sequence());
            }
            runMapper.update(null, new LambdaUpdateWrapper<SoftwareIntegrationRunEntity>()
                    .eq(SoftwareIntegrationRunEntity::getId, runId)
                    .eq(SoftwareIntegrationRunEntity::getStatusVersion, run.getStatusVersion())
                    .set(SoftwareIntegrationRunEntity::getLastWorkerSequence, last)
                    .set(SoftwareIntegrationRunEntity::getUpdatedAt, LocalDateTime.now()));
        });
    }

    public boolean complete(long runId, SoftwareIntegrationRunStatus terminal, String resultContract,
                            JsonNode result, JsonNode error, JsonNode cleanup, PublishedArtifacts published) {
        Boolean completed = transactions.execute(status -> {
            SoftwareIntegrationRunEntity current = runMapper.selectForUpdate(runId);
            if (current == null) return false;
            SoftwareIntegrationRunStatus from = SoftwareIntegrationRunStatus.valueOf(current.getStatus());
            if (from.isTerminal()) return false;
            SoftwareIntegrationRunStateMachine.requireAllowed(from, terminal);
            LocalDateTime now = LocalDateTime.now();
            SoftwareIntegrationRunEntity patch = statePatch(current, terminal, now);
            patch.setResultContract(resultContract);
            patch.setResultJson(json(result));
            applyErrorFields(patch, error);
            patch.setCleanupJson(json(cleanup));
            patch.setArtifactManifestKey(published == null ? null : published.manifestKey());
            int updated = casUpdate(current, patch);
            if (updated != 1) return false;
            if (published != null) {
                for (var item : published.artifacts()) {
                    SoftwareIntegrationArtifactEntity artifact = new SoftwareIntegrationArtifactEntity();
                    artifact.setRunId(runId);
                    artifact.setArtifactName(item.name());
                    artifact.setArtifactType(item.type());
                    artifact.setContentType(item.contentType());
                    artifact.setStorageKey(item.storageKey());
                    artifact.setSizeBytes(item.sizeBytes());
                    artifact.setSha256(item.sha256());
                    artifact.setCreatedAt(item.createdAt());
                    artifact.setExpiresAt(item.createdAt().plusDays(30));
                    artifactMapper.insert(artifact);
                }
            }
            appendLocalEvent(runId, "STATE", terminal,
                    terminal == SoftwareIntegrationRunStatus.PARTIAL_SUCCEEDED ? "运行部分成功" : "运行完成", error, now);
            return true;
        });
        return Boolean.TRUE.equals(completed);
    }

    public CancelDecision requestCancel(long runId) {
        return transactions.execute(status -> {
            SoftwareIntegrationRunEntity current = runMapper.selectForUpdate(runId);
            if (current == null) return new CancelDecision(CancelOutcome.NOT_FOUND, null);
            SoftwareIntegrationRunStatus currentStatus = SoftwareIntegrationRunStatus.valueOf(current.getStatus());
            if (currentStatus == SoftwareIntegrationRunStatus.CANCELLED) return new CancelDecision(CancelOutcome.CANCELLED, current);
            if (currentStatus == SoftwareIntegrationRunStatus.CREATED || currentStatus == SoftwareIntegrationRunStatus.QUEUED) {
                SoftwareIntegrationRunEntity cancelled = transitionLocked(current, SoftwareIntegrationRunStatus.CANCELLED,
                        patch -> patch.setCancellationReason("USER"), "排队运行已取消", null, LocalDateTime.now());
                return new CancelDecision(CancelOutcome.CANCELLED, cancelled);
            }
            if (currentStatus == SoftwareIntegrationRunStatus.CANCEL_REQUESTED) {
                return new CancelDecision(CancelOutcome.REQUESTED, current);
            }
            if (currentStatus.isActive()) {
                SoftwareIntegrationRunEntity requested = transitionLocked(current, SoftwareIntegrationRunStatus.CANCEL_REQUESTED,
                        patch -> patch.setCancellationReason("USER"), "已请求 Worker 取消运行", null, LocalDateTime.now());
                return new CancelDecision(CancelOutcome.REQUESTED, requested);
            }
            return new CancelDecision(CancelOutcome.CONFLICT, current);
        });
    }

    public List<SoftwareIntegrationRunEntity> ownedActive(String dispatcherId) {
        return runMapper.selectList(new LambdaQueryWrapper<SoftwareIntegrationRunEntity>()
                .eq(SoftwareIntegrationRunEntity::getDispatcherId, dispatcherId)
                .in(SoftwareIntegrationRunEntity::getStatus, ACTIVE_STATUSES)
                .orderByAsc(SoftwareIntegrationRunEntity::getId));
    }

    public void recoverOnStartup() { recoverOnStartup(null); }

    public void recoverOnStartup(String dispatcherId) {
        List<SoftwareIntegrationRunEntity> created = runMapper.selectList(new LambdaQueryWrapper<SoftwareIntegrationRunEntity>()
                .eq(SoftwareIntegrationRunEntity::getStatus, SoftwareIntegrationRunStatus.CREATED.name()));
        for (SoftwareIntegrationRunEntity run : created) {
            transition(run.getId(), SoftwareIntegrationRunStatus.QUEUED, patch -> patch.setQueuedAt(LocalDateTime.now()),
                    "启动恢复：CREATED 重新进入队列", null);
        }
        List<SoftwareIntegrationRunEntity> interrupted = runMapper.selectList(new LambdaQueryWrapper<SoftwareIntegrationRunEntity>()
                .in(SoftwareIntegrationRunEntity::getStatus, ACTIVE_STATUSES));
        for (SoftwareIntegrationRunEntity run : interrupted) {
            boolean recoverableUncertain = run.getAcceptanceUncertainAt() != null
                    && ((SoftwareIntegrationRunStatus.CLAIMED.name().equals(run.getStatus()) && run.getWorkerId() == null)
                    || SoftwareIntegrationRunStatus.CANCEL_REQUESTED.name().equals(run.getStatus()));
            if (dispatcherId != null && recoverableUncertain) {
                transactions.executeWithoutResult(status -> runMapper.update(null,
                        new LambdaUpdateWrapper<SoftwareIntegrationRunEntity>()
                                .eq(SoftwareIntegrationRunEntity::getId, run.getId())
                                .eq(SoftwareIntegrationRunEntity::getStatus, run.getStatus())
                                .eq(SoftwareIntegrationRunEntity::getStatusVersion, run.getStatusVersion())
                                .isNotNull(SoftwareIntegrationRunEntity::getAcceptanceUncertainAt)
                                .set(SoftwareIntegrationRunEntity::getDispatcherId, dispatcherId)
                                .set(SoftwareIntegrationRunEntity::getUpdatedAt, LocalDateTime.now())));
                continue;
            }
            JsonNode error = error("WORKER_LOST", "平台重启时运行状态不确定");
            transition(run.getId(), SoftwareIntegrationRunStatus.WORKER_LOST, patch -> {
                patch.setErrorCode("WORKER_LOST");
                patch.setErrorJson(json(error));
            }, "启动恢复：活动运行标记为 WORKER_LOST", error);
        }
    }

    private boolean lockActiveProject(long projectId) {
        return Boolean.TRUE.equals(jdbcTemplate.query(
                "SELECT deleted_at FROM software_integration_project WHERE id = ? FOR UPDATE",
                resultSet -> resultSet.next() && resultSet.getTimestamp(1) == null, projectId));
    }

    public JsonNode error(String code, String message) {
        var node = objectMapper.createObjectNode();
        node.put("category", errorCategory(code));
        node.put("code", code);
        node.put("message", message);
        node.put("retryable", retryable(code));
        return node;
    }

    private SoftwareIntegrationRunEntity transitionLocked(SoftwareIntegrationRunEntity current,
                                                           SoftwareIntegrationRunStatus target,
                                                           Consumer<SoftwareIntegrationRunEntity> mutation,
                                                           String message, JsonNode error, LocalDateTime now) {
        SoftwareIntegrationRunStatus from = SoftwareIntegrationRunStatus.valueOf(current.getStatus());
        SoftwareIntegrationRunStateMachine.requireAllowed(from, target);
        SoftwareIntegrationRunEntity patch = statePatch(current, target, now);
        applyErrorFields(patch, error);
        if (mutation != null) mutation.accept(patch);
        if (casUpdate(current, patch) != 1) throw new IllegalStateException("Run state CAS failed");
        appendLocalEvent(current.getId(), "STATE", target, message, error, now);
        return runMapper.selectById(current.getId());
    }

    private SoftwareIntegrationRunEntity statePatch(SoftwareIntegrationRunEntity current,
                                                     SoftwareIntegrationRunStatus target, LocalDateTime now) {
        SoftwareIntegrationRunEntity patch = new SoftwareIntegrationRunEntity();
        patch.setStatus(target.name());
        patch.setStatusVersion(current.getStatusVersion() + 1);
        patch.setUpdatedBy("administrator");
        patch.setUpdatedAt(now);
        if (target == SoftwareIntegrationRunStatus.QUEUED) patch.setQueuedAt(now);
        if (target == SoftwareIntegrationRunStatus.CLAIMED) patch.setClaimedAt(now);
        if (target.isTerminal()) {
            patch.setFinishedAt(now);
            LocalDateTime start = current.getStartedAt() != null ? current.getStartedAt()
                    : (current.getClaimedAt() != null ? current.getClaimedAt() : current.getCreatedAt());
            patch.setElapsedMillis(Math.max(0, Duration.between(start, now).toMillis()));
        }
        return patch;
    }

    private int casUpdate(SoftwareIntegrationRunEntity current, SoftwareIntegrationRunEntity patch) {
        return runMapper.update(patch, new LambdaUpdateWrapper<SoftwareIntegrationRunEntity>()
                .eq(SoftwareIntegrationRunEntity::getId, current.getId())
                .eq(SoftwareIntegrationRunEntity::getStatus, current.getStatus())
                .eq(SoftwareIntegrationRunEntity::getStatusVersion, current.getStatusVersion()));
    }

    private void appendLocalEvent(long runId, String type, SoftwareIntegrationRunStatus status,
                                  String message, JsonNode error, LocalDateTime occurredAt) {
        appendEvent(runId, null, type, status.name(), message, error, occurredAt);
    }

    private void appendEvent(long runId, Long workerSequence, String type, String status,
                             String message, JsonNode error, LocalDateTime occurredAt) {
        SoftwareIntegrationRunEventEntity last = eventMapper.selectOne(new LambdaQueryWrapper<SoftwareIntegrationRunEventEntity>()
                .eq(SoftwareIntegrationRunEventEntity::getRunId, runId)
                .orderByDesc(SoftwareIntegrationRunEventEntity::getEventSequence).last("LIMIT 1"));
        SoftwareIntegrationRunEventEntity event = new SoftwareIntegrationRunEventEntity();
        event.setRunId(runId);
        event.setEventSequence(last == null ? 1 : last.getEventSequence() + 1);
        event.setWorkerSequence(workerSequence);
        event.setEventType(type == null ? "WORKER" : type);
        event.setStatus(status);
        event.setMessage(message == null ? null : message.substring(0, Math.min(message.length(), 1000)));
        event.setErrorJson(json(error));
        event.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        event.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private String json(JsonNode value) {
        return value == null || value.isNull() ? null : value.toString();
    }

    private void applyErrorFields(SoftwareIntegrationRunEntity patch, JsonNode error) {
        if (error == null || !error.isObject()) return;
        if (error.path("category").isTextual()) patch.setErrorCategory(error.path("category").asText());
        if (error.path("code").isTextual()) patch.setErrorCode(error.path("code").asText());
        patch.setErrorJson(json(error));
    }

    private static String errorCategory(String code) {
        if (code == null) return "EXECUTION";
        if (code.equals("TIMEOUT") || code.contains("TIMEOUT")) return "TIMEOUT";
        if (code.startsWith("ARTIFACT") || code.equals("RESULT_PUBLICATION_FAILED")) return "ARTIFACT";
        if (code.startsWith("RESULT_CONTRACT") || code.startsWith("VALIDATION")) return "VALIDATION";
        if (code.startsWith("MODEL_STORAGE") || code.startsWith("STORAGE") || code.contains("SHA256")) return "STORAGE";
        if (code.startsWith("MODEL_")) return "MODEL";
        if (code.startsWith("WORKER_")) return "COORDINATION";
        if (code.contains("CANCEL")) return "CANCELLATION";
        return "EXECUTION";
    }

    private static boolean retryable(String code) {
        return "WORKER_UNREACHABLE".equals(code) || "WORKER_REJECTED".equals(code)
                || "WORKER_ACCEPTANCE_UNCERTAIN".equals(code);
    }

    public enum CancelOutcome { CANCELLED, REQUESTED, CONFLICT, NOT_FOUND }
    public record CancelDecision(CancelOutcome outcome, SoftwareIntegrationRunEntity run) {}
}
