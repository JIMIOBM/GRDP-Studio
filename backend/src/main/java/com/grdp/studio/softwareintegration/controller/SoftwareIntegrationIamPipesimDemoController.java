package com.grdp.studio.softwareintegration.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/software-integration/iam-pipesim-demo")
public class SoftwareIntegrationIamPipesimDemoController {
    private static final Pattern RUN_ID = Pattern.compile("^[0-9a-f]{32}$");

    private final SoftwareIntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SoftwareIntegrationIamPipesimDemoController(SoftwareIntegrationProperties properties,
                                                        ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getWorkerConnectTimeout())
                .build();
    }

    @GetMapping("/capabilities")
    public ResponseEntity<ApiResponse<JsonNode>> capabilities() {
        return forward("/api/iam-pipesim-demo/capabilities", "GET", null);
    }

    @GetMapping("/runs")
    public ResponseEntity<ApiResponse<JsonNode>> list() {
        return forward("/api/iam-pipesim-demo/runs", "GET", null);
    }

    @PostMapping("/runs")
    public ResponseEntity<ApiResponse<JsonNode>> start(@RequestBody JsonNode request) throws IOException {
        return forward("/api/iam-pipesim-demo/runs", "POST", objectMapper.writeValueAsString(request));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<ApiResponse<JsonNode>> get(@PathVariable String runId) {
        requireRunId(runId);
        return forward("/api/iam-pipesim-demo/runs/" + runId, "GET", null);
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<ApiResponse<JsonNode>> cancel(@PathVariable String runId) {
        requireRunId(runId);
        return forward("/api/iam-pipesim-demo/runs/" + runId + "/cancel", "POST", null);
    }

    private ResponseEntity<ApiResponse<JsonNode>> forward(String path, String method, String body) {
        try {
            var requestBuilder = HttpRequest.newBuilder(workerUri(path))
                    .timeout(properties.getWorkerReadTimeout());
            if (body != null) requestBuilder.header("Content-Type", "application/json");
            if ("POST".equals(method)) requestBuilder.POST(body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            else requestBuilder.GET();
            var response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode responseBody = response.body() == null || response.body().isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return ResponseEntity.status(response.statusCode()).body(ApiResponse.success(responseBody));
            }
            String message = responseBody.path("message").asText(responseBody.path("code").asText("Worker request failed"));
            return ResponseEntity.status(response.statusCode())
                    .body(new ApiResponse<>(response.statusCode(), message, null));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Software Integration Worker request was interrupted.");
        } catch (IOException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Software Integration Worker is unavailable.");
        }
    }

    private URI workerUri(String path) {
        return URI.create(properties.getWorkerBaseUrl().replaceAll("/+$", "") + path);
    }

    private static void requireRunId(String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid IAM demo run identifier.");
        }
    }
}
