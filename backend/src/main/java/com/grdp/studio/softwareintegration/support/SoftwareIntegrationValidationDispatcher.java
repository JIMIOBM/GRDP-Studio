package com.grdp.studio.softwareintegration.support;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;
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
    private final SoftwareIntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final SoftwareIntegrationStorageKeyNormalizer storageKeyNormalizer;
    private final HttpClient httpClient;

    public SoftwareIntegrationValidationDispatcher(SoftwareIntegrationModelVersionMapper versionMapper,
                                                     SoftwareIntegrationProperties properties, ObjectMapper objectMapper,
                                                     SoftwareIntegrationStorageKeyNormalizer storageKeyNormalizer) {
        this.versionMapper = versionMapper;
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
                List<String> studies = new ArrayList<>();
                payload.path("studies").forEach(study -> studies.add(study.asText()));
                update(version, "READY", payload.path("message").asText("模型验证完成"), String.join("\n", studies));
            } else {
                String status = response.statusCode() == 409 || response.statusCode() == 503
                        || "ENVIRONMENT_ERROR".equals(payload.path("status").asText()) ? "ENVIRONMENT_ERROR" : "INVALID";
                update(version, status, payload.path("message").asText(payload.path("detail").asText("PIPESIM 模型验证失败")), null);
            }
        } catch (Exception exception) {
            update(version, "ENVIRONMENT_ERROR", "无法连接软件集成 Worker", null);
        }
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
        versionMapper.update(null, new LambdaUpdateWrapper<SoftwareIntegrationModelVersionEntity>()
                .eq(SoftwareIntegrationModelVersionEntity::getId, version.getId())
                .set(SoftwareIntegrationModelVersionEntity::getStatus, status)
                .set(SoftwareIntegrationModelVersionEntity::getValidationMessage, message)
                .set(SoftwareIntegrationModelVersionEntity::getStudiesJson, studies)
                .set(SoftwareIntegrationModelVersionEntity::getUpdatedAt, LocalDateTime.now()));
    }
}
