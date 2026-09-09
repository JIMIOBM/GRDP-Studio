package com.grdp.studio.softwareintegration.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelVersionMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class SoftwareIntegrationValidationDispatcher {
    private final SoftwareIntegrationModelVersionMapper versionMapper;
    private final SoftwareIntegrationModelMapper modelMapper;
    private final SoftwareIntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final SoftwareIntegrationStorageKeyNormalizer storageKeyNormalizer;
    private final HttpClient httpClient;

    public SoftwareIntegrationValidationDispatcher(SoftwareIntegrationModelVersionMapper versionMapper,
                                                     SoftwareIntegrationModelMapper modelMapper,
                                                     SoftwareIntegrationProperties properties, ObjectMapper objectMapper,
                                                     SoftwareIntegrationStorageKeyNormalizer storageKeyNormalizer) {
        this.versionMapper = versionMapper;
        this.modelMapper = modelMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.storageKeyNormalizer = storageKeyNormalizer;
        this.httpClient = HttpClient.newBuilder().connectTimeout(properties.getWorkerConnectTimeout()).build();
    }

    @Async
    public void validate(long versionId) {
        SoftwareIntegrationModelVersionEntity version = versionMapper.selectById(versionId);
        if (version == null || !"UPLOADED".equals(version.getStatus())) return;
        update(version, "VALIDATING", "正在读取 PIPESIM 模型和 Study", null);
        if (!version.getOriginalName().toLowerCase().endsWith(".pips")) {
            update(version, "INVALID", "ZIP 模型包解压验证将在下一阶段提供，请上传 .pips 主模型", null);
            return;
        }
        try {
            String storageKey = normalizeAndPersist(version);
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "modelStorageKey", storageKey,
                    "expectedSha256", version.getSha256()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getWorkerBaseUrl() + "/api/models/validate"))
                    .header("Content-Type", "application/json").timeout(Duration.ofMinutes(2))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode payload = objectMapper.readTree(response.body());
            if (response.statusCode() == 200 && "READY".equals(payload.path("status").asText())) {
                String simulatorType = simulatorType(payload.path("modelKind").asText());
                if (simulatorType == null) {
                    update(version, "INVALID", "Worker 返回不支持的 PIPESIM 模型类型", null);
                    return;
                }
                List<String> studies = new ArrayList<>();
                payload.path("studies").forEach(study -> studies.add(study.asText()));
                if (!persistReady(version, payload.path("modelKind").asText(), simulatorType,
                        payload.path("message").asText("模型验证完成"), String.join("\n", studies))) {
                    update(version, "INVALID", "模型版本所属模型不存在", null);
                    return;
                }
            } else {
                String status = response.statusCode() == 409 || response.statusCode() == 503
                        || "ENVIRONMENT_ERROR".equals(payload.path("status").asText()) ? "ENVIRONMENT_ERROR" : "INVALID";
                update(version, status, payload.path("message").asText(payload.path("detail").asText("PIPESIM 模型验证失败")), null);
            }
        } catch (Exception exception) {
            update(version, "ENVIRONMENT_ERROR", "无法连接软件集成 Worker", null);
        }
    }

    private boolean persistSimulatorType(long modelId, String simulatorType) {
        return modelMapper.update(null, new LambdaUpdateWrapper<SoftwareIntegrationModelEntity>()
                .eq(SoftwareIntegrationModelEntity::getId, modelId)
                .isNull(SoftwareIntegrationModelEntity::getDeletedAt)
                .set(SoftwareIntegrationModelEntity::getSimulatorType, simulatorType)
                .set(SoftwareIntegrationModelEntity::getUpdatedAt, LocalDateTime.now())) == 1;
    }

    private synchronized boolean persistReady(SoftwareIntegrationModelVersionEntity version, String modelKind,
                                               String simulatorType, String message, String studies) {
        SoftwareIntegrationModelEntity model = modelMapper.selectById(version.getModelId());
        if (model == null || model.getDeletedAt() != null) return false;
        update(version, "READY", message, studies, modelKind);
        SoftwareIntegrationModelVersionEntity newestReady = versionMapper.selectOne(
                new LambdaQueryWrapper<SoftwareIntegrationModelVersionEntity>()
                        .eq(SoftwareIntegrationModelVersionEntity::getModelId, version.getModelId())
                        .eq(SoftwareIntegrationModelVersionEntity::getStatus, "READY")
                        .orderByDesc(SoftwareIntegrationModelVersionEntity::getVersionNo)
                        .last("LIMIT 1"));
        String newestType = newestReady == null ? simulatorType : simulatorType(newestReady.getModelKind());
        return persistSimulatorType(version.getModelId(), newestType == null ? simulatorType : newestType);
    }

    private static String simulatorType(String modelKind) {
        if (modelKind == null) return null;
        return switch (modelKind) {
            case "network" -> "PIPESIM_NETWORK";
            case "black_oil_liquid", "basic_gas", "legacy_well" -> "PIPESIM_WELL";
            default -> null;
        };
    }

    private String normalizeAndPersist(SoftwareIntegrationModelVersionEntity version) {
        String normalized = storageKeyNormalizer.normalizeStoredKey(version.getStorageKey());
        if (!normalized.equals(version.getStorageKey())) {
            int updated = versionMapper.update(null, new LambdaUpdateWrapper<SoftwareIntegrationModelVersionEntity>()
                    .eq(SoftwareIntegrationModelVersionEntity::getId, version.getId())
                    .eq(SoftwareIntegrationModelVersionEntity::getStorageKey, version.getStorageKey())
                    .set(SoftwareIntegrationModelVersionEntity::getStorageKey, normalized));
            if (updated != 1) throw new IllegalStateException("Model storage key CAS failed");
            version.setStorageKey(normalized);
        }
        return normalized;
    }

    private void update(SoftwareIntegrationModelVersionEntity version, String status, String message, String studies) {
        update(version, status, message, studies, null);
    }

    private void update(SoftwareIntegrationModelVersionEntity version, String status, String message,
                        String studies, String modelKind) {
        LambdaUpdateWrapper<SoftwareIntegrationModelVersionEntity> update =
                new LambdaUpdateWrapper<SoftwareIntegrationModelVersionEntity>()
                .eq(SoftwareIntegrationModelVersionEntity::getId, version.getId())
                .set(SoftwareIntegrationModelVersionEntity::getStatus, status)
                .set(SoftwareIntegrationModelVersionEntity::getValidationMessage,
                        SoftwareIntegrationDiagnosticSanitizer.sanitize(message))
                .set(SoftwareIntegrationModelVersionEntity::getStudiesJson, studies)
                .set(SoftwareIntegrationModelVersionEntity::getUpdatedAt, LocalDateTime.now());
        if (modelKind != null) update.set(SoftwareIntegrationModelVersionEntity::getModelKind, modelKind);
        versionMapper.update(null, update);
    }
}
