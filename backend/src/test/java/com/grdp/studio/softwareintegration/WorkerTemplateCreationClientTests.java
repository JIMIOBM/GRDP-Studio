package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.client.WorkerTemplateCreationClient;
import com.grdp.studio.softwareintegration.client.WorkerTemplateCreationClient.CreationTransportException;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class WorkerTemplateCreationClientTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID id = UUID.randomUUID();
    private HttpServer server;
    private WorkerTemplateCreationClient client;
    private int status;
    private byte[] response;
    private final AtomicInteger calls = new AtomicInteger();
    private String lastPath;
    private String lastMethod;

    @BeforeEach void setup() throws Exception {
        status = 200;
        response = record("SUCCEEDED").toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); lastPath = exchange.getRequestURI().getPath(); lastMethod = exchange.getRequestMethod();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        var properties = new SoftwareIntegrationProperties();
        properties.setWorkerBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = new WorkerTemplateCreationClient(properties, mapper);
    }

    @AfterEach void stop() { server.stop(0); }

    private JsonNode record(String state) {
        var node = mapper.createObjectNode(); node.put("requestId", id.toString()); node.put("status", state); return node;
    }

    @Test void createAcceptsSuccessAndFailedDurableRecords() {
        status = 201;
        assertThat(client.create(id, "ConsoleWell", mapper.createObjectNode()).statusCode()).isEqualTo(201);
        assertThat(lastPath).isEqualTo("/api/model-creations/pipesim-template"); assertThat(lastMethod).isEqualTo("POST");
        status = 422; response = record("FAILED").toString().getBytes(StandardCharsets.UTF_8);
        assertThat(client.create(id, "ConsoleWell", mapper.createObjectNode()).record().path("status").asText()).isEqualTo("FAILED");
    }

    @Test void rejectionIsNotRetried() {
        status = 409;
        assertThatThrownBy(() -> client.create(id, "Well", mapper.createObjectNode()))
                .isInstanceOfSatisfying(CreationTransportException.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(409); assertThat(e.dispatchUncertain()).isFalse();
                });
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void malformedPostResponseRequiresRecoveryNotRetry() {
        response = "not-json".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> client.create(id, "Well", mapper.createObjectNode()))
                .isInstanceOfSatisfying(CreationTransportException.class, e -> assertThat(e.dispatchUncertain()).isTrue());
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void findHandlesMissingAndRejectsWrongIdentityAndState() {
        status = 404; response = new byte[0]; assertThat(client.find(id)).isNull();
        status = 200; response = record("INVALID").toString().getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> client.find(id)).isInstanceOf(CreationTransportException.class);
        response = record("SUCCEEDED").toString().replace(id.toString(), UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> client.find(id)).isInstanceOf(CreationTransportException.class);
    }

    @Test void downloadRequiresExactSizeAndHash() throws Exception {
        response = "test model bytes".getBytes(StandardCharsets.UTF_8);
        var node = (tools.jackson.databind.node.ObjectNode) record("SUCCEEDED");
        var artifact = node.putObject("model"); artifact.put("sizeBytes", response.length);
        artifact.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response)));
        assertThat(client.download(id, node)).isEqualTo(response);
        assertThat(lastPath).isEqualTo("/api/model-creations/" + id + "/model");
        artifact.put("sha256", "0".repeat(64));
        assertThatThrownBy(() -> client.download(id, node)).isInstanceOf(CreationTransportException.class);
        artifact.put("sizeBytes", response.length - 1);
        assertThatThrownBy(() -> client.download(id, node)).isInstanceOf(CreationTransportException.class);
    }

    @Test void invalidInputsAndArtifactAreRejectedBeforeNetwork() {
        assertThatThrownBy(() -> client.find(new UUID(0, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.create(id, "../well", mapper.createObjectNode())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.download(id, record("FAILED"))).isInstanceOf(IllegalArgumentException.class);
        var node = (tools.jackson.databind.node.ObjectNode) record("SUCCEEDED");
        node.putObject("model").put("sizeBytes", 65L * 1024 * 1024).put("sha256", "0".repeat(64));
        assertThatThrownBy(() -> client.download(id, node)).isInstanceOf(CreationTransportException.class);
        assertThat(calls.get()).isZero();
    }

    @Test void redirectsAreNotFollowed() {
        status = 302;
        assertThatThrownBy(() -> client.find(id)).isInstanceOf(CreationTransportException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void oversizedRecordIsRejected() {
        response = new byte[2 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> client.find(id)).isInstanceOf(CreationTransportException.class);
    }

    @Test void cancellationAndTimeoutAreValidTerminalRecords() {
        for (String state : java.util.List.of("CANCELLED", "TIMED_OUT", "INTERRUPTED")) {
            response = record(state).toString().getBytes(StandardCharsets.UTF_8);
            assertThat(client.find(id).path("status").asText()).isEqualTo(state);
        }
        status = 503; response = record("FAILED").toString().getBytes(StandardCharsets.UTF_8);
        assertThat(client.create(id, "Well", mapper.createObjectNode()).statusCode()).isEqualTo(503);
    }

    @Test void stalledBodyIsCoveredByReadTimeout() throws Exception {
        server.removeContext("/");
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 10);
            try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        var properties = new SoftwareIntegrationProperties();
        properties.setWorkerBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setWorkerReadTimeout(java.time.Duration.ofMillis(100));
        var shortTimeout = new WorkerTemplateCreationClient(properties, mapper);
        long started = System.nanoTime();
        try { assertThatThrownBy(() -> shortTimeout.find(id)).isInstanceOf(CreationTransportException.class); }
        finally { release.countDown(); }
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(1));
    }
}
