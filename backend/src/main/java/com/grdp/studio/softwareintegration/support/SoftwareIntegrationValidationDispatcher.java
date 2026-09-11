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
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class SoftwareIntegrationValidationDispatcher {
    private static final Pattern CONTROLLED_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
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
        String originalName = version.getOriginalName().toLowerCase(Locale.ROOT);
        boolean eclipse = originalName.endsWith(".data");
        update(version, "VALIDATING", eclipse ? "正在验证 ECLIPSE 模型" : "正在读取 PIPESIM 模型和 Study", null);
        if (!originalName.endsWith(".pips") && !eclipse) {
            update(version, "INVALID", "ZIP 模型包解压验证将在下一阶段提供，请上传 .pips 主模型", null);
            return;
        }
        try {
            String storageKey = normalizeAndPersist(version);
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "modelStorageKey", storageKey,
                    "expectedSha256", version.getSha256()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getWorkerBaseUrl()
                    + (eclipse ? "/api/models/inspect" : "/api/models/validate")))
                    .header("Content-Type", "application/json").timeout(Duration.ofMinutes(2))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode payload = objectMapper.readTree(response.body());
            if (response.statusCode() == 200 && "READY".equals(payload.path("status").asText())) {
                String modelKind = payload.path("modelKind").asText();
                String simulatorType = simulatorType(modelKind);
                if (simulatorType == null || eclipse != "eclipse_100".equals(modelKind)) {
                    update(version, "INVALID", "MODEL_KIND_EXTENSION_MISMATCH: Worker 返回的 modelKind 与模型扩展名不匹配", null);
                    return;
                }
                List<String> studies = new ArrayList<>();
                JsonNode studyPayload = payload.get("studies");
                if (studyPayload == null || !studyPayload.isArray()) {
                    update(version, "INVALID", "VALIDATION_RESPONSE_INVALID: Worker Study 列表无效", null);
                    return;
                }
                for (JsonNode study : studyPayload) {
                    if (!study.isTextual()) {
                        update(version, "INVALID", "VALIDATION_RESPONSE_INVALID: Worker Study 名称无效", null);
                        return;
                    }
                    studies.add(study.asText());
                }
                if (eclipse && !studies.isEmpty()) {
                    update(version, "INVALID", "ECLIPSE_STUDY_UNSUPPORTED: ECLIPSE 模型不得返回 Study", null);
                    return;
                }
                String inspection = null;
                if (eclipse) {
                    try {
                        inspection = EclipseDataInspectionValidator.validateAndSerialize(payload.get("inspection"), objectMapper);
                    } catch (IllegalArgumentException exception) {
                        update(version, "INVALID", "INSPECTION_RESPONSE_INVALID: Worker inspection metadata is invalid", null);
                        return;
                    }
                }
                String readyMessage = payload.path("message").asText("模型验证完成");
                if (eclipse) readyMessage = SoftwareIntegrationEclipseSanitizer.sanitizeText(readyMessage);
                if (!persistReady(version, modelKind, simulatorType,
                        readyMessage, eclipse ? null : String.join("\n", studies), inspection)) {
                    update(version, "INVALID", "模型版本所属模型不存在", null);
                    return;
                }
            } else {
                JsonNode error = payload.path("error");
                String code = error.path("code").asText(payload.path("code").asText());
                String category = error.path("category").asText(payload.path("category").asText());
                boolean inspectionInputError = eclipse && ("ECLIPSE_INCLUDE_UNSUPPORTED".equals(code)
                        || (code != null && (code.startsWith("ECLIPSE_DATA_")
                        || code.startsWith("ECLIPSE_RESOURCE_") || code.startsWith("ECLIPSE_ENCODING_"))));
                boolean environment = !inspectionInputError && (response.statusCode() == 409 || response.statusCode() == 503
                        || "ENVIRONMENT_ERROR".equals(payload.path("status").asText())
                        || "ENVIRONMENT".equals(category) || "ECLIPSE_UNAVAILABLE".equals(code)
                        || "ECLIPSE_VERSION_MISMATCH".equals(code));
                String status = environment ? "ENVIRONMENT_ERROR" : "INVALID";
                String fallback = eclipse ? "ECLIPSE 模型验证失败" : "PIPESIM 模型验证失败";
                String message = error.path("message").asText(
                        payload.path("message").asText(payload.path("detail").asText(fallback)));
                update(version, status, classifiedMessage(code, message, eclipse), null);
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
                                                String simulatorType, String message, String studies, String inspection) {
        SoftwareIntegrationModelEntity model = modelMapper.selectById(version.getModelId());
        if (model == null || model.getDeletedAt() != null) return false;
        update(version, "READY", message, studies, inspection, modelKind);
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
            case "eclipse_100" -> "ECLIPSE_100";
            default -> null;
        };
    }

    private static String classifiedMessage(String code, String message, boolean eclipse) {
        String sanitized = eclipse ? SoftwareIntegrationEclipseSanitizer.sanitizeText(message)
                : SoftwareIntegrationDiagnosticSanitizer.sanitize(message);
        if (!eclipse || code == null || !CONTROLLED_ERROR_CODE.matcher(code).matches()) return sanitized;
        return code + ": " + sanitized;
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
        update(version, status, message, studies, null, null);
    }

    private void update(SoftwareIntegrationModelVersionEntity version, String status, String message,
                        String studies, String modelKind) {
        update(version, status, message, studies, null, modelKind);
    }

    private void update(SoftwareIntegrationModelVersionEntity version, String status, String message,
                        String studies, String inspection, String modelKind) {
        LambdaUpdateWrapper<SoftwareIntegrationModelVersionEntity> update =
                new LambdaUpdateWrapper<SoftwareIntegrationModelVersionEntity>()
                .eq(SoftwareIntegrationModelVersionEntity::getId, version.getId())
                .set(SoftwareIntegrationModelVersionEntity::getStatus, status)
                .set(SoftwareIntegrationModelVersionEntity::getStudiesJson, studies)
                .set(SoftwareIntegrationModelVersionEntity::getInspectionJson, inspection)
                .set(SoftwareIntegrationModelVersionEntity::getUpdatedAt, LocalDateTime.now());
        if (modelKind != null) update.set(SoftwareIntegrationModelVersionEntity::getModelKind, modelKind);
        String sanitizedMessage = SoftwareIntegrationDiagnosticSanitizer.sanitize(message);
        if (sanitizedMessage != null && sanitizedMessage.length() > 1000) {
            sanitizedMessage = sanitizedMessage.substring(0, 1000);
        }
        update.set(SoftwareIntegrationModelVersionEntity::getValidationMessage, sanitizedMessage);
        versionMapper.update(null, update);
    }
}
