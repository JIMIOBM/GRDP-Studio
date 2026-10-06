package com.grdp.studio.softwareintegration.client;

import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Internal transport only: a project-authorized durable dispatcher must own request IDs. */
@Component
public class WorkerTemplateCreationClient {
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_MODEL_BYTES = 64 * 1024 * 1024;
    private static final Set<String> STATES = Set.of("PREPARING", "SUCCEEDED", "FAILED", "INTERRUPTED", "CANCELLED", "TIMED_OUT");
    private final SoftwareIntegrationProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public WorkerTemplateCreationClient(SoftwareIntegrationProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(properties.getWorkerConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public record Reply(int statusCode, JsonNode record) { }

    public int executionBudgetSeconds() {
        Reply reply = json(HttpRequest.newBuilder(URI.create(properties.getWorkerBaseUrl().replaceAll("/+$", "")
                + "/api/model-creations/pipesim-template/capabilities"))
                .timeout(properties.getWorkerReadTimeout()).GET().build());
        JsonNode budget = reply.record().path("maxRunTimeoutSeconds");
        JsonNode cleanup = reply.record().path("maxCleanupSeconds");
        JsonNode cancellation = reply.record().path("supportsCancellation");
        JsonNode modelLimit = reply.record().path("maxModelBytes");
        if (reply.statusCode() != 200
                || !"pipesim-template-creation-capabilities/1".equals(reply.record().path("schemaVersion").asText())
                || !"PIPESIM_FIELD".equals(reply.record().path("unitsSystem").asText())
                || !"Simple vertical".equals(reply.record().path("template").asText())
                || !cancellation.isBoolean() || !cancellation.booleanValue()
                || !modelLimit.isIntegralNumber() || !modelLimit.canConvertToInt() || modelLimit.intValue() != MAX_MODEL_BYTES
                || !cleanup.isIntegralNumber() || !cleanup.canConvertToInt() || cleanup.intValue() < 0 || cleanup.intValue() > 600
                || !budget.isIntegralNumber() || !budget.canConvertToInt() || budget.intValue() < 1 || budget.intValue() > 86_400) {
            throw new CreationTransportException("Worker creation budget/cancellation capability unavailable", reply.statusCode(), false);
        }
        return budget.intValue() + cleanup.intValue();
    }

    /** 202 acknowledges a signal, not native process termination or a terminal CANCELLED record. */
    public Reply cancel(UUID id) {
        requireId(id);
        Reply reply = json(HttpRequest.newBuilder(URI.create(properties.getWorkerBaseUrl().replaceAll("/+$", "")
                + "/api/model-creations/" + id + "/cancel"))
                .timeout(properties.getWorkerReadTimeout()).POST(HttpRequest.BodyPublishers.noBody()).build());
        if (reply.statusCode() == 200) validateRecord(id, reply.record());
        else if (reply.statusCode() != 202 || !id.toString().equalsIgnoreCase(reply.record().path("requestId").asText())
                || !"CANCEL_REQUESTED".equals(reply.record().path("status").asText())) {
            throw new CreationTransportException("Worker cancellation unavailable", reply.statusCode(), false);
        }
        return reply;
    }

    /** Never automatically retries POST: a lost response is recovered with find(id). */
    public Reply create(UUID id, String well, JsonNode inputs) {
        requireId(id);
        if (well == null || !well.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || inputs == null || !inputs.isObject()) {
            throw new IllegalArgumentException("Invalid template creation input");
        }
        var payload = mapper.createObjectNode();
        payload.put("requestId", id.toString());
        payload.put("well", well);
        payload.set("inputs", inputs);
        Duration timeout = Duration.ofSeconds(executionBudgetSeconds() + 30L);
        HttpRequest request = HttpRequest.newBuilder(uri(id, true, false)).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
        Reply reply = json(request);
        if (Set.of(200, 201, 422).contains(reply.statusCode())
                || reply.statusCode() == 503 && reply.record().has("requestId")) validateRecord(id, reply.record());
        else throw new CreationTransportException("Worker rejected creation", reply.statusCode(), false);
        return reply;
    }

    /** 404 means no readable record, not permission to dispatch the same creation again. */
    public JsonNode find(UUID id) {
        requireId(id);
        Reply reply = json(HttpRequest.newBuilder(uri(id, false, false))
                .timeout(properties.getWorkerReadTimeout()).GET().build());
        if (reply.statusCode() == 404) return null;
        if (reply.statusCode() != 200) throw new CreationTransportException("Worker record unavailable", reply.statusCode(), false);
        validateRecord(id, reply.record());
        return reply.record();
    }

    /** Caller supplies a freshly recovered record; no artifact path or URL is accepted. */
    public byte[] download(UUID id, JsonNode record) {
        requireId(id);
        validateRecord(id, record);
        JsonNode artifact = record.path("model");
        if (!"SUCCEEDED".equals(record.path("status").asText()) || !artifact.isObject()) {
            throw new IllegalArgumentException("Only successful creation models can be downloaded");
        }
        JsonNode size = artifact.path("sizeBytes");
        String sha = artifact.path("sha256").asText("");
        if (!size.isIntegralNumber() || !size.canConvertToLong() || size.longValue() <= 0
                || size.longValue() > MAX_MODEL_BYTES || !sha.matches("[a-fA-F0-9]{64}")) {
            throw new CreationTransportException("Invalid creation artifact metadata", 0, false);
        }
        HttpRequest request = HttpRequest.newBuilder(uri(id, false, true))
                .timeout(properties.getWorkerReadTimeout()).GET().build();
        try {
            HttpResponse<byte[]> response = boundedSend(request, (int) size.longValue() + 1);
            if (response.statusCode() != 200) throw new CreationTransportException("Creation model unavailable", response.statusCode(), false);
            byte[] bytes = response.body();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (bytes.length != size.longValue() || !actual.equalsIgnoreCase(sha)) {
                throw new CreationTransportException("Creation model integrity mismatch", 0, false);
            }
            return bytes;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CreationTransportException("Creation download interrupted", exception, false);
        } catch (CreationTransportException exception) { throw exception;
        } catch (Exception exception) { throw new CreationTransportException("Creation download failed", exception, false); }
    }

    private Reply json(HttpRequest request) {
        boolean uncertainDispatch = "POST".equals(request.method());
        try {
            HttpResponse<byte[]> response = boundedSend(request, MAX_JSON_BYTES);
            byte[] bytes = response.body();
            return new Reply(response.statusCode(), bytes.length == 0 ? mapper.nullNode() : mapper.readTree(bytes));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CreationTransportException("Creation request interrupted; recover record before retry", exception, uncertainDispatch);
        } catch (Exception exception) {
            throw new CreationTransportException("Creation response unavailable; recover record before retry", exception, uncertainDispatch);
        }
    }

    /** Bound both total body size and wall-clock time, including a stalled streaming body. */
    private HttpResponse<byte[]> boundedSend(HttpRequest request, int limit) throws Exception {
        var pending = http.sendAsync(request, ignored -> new LimitedBody(limit));
        try { return pending.get(request.timeout().orElseThrow().toMillis(), TimeUnit.MILLISECONDS); }
        catch (Exception exception) { pending.cancel(true); throw exception; }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Creation response exceeds limit")); return;
                }
                byte[] data = new byte[chunk.remaining()]; chunk.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    private static void validateRecord(UUID id, JsonNode record) {
        if (record == null || !record.isObject() || !id.toString().equalsIgnoreCase(record.path("requestId").asText())
                || !STATES.contains(record.path("status").asText())) {
            throw new CreationTransportException("Invalid creation record identity/state", 0, true);
        }
    }

    private URI uri(UUID id, boolean create, boolean model) {
        String base = properties.getWorkerBaseUrl().replaceAll("/+$", "");
        return URI.create(base + (create ? "/api/model-creations/pipesim-template"
                : "/api/model-creations/" + id + (model ? "/model" : "")));
    }

    private static void requireId(UUID id) {
        if (id == null || id.equals(new UUID(0, 0))) throw new IllegalArgumentException("Creation ID is required");
    }

    public static final class CreationTransportException extends RuntimeException {
        private final int statusCode;
        private final boolean dispatchUncertain;
        public CreationTransportException(String message, int statusCode, boolean dispatchUncertain) {
            super(message); this.statusCode = statusCode; this.dispatchUncertain = dispatchUncertain;
        }
        public CreationTransportException(String message, Throwable cause, boolean dispatchUncertain) {
            super(message, cause); this.statusCode = 0; this.dispatchUncertain = dispatchUncertain;
        }
        public int statusCode() { return statusCode; }
        public boolean dispatchUncertain() { return dispatchUncertain; }
    }
}
