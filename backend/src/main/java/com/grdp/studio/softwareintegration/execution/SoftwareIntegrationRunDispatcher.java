package com.grdp.studio.softwareintegration.execution;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.grdp.studio.softwareintegration.artifact.SoftwareIntegrationArtifactPublisher;
import com.grdp.studio.softwareintegration.artifact.SoftwareIntegrationArtifactPublisher.PublishedArtifacts;
import com.grdp.studio.softwareintegration.client.HttpWorkerRunClient.WorkerClientException;
import com.grdp.studio.softwareintegration.client.WorkerAvailability;
import com.grdp.studio.softwareintegration.client.WorkerRunAccepted;
import com.grdp.studio.softwareintegration.client.WorkerRunClient;
import com.grdp.studio.softwareintegration.client.WorkerRunExecuteRequest;
import com.grdp.studio.softwareintegration.client.WorkerRunSnapshot;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelVersionMapper;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationStorageKeyNormalizer;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "grdp.software-integration", name = "dispatcher-enabled", havingValue = "true", matchIfMissing = true)
public class SoftwareIntegrationRunDispatcher {
    private final String dispatcherId = UUID.randomUUID().toString();
    private final SoftwareIntegrationRunStore runStore;
    private final SoftwareIntegrationModelVersionMapper versionMapper;
    private final SoftwareIntegrationStorageKeyNormalizer normalizer;
    private final WorkerRunClient workerClient;
    private final PipesimWellResultValidator resultValidator;
    private final SoftwareIntegrationArtifactPublisher artifactPublisher;
    private final SoftwareIntegrationProperties properties;
    private final AtomicBoolean dispatching = new AtomicBoolean();
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile boolean recovered;
    private volatile LocalDateTime nextDispatchAt = LocalDateTime.MIN;

    public SoftwareIntegrationRunDispatcher(SoftwareIntegrationRunStore runStore,
                                            SoftwareIntegrationModelVersionMapper versionMapper,
                                            SoftwareIntegrationStorageKeyNormalizer normalizer,
                                            WorkerRunClient workerClient,
                                            PipesimWellResultValidator resultValidator,
                                            SoftwareIntegrationArtifactPublisher artifactPublisher,
                                            SoftwareIntegrationProperties properties) {
        this.runStore = runStore;
        this.versionMapper = versionMapper;
        this.normalizer = normalizer;
        this.workerClient = workerClient;
        this.resultValidator = resultValidator;
        this.artifactPublisher = artifactPublisher;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        runStore.recoverOnStartup(dispatcherId);
        recovered = true;
    }

    @Scheduled(fixedDelayString = "${grdp.software-integration.dispatch-delay:500ms}")
    public void dispatch() {
        if (!recovered) return;
        if (LocalDateTime.now().isBefore(nextDispatchAt)) return;
        if (!dispatching.compareAndSet(false, true)) return;
        try {
            WorkerAvailability availability;
            try { availability = workerClient.availability(); }
            catch (WorkerClientException exception) { return; }
            if (!availability.idle() || availability.generationId() == null) return;
            SoftwareIntegrationRunEntity claimed = runStore.claimOldest(dispatcherId);
            if (claimed == null) return;
            executeClaimed(claimed, availability);
        } finally {
            dispatching.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${grdp.software-integration.poll-delay:500ms}")
    public void poll() {
        if (!recovered) return;
        if (!polling.compareAndSet(false, true)) return;
        try {
            for (SoftwareIntegrationRunEntity run : runStore.ownedActive(dispatcherId)) {
                pollOne(run);
            }
        } finally {
            polling.set(false);
        }
    }

    private void executeClaimed(SoftwareIntegrationRunEntity claimed, WorkerAvailability availability) {
        SoftwareIntegrationRunEntity current = runStore.find(claimed.getId());
        if (current == null) return;
        if (SoftwareIntegrationRunStatus.CANCEL_REQUESTED.name().equals(current.getStatus())) {
            runStore.transition(current.getId(), SoftwareIntegrationRunStatus.CANCELLED, null,
                    "Worker 接受前取消完成", null);
            return;
        }
        if (!SoftwareIntegrationRunStatus.CLAIMED.name().equals(current.getStatus())) return;
        SoftwareIntegrationModelVersionEntity version = versionMapper.selectById(current.getModelVersionId());
        if (version == null) {
            fail(current.getId(), "MODEL_VERSION_MISSING", "认领后模型版本不存在");
            return;
        }
        final String storageKey;
        try {
            storageKey = normalizeAndPersist(version);
        } catch (RuntimeException exception) {
            fail(current.getId(), "MODEL_STORAGE_INVALID", "模型存储键不在受控根目录内");
            return;
        }
        WorkerRunAccepted accepted;
        try {
            accepted = workerClient.execute(new WorkerRunExecuteRequest(current.getId(), storageKey, version.getSha256(),
                    current.getStudyName(), current.getRunType(), null, current.getTimeoutSeconds()));
        } catch (WorkerClientException exception) {
            SoftwareIntegrationRunEntity afterExecute = runStore.find(current.getId());
            if (afterExecute != null
                    && SoftwareIntegrationRunStatus.CANCEL_REQUESTED.name().equals(afterExecute.getStatus())) {
                if (exception.statusCode() == null) {
                    runStore.markAcceptanceUncertain(current.getId(), availability.generationId(),
                            properties.getAcceptanceRecoveryWindow());
                } else {
                    runStore.transition(current.getId(), SoftwareIntegrationRunStatus.CANCELLED, null,
                            "Worker 明确未接受运行，取消安全完成", null);
                }
                return;
            }
            if (isWorkerBusy(exception)) {
                runStore.requeueClaimed(current.getId(), "Worker 短暂忙，运行安全退回队列", exception.error());
                nextDispatchAt = LocalDateTime.now().plus(properties.getWorkerBusyBackoff());
            } else if (exception.statusCode() == null) {
                runStore.markAcceptanceUncertain(current.getId(), availability.generationId(),
                        properties.getAcceptanceRecoveryWindow());
            } else {
                JsonNode error = exception.error() == null
                        ? runStore.error("WORKER_REJECTED", "Worker 在接受运行前拒绝了请求") : exception.error();
                failWithWorkerError(current.getId(), error, "WORKER_REJECTED", "Worker 在接受运行前拒绝了请求");
            }
            return;
        }
        if (!availability.generationId().equals(accepted.generationId())) {
            try { workerClient.cancel(current.getId()); }
            catch (WorkerClientException ignored) { }
            workerLost(current.getId(), "Worker generation 在接受运行时发生变化");
            return;
        }
        try {
            runStore.acceptWorker(current.getId(), accepted.workerId(), accepted.generationId());
        } catch (RuntimeException exception) {
            SoftwareIntegrationRunEntity afterExecute = runStore.find(current.getId());
            if (afterExecute != null
                    && SoftwareIntegrationRunStatus.CANCEL_REQUESTED.name().equals(afterExecute.getStatus())
                    && afterExecute.getWorkerId() == null) {
                try {
                    runStore.acceptWorkerForCancellation(current.getId(), accepted.workerId(), accepted.generationId(),
                            properties.getAcceptanceRecoveryWindow());
                    try { workerClient.cancel(current.getId()); }
                    catch (WorkerClientException ignored) { }
                } catch (RuntimeException recoveryException) {
                    workerLost(current.getId(), "Worker 已接受运行，但取消状态无法持久化 Worker identity");
                }
            } else {
                workerLost(current.getId(), "Worker 已接受运行，但 Spring 无法持久化接受状态");
            }
        }
    }

    private void pollOne(SoftwareIntegrationRunEntity run) {
        SoftwareIntegrationRunStatus status = SoftwareIntegrationRunStatus.valueOf(run.getStatus());
        if (status == SoftwareIntegrationRunStatus.CANCEL_REQUESTED && run.getAcceptanceUncertainAt() != null) {
            if (run.getWorkerId() == null) probeUncertainCancellation(run);
            else pollAcceptedUncertainCancellation(run);
            return;
        }
        if (status == SoftwareIntegrationRunStatus.CLAIMED && run.getWorkerId() == null) {
            if (run.getAcceptanceUncertainAt() != null) probeUncertainAcceptance(run);
            return;
        }
        if (status != SoftwareIntegrationRunStatus.CANCEL_REQUESTED && run.getDeadlineAt() != null
                && !LocalDateTime.now().isBefore(run.getDeadlineAt())) {
            JsonNode timeout = runStore.error("TIMEOUT", "运行超过配置超时，已请求 Worker 取消");
            SoftwareIntegrationRunEntity requested = runStore.transition(run.getId(), SoftwareIntegrationRunStatus.CANCEL_REQUESTED, patch -> {
                patch.setCancellationReason("TIMEOUT");
                patch.setErrorCode("TIMEOUT");
                patch.setErrorJson(timeout.toString());
            }, "运行超时，正在请求 Worker 清理", timeout);
            try { workerClient.cancel(requested.getId()); }
            catch (WorkerClientException exception) { workerLost(requested.getId(), "超时取消后无法确认 Worker 清理状态"); }
            return;
        }
        if (status == SoftwareIntegrationRunStatus.CANCEL_REQUESTED && run.getWorkerId() == null) {
            // execute may still be in flight; its response (or uncertainty marker) owns reconciliation.
            return;
        }
        WorkerRunSnapshot snapshot;
        try {
            snapshot = workerClient.get(run.getId(), run.getLastWorkerSequence() == null ? 0 : run.getLastWorkerSequence());
        } catch (WorkerClientException exception) {
            workerLost(run.getId(), "Worker 接受运行后不可达或运行未知");
            return;
        }
        processSnapshot(run, snapshot);
    }

    private void probeUncertainCancellation(SoftwareIntegrationRunEntity run) {
        WorkerRunSnapshot snapshot;
        try {
            snapshot = workerClient.get(run.getId(), run.getLastWorkerSequence() == null ? 0 : run.getLastWorkerSequence());
        } catch (WorkerClientException exception) {
            if (Integer.valueOf(404).equals(exception.statusCode())) {
                resolveExplicitMissingCancellation(run);
            } else if (acceptanceRecoveryExpired(run)) {
                workerLost(run.getId(), "取消期间无法在有限恢复窗口内确认 Worker 是否已接受运行");
            }
            return;
        }
        if (snapshot.runId() != run.getId() || run.getGenerationId() == null
                || !run.getGenerationId().equals(snapshot.generationId())) {
            workerLost(run.getId(), "取消期间探测到不匹配的 Worker generation");
            return;
        }
        try {
            SoftwareIntegrationRunEntity accepted = runStore.acceptWorkerForCancellation(
                    run.getId(), snapshot.workerId(), snapshot.generationId(), properties.getAcceptanceRecoveryWindow());
            try { workerClient.cancel(run.getId()); }
            catch (WorkerClientException ignored) { }
            processSnapshot(accepted, snapshot);
        } catch (RuntimeException exception) {
            workerLost(run.getId(), "取消期间确认 Worker 已接受运行，但持久化恢复失败");
        }
    }

    private void pollAcceptedUncertainCancellation(SoftwareIntegrationRunEntity run) {
        try { workerClient.cancel(run.getId()); }
        catch (WorkerClientException ignored) { }
        WorkerRunSnapshot snapshot;
        try {
            snapshot = workerClient.get(run.getId(), run.getLastWorkerSequence() == null ? 0 : run.getLastWorkerSequence());
        } catch (WorkerClientException exception) {
            if (Integer.valueOf(404).equals(exception.statusCode())) {
                workerLost(run.getId(), "取消期间已确认接受的 Worker 运行随后不可见");
            } else if (acceptanceRecoveryExpired(run)) {
                workerLost(run.getId(), "取消期间网络持续未知且恢复窗口已到期");
            }
            return;
        }
        processSnapshot(run, snapshot);
    }

    private void probeUncertainAcceptance(SoftwareIntegrationRunEntity run) {
        WorkerRunSnapshot snapshot;
        try {
            snapshot = workerClient.get(run.getId(), 0);
        } catch (WorkerClientException exception) {
            if (Integer.valueOf(404).equals(exception.statusCode())) {
                resolveExplicitMissingRun(run, exception.error());
            } else if (run.getAcceptanceRecoveryDeadlineAt() != null
                    && !LocalDateTime.now().isBefore(run.getAcceptanceRecoveryDeadlineAt())) {
                workerLost(run.getId(), "Worker execute 接受状态在有限恢复窗口内无法确认");
            }
            return;
        }
        if (snapshot.runId() != run.getId() || run.getGenerationId() == null
                || !run.getGenerationId().equals(snapshot.generationId())) {
            workerLost(run.getId(), "Worker execute 响应丢失后探测到不匹配的 generation");
            return;
        }
        try {
            SoftwareIntegrationRunEntity accepted = runStore.acceptWorker(
                    run.getId(), snapshot.workerId(), snapshot.generationId());
            processSnapshot(accepted, snapshot);
        } catch (RuntimeException exception) {
            workerLost(run.getId(), "探测到 Worker 已接受，但持久化恢复失败");
        }
    }

    private void resolveExplicitMissingRun(SoftwareIntegrationRunEntity run, JsonNode workerError) {
        WorkerAvailability availability;
        try {
            availability = workerClient.availability();
        } catch (WorkerClientException exception) {
            if (run.getAcceptanceRecoveryDeadlineAt() != null
                    && !LocalDateTime.now().isBefore(run.getAcceptanceRecoveryDeadlineAt())) {
                workerLost(run.getId(), "Worker 404 后无法确认原 generation");
            }
            return;
        }
        if (!run.getGenerationId().equals(availability.generationId())) {
            workerLost(run.getId(), "Worker generation 已变化，原 execute 是否执行无法安全确认");
            return;
        }
        runStore.requeueClaimed(run.getId(), "同 generation 明确返回 404，运行安全退回队列", workerError);
        nextDispatchAt = LocalDateTime.now().plus(properties.getWorkerBusyBackoff());
    }

    private void resolveExplicitMissingCancellation(SoftwareIntegrationRunEntity run) {
        WorkerAvailability availability;
        try {
            availability = workerClient.availability();
        } catch (WorkerClientException exception) {
            if (acceptanceRecoveryExpired(run)) {
                workerLost(run.getId(), "取消期间 Worker 404 后无法确认原 generation");
            }
            return;
        }
        if (run.getGenerationId() == null || !run.getGenerationId().equals(availability.generationId())) {
            workerLost(run.getId(), "取消期间 Worker generation 已变化，原运行无法安全确认");
            return;
        }
        JsonNode error = runStore.error("RUN_CANCELLED", "同 generation 明确确认 Worker 未接受运行");
        runStore.transition(run.getId(), SoftwareIntegrationRunStatus.CANCELLED, null,
                "同 generation 明确返回 404，取消安全完成", error);
    }

    private static boolean acceptanceRecoveryExpired(SoftwareIntegrationRunEntity run) {
        return run.getAcceptanceRecoveryDeadlineAt() != null
                && !LocalDateTime.now().isBefore(run.getAcceptanceRecoveryDeadlineAt());
    }

    private void processSnapshot(SoftwareIntegrationRunEntity run, WorkerRunSnapshot snapshot) {
        try {
            if (run == null || snapshot.runId() != run.getId() || run.getWorkerId() == null
                    || !run.getWorkerId().equals(snapshot.workerId())
                    || !run.getGenerationId().equals(snapshot.generationId())) {
                if (run != null) workerLost(run.getId(), "Worker 返回的运行或 generation 标识不匹配");
                return;
            }
            runStore.persistWorkerEvents(run.getId(), snapshot.events());
            for (var event : snapshot.events()) applyWorkerPhase(run.getId(), event.state());
            applySnapshot(runStore.find(run.getId()), snapshot);
        } catch (RuntimeException exception) {
            workerLost(run.getId(), "Worker 返回的运行状态或增量事件无效");
        }
    }

    private static boolean isWorkerBusy(WorkerClientException exception) {
        if (!Integer.valueOf(409).equals(exception.statusCode())) return false;
        String code = exception.errorCode();
        return "WORKER_BUSY".equals(code) || "PIPESIM_GLOBAL_BUSY".equals(code)
                || ("COORDINATION".equals(exception.errorCategory()) && code != null && code.contains("BUSY"));
    }

    private void applyWorkerPhase(long runId, String workerState) {
        SoftwareIntegrationRunEntity run = runStore.find(runId);
        if (run == null || SoftwareIntegrationRunStatus.valueOf(run.getStatus()).isTerminal()) return;
        String state = workerState == null ? "" : workerState.toUpperCase(Locale.ROOT);
        switch (state) {
            case "CLAIMED", "CANCEL_REQUESTED" -> { }
            case "PREPARING" -> phase(run, SoftwareIntegrationRunStatus.PREPARING, "Worker 正在准备模型");
            case "RUNNING_NODAL" -> phase(run, SoftwareIntegrationRunStatus.RUNNING_NODAL, "Worker 正在执行节点分析");
            case "RUNNING_PROFILE" -> phase(run, SoftwareIntegrationRunStatus.RUNNING_PROFILE, "Worker 正在执行 PT 剖面");
            case "COLLECTING" -> phase(run, SoftwareIntegrationRunStatus.COLLECTING, "Worker 正在收集结果");
            default -> { }
        }
    }

    private void applySnapshot(SoftwareIntegrationRunEntity run, WorkerRunSnapshot snapshot) {
        if (run == null || SoftwareIntegrationRunStatus.valueOf(run.getStatus()).isTerminal()) return;
        String remote = snapshot.state() == null ? "" : snapshot.state().toUpperCase(Locale.ROOT);
        boolean uncertainCancellation = SoftwareIntegrationRunStatus.CANCEL_REQUESTED.name().equals(run.getStatus())
                && run.getAcceptanceUncertainAt() != null;
        if (uncertainCancellation && (remote.equals("SUCCEEDED") || remote.equals("PARTIAL_SUCCEEDED")
                || remote.equals("FAILED"))) {
            if (snapshot.cleanupConfirmsProcessExit()) {
                finishCancelWonRace(run, snapshot);
            } else if (acceptanceRecoveryExpired(run)) {
                workerLost(run.getId(), "取消期间 Worker 未返回可确认的取消终态");
            }
            return;
        }
        if (SoftwareIntegrationRunStatus.CANCEL_REQUESTED.name().equals(run.getStatus())
                && (remote.equals("SUCCEEDED") || remote.equals("PARTIAL_SUCCEEDED"))) {
            finishCancelWonRace(run, snapshot);
            return;
        }
        if (SoftwareIntegrationRunStatus.CANCEL_REQUESTED.name().equals(run.getStatus()) && remote.equals("FAILED")) {
            finishCancelRequestedWorkerFailure(run, snapshot);
            return;
        }
        switch (remote) {
            case "CLAIMED" -> { }
            case "CANCEL_REQUESTED" -> {
                if (SoftwareIntegrationRunStatus.CANCEL_REQUESTED.name().equals(run.getStatus())
                        && snapshot.cleanupConfirmsProcessExit()) {
                    finishCancelWonRace(run, snapshot);
                }
            }
            case "PREPARING" -> phase(run, SoftwareIntegrationRunStatus.PREPARING, "Worker 正在准备模型");
            case "RUNNING_NODAL" -> phase(run, SoftwareIntegrationRunStatus.RUNNING_NODAL, "Worker 正在执行节点分析");
            case "RUNNING_PROFILE" -> phase(run, SoftwareIntegrationRunStatus.RUNNING_PROFILE, "Worker 正在执行 PT 剖面");
            case "COLLECTING" -> phase(run, SoftwareIntegrationRunStatus.COLLECTING, "Worker 正在收集结果");
            case "SUCCEEDED", "PARTIAL_SUCCEEDED" -> publishResult(runStore.find(run.getId()), snapshot);
            case "FAILED" -> finishFailure(runStore.find(run.getId()), snapshot);
            case "CANCELLED" -> finishCancellation(runStore.find(run.getId()), snapshot);
            case "TIMED_OUT" -> finishTimeout(runStore.find(run.getId()), snapshot);
            case "WORKER_LOST" -> workerLost(run.getId(), "Worker 报告运行上下文已丢失");
            default -> workerLost(run.getId(), "Worker 返回未知运行状态");
        }
    }

    private void finishCancelWonRace(SoftwareIntegrationRunEntity run, WorkerRunSnapshot snapshot) {
        if (snapshot.cleanupConfirmsProcessExit()) {
            boolean timedOut = "TIMEOUT".equals(run.getCancellationReason());
            SoftwareIntegrationRunStatus terminal = timedOut
                    ? SoftwareIntegrationRunStatus.TIMED_OUT : SoftwareIntegrationRunStatus.CANCELLED;
            JsonNode error = timedOut
                    ? runStore.error("TIMEOUT", "取消 CAS 先于 Worker 终态，超时结果已丢弃且清理已确认")
                    : runStore.error("RUN_CANCELLED", "取消 CAS 先于 Worker 终态，结果已丢弃且清理已确认");
            runStore.complete(run.getId(), terminal, null, null, error, snapshot.cleanup(), null);
            return;
        }
        try {
            workerClient.cancel(run.getId());
        } catch (WorkerClientException exception) {
            workerLost(run.getId(), "取消 CAS 已生效，但无法重发取消或确认 Worker 清理");
            return;
        }
        workerLost(run.getId(), "取消 CAS 已生效，但 Worker 成功快照未证明进程清理");
    }

    private void finishCancelRequestedWorkerFailure(SoftwareIntegrationRunEntity run, WorkerRunSnapshot snapshot) {
        if (snapshot.cleanupConfirmsProcessExit()) {
            finishCancelWonRace(run, snapshot);
            return;
        }
        JsonNode workerError = snapshot.error();
        boolean cleanupFailure = workerError != null && workerError.isObject()
                && ("CLEANUP".equals(workerError.path("category").asText())
                || "PROCESS_TREE_EXIT_UNCONFIRMED".equals(workerError.path("code").asText()));
        if (cleanupFailure) {
            finishFailure(run, snapshot);
        } else {
            workerLost(run.getId(), "取消 CAS 已生效，但 Worker 失败快照未证明进程清理");
        }
    }

    private void phase(SoftwareIntegrationRunEntity run, SoftwareIntegrationRunStatus target, String message) {
        SoftwareIntegrationRunStatus current = SoftwareIntegrationRunStatus.valueOf(run.getStatus());
        if (current == target || current == SoftwareIntegrationRunStatus.CANCEL_REQUESTED) return;
        runStore.transition(run.getId(), target, null, message, null);
    }

    private void publishResult(SoftwareIntegrationRunEntity run, WorkerRunSnapshot snapshot) {
        PublishedArtifacts published = null;
        try {
            PipesimWellResultValidator.ValidatedResult validated = resultValidator.validate(run.getRunType(), snapshot.result());
            SoftwareIntegrationRunStatus current = SoftwareIntegrationRunStatus.valueOf(run.getStatus());
            if (current != SoftwareIntegrationRunStatus.CANCEL_REQUESTED && current != SoftwareIntegrationRunStatus.COLLECTING) {
                run = runStore.transition(run.getId(), SoftwareIntegrationRunStatus.COLLECTING, null,
                        "Worker 已完成计算，正在发布结果", null);
            }
            published = artifactPublisher.publish(run.getId(), snapshot.artifacts());
            if (published.manifestKey() == null) {
                artifactPublisher.discard(published);
                published = null;
                throw new SoftwareIntegrationArtifactPublisher.ArtifactPublicationException(
                        "Successful run is missing manifest.json");
            }
            JsonNode error = snapshot.error();
            if (validated.terminalStatus() == SoftwareIntegrationRunStatus.PARTIAL_SUCCEEDED && error == null) {
                error = runStore.error("PROFILE_PARTIAL", "节点分析有效，但 PT 剖面未产生有效结果");
            }
            boolean completed = runStore.complete(run.getId(), validated.terminalStatus(), validated.contract(),
                    validated.result(), error, snapshot.cleanup(), published);
            if (!completed) artifactPublisher.discard(published);
        } catch (PipesimWellResultValidator.ResultValidationException exception) {
            if (published != null) artifactPublisher.discard(published);
            if (finishCancellationThatWonDuringPublication(run.getId(), snapshot)) return;
            fail(run.getId(), "RESULT_CONTRACT_INVALID", "Worker 结果不符合 pipesim-well-result/1");
        } catch (SoftwareIntegrationArtifactPublisher.ArtifactPublicationException exception) {
            if (published != null) artifactPublisher.discard(published);
            if (finishCancellationThatWonDuringPublication(run.getId(), snapshot)) return;
            fail(run.getId(), "ARTIFACT_INVALID", "Worker Artifact 未通过路径、大小或 SHA-256 校验");
        } catch (RuntimeException exception) {
            if (published != null) artifactPublisher.discard(published);
            if (finishCancellationThatWonDuringPublication(run.getId(), snapshot)) return;
            fail(run.getId(), "RESULT_PUBLICATION_FAILED", "结果或 Artifact 元数据发布失败");
        }
    }

    private boolean finishCancellationThatWonDuringPublication(long runId, WorkerRunSnapshot snapshot) {
        SoftwareIntegrationRunEntity current = runStore.find(runId);
        if (current == null || !SoftwareIntegrationRunStatus.CANCEL_REQUESTED.name().equals(current.getStatus())) return false;
        finishCancelWonRace(current, snapshot);
        return true;
    }

    private void finishCancellation(SoftwareIntegrationRunEntity run, WorkerRunSnapshot snapshot) {
        if (run == null) return;
        if (!snapshot.cleanupConfirmsProcessExit()) {
            workerLost(run.getId(), "Worker 未确认取消后的进程退出");
            return;
        }
        if ("TIMEOUT".equals(run.getCancellationReason())) finishTimeout(run, snapshot);
        else {
            JsonNode error = snapshot.error() == null
                    ? runStore.error("RUN_CANCELLED", "运行已取消且进程清理已确认") : snapshot.error();
            runStore.complete(run.getId(), SoftwareIntegrationRunStatus.CANCELLED, null,
                    null, error, snapshot.cleanup(), null);
        }
    }

    private void finishTimeout(SoftwareIntegrationRunEntity run, WorkerRunSnapshot snapshot) {
        if (run == null) return;
        if (!snapshot.cleanupConfirmsProcessExit()) {
            workerLost(run.getId(), "超时后 Worker 未确认进程退出");
            return;
        }
        if (SoftwareIntegrationRunStatus.valueOf(run.getStatus()) != SoftwareIntegrationRunStatus.CANCEL_REQUESTED) {
            JsonNode requestedError = runStore.error("TIMEOUT", "Worker 报告超时，等待清理确认");
            run = runStore.transition(run.getId(), SoftwareIntegrationRunStatus.CANCEL_REQUESTED, patch -> {
                patch.setCancellationReason("TIMEOUT");
                patch.setErrorCode("TIMEOUT");
                patch.setErrorJson(requestedError.toString());
            }, "Worker 报告运行超时", requestedError);
        }
        JsonNode error = snapshot.error() == null ? runStore.error("TIMEOUT", "运行超时并已完成进程清理") : snapshot.error();
        runStore.complete(run.getId(), SoftwareIntegrationRunStatus.TIMED_OUT, null,
                null, error, snapshot.cleanup(), null);
    }

    private void finishFailure(SoftwareIntegrationRunEntity run, WorkerRunSnapshot snapshot) {
        if (run == null) return;
        JsonNode error = snapshot.error() == null
                ? runStore.error("WORKER_FAILED", "Worker 运行失败") : snapshot.error();
        publishTerminal(run, SoftwareIntegrationRunStatus.FAILED, snapshot, error,
                "失败运行的 Artifact 发布失败");
    }

    private void publishTerminal(SoftwareIntegrationRunEntity run, SoftwareIntegrationRunStatus terminal,
                                 WorkerRunSnapshot snapshot, JsonNode error, String artifactFailureMessage) {
        PublishedArtifacts published = null;
        try {
            published = artifactPublisher.publish(run.getId(), snapshot.artifacts());
            boolean completed = runStore.complete(run.getId(), terminal, null,
                    null, error, snapshot.cleanup(), published);
            if (!completed) artifactPublisher.discard(published);
        } catch (SoftwareIntegrationArtifactPublisher.ArtifactPublicationException exception) {
            if (published != null) artifactPublisher.discard(published);
            terminalPublicationFailed(run.getId(), snapshot.cleanup(), "ARTIFACT_INVALID", artifactFailureMessage);
        } catch (RuntimeException exception) {
            if (published != null) artifactPublisher.discard(published);
            terminalPublicationFailed(run.getId(), snapshot.cleanup(), "RESULT_PUBLICATION_FAILED",
                    "终态运行元数据发布失败");
        }
    }

    private void terminalPublicationFailed(long runId, JsonNode cleanup, String code, String message) {
        JsonNode error = runStore.error(code, message);
        SoftwareIntegrationRunEntity current = runStore.find(runId);
        if (current != null && !SoftwareIntegrationRunStatus.valueOf(current.getStatus()).isTerminal()) {
            runStore.transition(runId, SoftwareIntegrationRunStatus.FAILED, patch -> {
                patch.setErrorCode(code);
                patch.setErrorJson(error.toString());
                patch.setCleanupJson(cleanup == null ? null : cleanup.toString());
            }, message, error);
        }
    }

    private void fail(long runId, String code, String message) {
        failWithWorkerError(runId, runStore.error(code, message), code, message);
    }

    private void failWithWorkerError(long runId, JsonNode workerError, String fallbackCode, String fallbackMessage) {
        SoftwareIntegrationRunEntity current = runStore.find(runId);
        if (current == null || SoftwareIntegrationRunStatus.valueOf(current.getStatus()).isTerminal()) return;
        JsonNode error = workerError == null ? runStore.error(fallbackCode, fallbackMessage) : workerError;
        runStore.transition(runId, SoftwareIntegrationRunStatus.FAILED, null, fallbackMessage, error);
    }

    private void workerLost(long runId, String message) {
        SoftwareIntegrationRunEntity current = runStore.find(runId);
        if (current == null || SoftwareIntegrationRunStatus.valueOf(current.getStatus()).isTerminal()) return;
        JsonNode error = runStore.error("WORKER_LOST", message);
        runStore.transition(runId, SoftwareIntegrationRunStatus.WORKER_LOST, patch -> {
            patch.setErrorCode("WORKER_LOST");
            patch.setErrorJson(error.toString());
        }, message, error);
    }

    private String normalizeAndPersist(SoftwareIntegrationModelVersionEntity version) {
        String normalized = normalizer.normalizeStoredKey(version.getStorageKey());
        if (!normalized.equals(version.getStorageKey())) {
            int updated = versionMapper.update(null, new LambdaUpdateWrapper<SoftwareIntegrationModelVersionEntity>()
                    .eq(SoftwareIntegrationModelVersionEntity::getId, version.getId())
                    .eq(SoftwareIntegrationModelVersionEntity::getStorageKey, version.getStorageKey())
                    .set(SoftwareIntegrationModelVersionEntity::getStorageKey, normalized));
            if (updated != 1) throw new IllegalStateException("Model storage key CAS failed");
        }
        return normalized;
    }
}
