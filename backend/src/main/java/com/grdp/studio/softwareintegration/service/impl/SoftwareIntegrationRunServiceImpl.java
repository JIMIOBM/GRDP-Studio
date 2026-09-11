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
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationCapabilityService;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationDiagnosticSanitizer;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationRunExceptionHandler.RunException;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationStorageKeyNormalizer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Service
public class SoftwareIntegrationRunServiceImpl implements SoftwareIntegrationRunService {
    private static final int ECLIPSE_TIMEOUT_SECONDS = 1800;
    private static final Set<String> WELL_RUN_TYPES = Set.of("nodal", "profile", "combined");
    private static final Set<String> RUN_TYPES = Set.of("nodal", "profile", "combined", "network", "eclipse");
    private final SoftwareIntegrationRunStore runStore;
    private final SoftwareIntegrationModelVersionMapper versionMapper;
    private final SoftwareIntegrationModelMapper modelMapper;
    private final SoftwareIntegrationProjectMapper projectMapper;
    private final SoftwareIntegrationStorageKeyNormalizer normalizer;
    private final SoftwareIntegrationProperties properties;
    private final WorkerRunClient workerClient;
    private final SoftwareIntegrationCapabilityService capabilityService;
    private final ObjectMapper objectMapper;

    public SoftwareIntegrationRunServiceImpl(SoftwareIntegrationRunStore runStore,
                                             SoftwareIntegrationModelVersionMapper versionMapper,
                                             SoftwareIntegrationModelMapper modelMapper,
                                             SoftwareIntegrationProjectMapper projectMapper,
                                             SoftwareIntegrationStorageKeyNormalizer normalizer,
                                             SoftwareIntegrationProperties properties,
                                             WorkerRunClient workerClient,
                                             SoftwareIntegrationCapabilityService capabilityService,
                                             ObjectMapper objectMapper) {
        this.runStore = runStore;
        this.versionMapper = versionMapper;
        this.modelMapper = modelMapper;
        this.projectMapper = projectMapper;
        this.normalizer = normalizer;
        this.properties = properties;
        this.workerClient = workerClient;
        this.capabilityService = capabilityService;
        this.objectMapper = objectMapper;
    }

    @Override
    public SoftwareIntegrationRunSummaryResponse create(long versionId, SoftwareIntegrationCreateRunRequest request) {
        if (!request.isParametersProvided() || request.getParameters() != null) {
            throw new RunException(HttpStatus.BAD_REQUEST, "parameters 必须显式为 null");
        }
        String runType = request.getRunType();
        if (!RUN_TYPES.contains(runType)) {
            throw new RunException(HttpStatus.BAD_REQUEST, "runType 必须为 nodal、profile、combined、network 或 eclipse");
        }
        SoftwareIntegrationModelVersionEntity version = requireVersion(versionId);
        if (!"READY".equals(version.getStatus())) throw new RunException(HttpStatus.CONFLICT, "只有 READY 模型版本可以创建运行");
        SoftwareIntegrationModelEntity model = modelMapper.selectById(version.getModelId());
        if (model == null || model.getDeletedAt() != null) throw new RunException(HttpStatus.NOT_FOUND, "模型不存在");
        String versionSimulatorType = simulatorType(version.getModelKind());
        if (versionSimulatorType == null) {
            throw new RunException(HttpStatus.CONFLICT, "模型版本缺少已验证类型，请重新验证");
        }
        boolean eclipse = "ECLIPSE_100".equals(versionSimulatorType);
        String lowerOriginalName = version.getOriginalName() == null
                ? "" : version.getOriginalName().toLowerCase(java.util.Locale.ROOT);
        if (eclipse ? !lowerOriginalName.endsWith(".data") : !lowerOriginalName.endsWith(".pips")) {
            throw new RunException(HttpStatus.CONFLICT, "模型文件扩展名与已验证 modelKind 不匹配");
        }
        String study = null;
        if (eclipse) {
            if (version.getStudiesJson() != null && !version.getStudiesJson().isBlank()) {
                throw new RunException(HttpStatus.CONFLICT, "ECLIPSE 模型版本不得包含 Study");
            }
            if (!"eclipse".equals(runType) || !request.isStudyProvided() || request.getStudy() != null) {
                throw new RunException(HttpStatus.BAD_REQUEST, "ECLIPSE 运行必须使用 runType=eclipse 且 study=null");
            }
            if (EclipseDataInspectionValidator.parsePersisted(version.getInspectionJson()) == null) {
                throw new RunException(HttpStatus.CONFLICT, "ECLIPSE 模型版本缺少有效检查信息，请重新验证");
            }
        } else {
            if (!request.isStudyProvided() || request.getStudy() == null || request.getStudy().isBlank()) {
                throw new RunException(HttpStatus.BAD_REQUEST, "PIPESIM 运行必须显式提供非空 Study");
            }
            List<String> matchingStudies = version.getStudiesJson() == null ? List.of()
                    : Arrays.stream(version.getStudiesJson().split("\\n", -1))
                    .filter(candidate -> SoftwareIntegrationDiagnosticSanitizer.sanitize(candidate).equals(request.getStudy()))
                    .toList();
            if (matchingStudies.size() != 1) {
                throw new RunException(HttpStatus.BAD_REQUEST, "Study 不存在、名称不精确匹配或脱敏后不唯一");
            }
            study = matchingStudies.get(0);
        }
        boolean compatible = eclipse && "eclipse".equals(runType)
                || "PIPESIM_NETWORK".equals(versionSimulatorType) && "network".equals(runType)
                || "PIPESIM_WELL".equals(versionSimulatorType) && WELL_RUN_TYPES.contains(runType);
        if (!compatible) throw new RunException(HttpStatus.BAD_REQUEST, "runType 与模型 simulatorType 不兼容");
        SoftwareIntegrationProjectEntity project = projectMapper.selectById(model.getProjectId());
        if (project == null || project.getDeletedAt() != null) throw new RunException(HttpStatus.NOT_FOUND, "软件集成项目不存在");
        normalizeStoredKey(version);
        if (eclipse && !capabilityService.eclipseAvailable()) {
            throw new RunException(HttpStatus.SERVICE_UNAVAILABLE, "ECLIPSE 100 2024.1 Worker 能力不可用");
        }
        SoftwareIntegrationRunEntity run = runStore.createQueued(project.getId(), model.getId(), version.getId(),
                study, runType, eclipse ? ECLIPSE_TIMEOUT_SECONDS : properties.getDefaultRunTimeoutSeconds());
        return summary(run, model, version);
    }

    @Override
    public SoftwareIntegrationRunDetailResponse get(long runId) {
        SoftwareIntegrationRunEntity run = requireRun(runId);
        SoftwareIntegrationModelVersionEntity version = requireVersion(run.getModelVersionId());
        SoftwareIntegrationModelEntity model = modelMapper.selectById(run.getModelId());
        return new SoftwareIntegrationRunDetailResponse(
                run.getId(), run.getProjectId(), run.getModelId(), run.getModelVersionId(), model.getName(), version.getVersionNo(),
                run.getStatus(), SoftwareIntegrationDiagnosticSanitizer.sanitize(run.getStudyName()),
                run.getRunType(), nullNode(), run.getCreatedAt(), run.getQueuedAt(),
                run.getClaimedAt(), run.getStartedAt(), run.getDeadlineAt(), run.getFinishedAt(), run.getTimeoutSeconds(),
                elapsed(run), cancellable(run), sanitize(parse(run.getErrorJson())), sanitize(parse(run.getCleanupJson())), run.getResultContract(),
                sanitize(parse(run.getResultJson())),
                runStore.events(runId).stream().map(event -> new SoftwareIntegrationRunEventResponse(
                        event.getId(), event.getEventSequence(), event.getWorkerSequence(), event.getEventType(), event.getStatus(),
                        SoftwareIntegrationDiagnosticSanitizer.sanitize(event.getMessage()),
                        sanitize(parse(event.getErrorJson())), event.getOccurredAt())).toList(),
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
                model.getName(), version.getVersionNo(), SoftwareIntegrationDiagnosticSanitizer.sanitize(run.getStudyName()),
                run.getRunType(), nullNode(), run.getStatus(),
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

    private JsonNode sanitize(JsonNode value) {
        if (value == null || value.isNull() || value.isNumber() || value.isBoolean()) return value;
        if (value.isTextual()) {
            return objectMapper.getNodeFactory().textNode(
                    SoftwareIntegrationDiagnosticSanitizer.sanitize(value.asText()));
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(sanitize(item)));
            return result;
        }
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            value.properties().forEach(field -> {
                String sanitizedKey = SoftwareIntegrationDiagnosticSanitizer.sanitize(field.getKey());
                String uniqueKey = sanitizedKey;
                for (int suffix = 2; result.has(uniqueKey); suffix++) uniqueKey = sanitizedKey + "#" + suffix;
                result.set(uniqueKey, sanitize(field.getValue()));
            });
            return result;
        }
        return value;
    }

    private static String simulatorType(String modelKind) {
        if ("network".equals(modelKind)) return "PIPESIM_NETWORK";
        if ("eclipse_100".equals(modelKind)) return "ECLIPSE_100";
        if ("black_oil_liquid".equals(modelKind) || "basic_gas".equals(modelKind)
                || "legacy_well".equals(modelKind)) return "PIPESIM_WELL";
        return null;
    }
}
