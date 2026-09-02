package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.client.HttpWorkerRunClient;
import com.grdp.studio.softwareintegration.client.HttpWorkerRunClient.WorkerClientException;
import com.grdp.studio.softwareintegration.client.WorkerRunExecuteRequest;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpWorkerRunClientTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> executeBody = new AtomicReference<>();
    private final AtomicReference<String> pollQuery = new AtomicReference<>();
    private final AtomicInteger executeStatus = new AtomicInteger(202);
    private HttpServer server;
    private HttpWorkerRunClient client;

    @BeforeEach
    void startFakeWorker() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/health", exchange -> respond(exchange, 200,
                "{\"status\":\"UP\",\"generationId\":\"generation-1\",\"activeRunId\":null,\"idle\":true}"));
        server.createContext("/api/runs/execute", exchange -> {
            executeBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (executeStatus.get() == 409) {
                respond(exchange, 409, "{\"category\":\"COORDINATION\",\"code\":\"WORKER_BUSY\",\"message\":\"busy\",\"retryable\":true}");
            } else {
                respond(exchange, 202, "{\"runId\":41,\"state\":\"CLAIMED\",\"workerId\":\"worker-1\",\"generationId\":\"generation-1\",\"acceptedAtUtc\":\"2026-08-31T00:00:00Z\"}");
            }
        });
        server.createContext("/api/runs/41", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/cancel")) {
                respond(exchange, 202, "{}");
            } else {
                pollQuery.set(exchange.getRequestURI().getQuery());
                respond(exchange, 200, "{\"runId\":41,\"state\":\"PREPARING\",\"lastSequence\":3,\"workerId\":\"worker-1\",\"generationId\":\"generation-1\",\"events\":[{\"sequence\":3,\"state\":\"PREPARING\",\"occurredAtUtc\":\"2026-08-31T00:00:00Z\",\"message\":\"Preparing\"}],\"result\":null,\"error\":null,\"artifacts\":[{\"storageKey\":\"jobs/41/output/run.log\",\"size\":12,\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"contentType\":\"text/plain\"}],\"cleanup\":null}");
            }
        });
        server.start();
        SoftwareIntegrationProperties properties = new SoftwareIntegrationProperties();
        properties.setWorkerBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = new HttpWorkerRunClient(properties, objectMapper);
    }

    @AfterEach
    void stopFakeWorker() { if (server != null) server.stop(0); }

    @Test
    void usesFrozenRunApiAndAlwaysSerializesExplicitNullParameters() {
        assertThat(client.availability().idle()).isTrue();
        var accepted = client.execute(new WorkerRunExecuteRequest(41, "models/2/3/model.pips", "a".repeat(64),
                "Study 1", "combined", null, 600));
        assertThat(accepted.workerId()).isEqualTo("worker-1");
        JsonNode request = objectMapper.readTree(executeBody.get());
        assertThat(request.size()).isEqualTo(7);
        assertThat(request.path("runId").asLong()).isEqualTo(41);
        assertThat(request.path("modelStorageKey").asText()).isEqualTo("models/2/3/model.pips");
        assertThat(request.path("expectedModelSha256").asText()).isEqualTo("a".repeat(64));
        assertThat(request.path("study").asText()).isEqualTo("Study 1");
        assertThat(request.path("runTask").asText()).isEqualTo("combined");
        assertThat(request.has("parameters") && request.path("parameters").isNull()).isTrue();
        assertThat(request.path("timeoutSeconds").asInt()).isEqualTo(600);
        var snapshot = client.get(41, 2);
        assertThat(snapshot.lastSequence()).isEqualTo(3);
        assertThat(snapshot.workerId()).isEqualTo("worker-1");
        assertThat(snapshot.events()).singleElement().satisfies(event -> {
            assertThat(event.state()).isEqualTo("PREPARING");
            assertThat(event.occurredAtUtc()).isEqualTo(java.time.Instant.parse("2026-08-31T00:00:00Z"));
        });
        assertThat(snapshot.artifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.storageKey()).isEqualTo("jobs/41/output/run.log");
            assertThat(artifact.size()).isEqualTo(12);
        });
        assertThat(pollQuery.get()).isEqualTo("afterSequence=2");
        client.cancel(41);
    }

    @Test
    void preservesStructuredWorkerErrorForBusyDecision() {
        executeStatus.set(409);
        assertThatThrownBy(() -> client.execute(new WorkerRunExecuteRequest(
                41, "models/2/3/model.pips", "a".repeat(64), "Study 1", "nodal", null, 600)))
                .isInstanceOf(WorkerClientException.class)
                .satisfies(error -> {
                    WorkerClientException workerError = (WorkerClientException) error;
                    assertThat(workerError.statusCode()).isEqualTo(409);
                    assertThat(workerError.errorCategory()).isEqualTo("COORDINATION");
                    assertThat(workerError.errorCode()).isEqualTo("WORKER_BUSY");
                    assertThat(workerError.error().path("retryable").asBoolean()).isTrue();
                });
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
