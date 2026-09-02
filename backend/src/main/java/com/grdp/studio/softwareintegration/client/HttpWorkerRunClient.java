package com.grdp.studio.softwareintegration.client;

import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class HttpWorkerRunClient implements WorkerRunClient {
    private final SoftwareIntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpWorkerRunClient(SoftwareIntegrationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(properties.getWorkerConnectTimeout()).build();
    }

    @Override
    public WorkerAvailability availability() {
        JsonNode body = send(HttpRequest.newBuilder(uri("/api/health"))
                .timeout(properties.getWorkerReadTimeout()).GET().build(), 200);
        String healthStatus = text(body, "status");
        boolean healthy = healthStatus != null && (healthStatus.equalsIgnoreCase("up")
                || healthStatus.equalsIgnoreCase("ok") || healthStatus.equalsIgnoreCase("healthy"));
        boolean explicitIdle = (body.has("idle") && body.path("idle").asBoolean(false))
                || (body.has("busy") && !body.path("busy").asBoolean(true))
                || "IDLE".equals(body.path("state").asText());
        String generationId = text(body, "generationId");
        return new WorkerAvailability(healthy && explicitIdle && generationId != null, generationId);
    }

    @Override
    public WorkerRunAccepted execute(WorkerRunExecuteRequest request) {
        try {
            var payload = objectMapper.createObjectNode();
            payload.put("runId", request.runId());
            payload.put("modelStorageKey", request.modelStorageKey());
            payload.put("expectedModelSha256", request.expectedModelSha256());
            payload.put("study", request.study());
            payload.put("runTask", request.runTask());
            payload.putNull("parameters");
            payload.put("timeoutSeconds", request.timeoutSeconds());
            String json = objectMapper.writeValueAsString(payload);
            JsonNode body = send(HttpRequest.newBuilder(uri("/api/runs/execute"))
                    .header("Content-Type", "application/json")
                    .timeout(properties.getWorkerReadTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(), 202);
            String workerId = text(body, "workerId");
            String generationId = text(body, "generationId");
            String state = text(body, "state");
            long runId = body.path("runId").asLong(-1);
            if (runId != request.runId() || !"CLAIMED".equals(state) || workerId == null || generationId == null) {
                throw new WorkerClientException("Worker returned an invalid acceptance response");
            }
            return new WorkerRunAccepted(runId, state, workerId, generationId);
        } catch (WorkerClientException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WorkerClientException("Worker execute request failed", exception);
        }
    }

    @Override
    public WorkerRunSnapshot get(long runId, long afterSequence) {
        String suffix = "/api/runs/" + runId + "?afterSequence="
                + URLEncoder.encode(Long.toString(afterSequence), StandardCharsets.UTF_8);
        JsonNode body = send(HttpRequest.newBuilder(uri(suffix)).timeout(properties.getWorkerReadTimeout()).GET().build(), 200);
        List<WorkerRunEvent> events = new ArrayList<>();
        body.path("events").forEach(node -> {
            String state = text(node, "state");
            if (state == null) throw new WorkerClientException("Worker event state is missing");
            events.add(new WorkerRunEvent(node.path("sequence").asLong(), state,
                    parseInstant(text(node, "occurredAtUtc")), text(node, "message")));
        });
        List<WorkerRunArtifact> artifacts = new ArrayList<>();
        body.path("artifacts").forEach(node -> artifacts.add(new WorkerRunArtifact(
                text(node, "storageKey"), node.path("size").asLong(-1), text(node, "sha256"), text(node, "contentType"))));
        return new WorkerRunSnapshot(body.path("runId").asLong(-1), text(body, "state"),
                body.path("lastSequence").asLong(afterSequence), text(body, "workerId"), text(body, "generationId"),
                List.copyOf(events), nullable(body.get("result")), nullable(body.get("error")),
                List.copyOf(artifacts), nullable(body.get("cleanup")));
    }

    @Override
    public void cancel(long runId) {
        send(HttpRequest.newBuilder(uri("/api/runs/" + runId + "/cancel"))
                .timeout(properties.getWorkerReadTimeout())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), 200, 202, 409);
    }

    private JsonNode send(HttpRequest request, int... expectedStatuses) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            boolean expected = java.util.Arrays.stream(expectedStatuses).anyMatch(status -> status == response.statusCode());
            JsonNode body = response.body() == null || response.body().isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
            if (!expected) throw new WorkerClientException(
                    "Worker returned HTTP " + response.statusCode(), response.statusCode(), body);
            return body;
        } catch (WorkerClientException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WorkerClientException("Worker request failed", exception);
        } catch (Exception exception) {
            throw new WorkerClientException("Worker request failed", exception);
        }
    }

    private URI uri(String path) {
        return URI.create(properties.getWorkerBaseUrl().replaceAll("/+$", "") + path);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
    }

    private static JsonNode nullable(JsonNode value) {
        return value == null || value.isNull() ? null : value;
    }

    private static Instant parseInstant(String value) {
        if (value == null) throw new WorkerClientException("Worker event timestamp is missing");
        try { return Instant.parse(value); }
        catch (RuntimeException ignored) {
            try { return java.time.OffsetDateTime.parse(value).toInstant(); }
            catch (RuntimeException alsoIgnored) { throw new WorkerClientException("Worker event timestamp is invalid"); }
        }
    }

    public static class WorkerClientException extends RuntimeException {
        private final Integer statusCode;
        private final JsonNode error;
        public WorkerClientException(String message) {
            super(message);
            this.statusCode = null;
            this.error = null;
        }
        public WorkerClientException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
            this.error = null;
        }
        public WorkerClientException(String message, int statusCode, JsonNode error) {
            super(message);
            this.statusCode = statusCode;
            this.error = error;
        }
        public WorkerClientException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = null;
            this.error = null;
        }
        public Integer statusCode() { return statusCode; }
        public JsonNode error() { return error; }
        public String errorCode() { return text(error, "code"); }
        public String errorCategory() { return text(error, "category"); }
    }
}
