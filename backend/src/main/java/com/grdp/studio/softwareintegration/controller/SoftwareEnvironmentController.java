package com.grdp.studio.softwareintegration.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/** Host environment management is independent of model/project data. */
@RestController
@RequestMapping("/software-integration/environment")
public class SoftwareEnvironmentController {
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1");
    private final SoftwareIntegrationProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public SoftwareEnvironmentController(SoftwareIntegrationProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @GetMapping
    public ResponseEntity<ApiResponse<JsonNode>> inspect() { return forward(null); }

    @PutMapping
    public ResponseEntity<ApiResponse<JsonNode>> save(@RequestBody JsonNode body, HttpServletRequest request) {
        // Do not trust forwarded headers: changing executable paths requires a host-local operator.
        if (!isLocalConfigurationRequest(request.getRemoteAddr(), request.getHeader("Origin"), request.getHeader("X-GRDP-Environment")))
            return failure(403, "请在计算主机上打开本地网页配置环境；远程用户只能查看状态。");
        if (body == null || !body.isObject() || !body.path("paths").isObject()
                || !(body.path("simulator").asText().equals("pipesim") || body.path("simulator").asText().equals("eclipse")))
            return failure(400, "环境配置请求不完整。");
        return forward(body);
    }

    public static boolean isLocalConfigurationRequest(String address, String origin, String header) {
        if (!LOOPBACK.contains(address) || !"1".equals(header)) return false;
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && LOOPBACK.contains(uri.getHost());
        } catch (IllegalArgumentException ex) { return false; }
    }

    private ResponseEntity<ApiResponse<JsonNode>> forward(JsonNode body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(properties.getWorkerBaseUrl().replaceAll("/+$", "") + "/api/environment"))
                    .timeout(Duration.ofSeconds(55));
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json").header("X-GRDP-Environment", "1")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String message = "计算服务无法完成环境配置。";
                try { message = mapper.readTree(response.body()).path("message").asText(message); }
                catch (RuntimeException ignored) { }
                return failure(response.statusCode(), message);
            }
            return ResponseEntity.ok(ApiResponse.success(mapper.readTree(response.body())));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failure(503, "环境检测被中断，请重试。");
        } catch (Exception ex) {
            return failure(503, "无法连接本地计算服务，请先启动 Worker，再点击重新检测。");
        }
    }

    private static ResponseEntity<ApiResponse<JsonNode>> failure(int code, String message) {
        return ResponseEntity.status(code).body(new ApiResponse<>(code, message, null));
    }
}
