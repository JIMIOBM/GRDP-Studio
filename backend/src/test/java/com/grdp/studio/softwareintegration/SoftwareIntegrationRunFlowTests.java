package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.artifact.SoftwareIntegrationArtifactPublisher;
import com.grdp.studio.softwareintegration.client.HttpWorkerRunClient.WorkerClientException;
import com.grdp.studio.softwareintegration.client.WorkerAvailability;
import com.grdp.studio.softwareintegration.client.WorkerRunAccepted;
import com.grdp.studio.softwareintegration.client.WorkerRunArtifact;
import com.grdp.studio.softwareintegration.client.WorkerRunClient;
import com.grdp.studio.softwareintegration.client.WorkerRunEvent;
import com.grdp.studio.softwareintegration.client.WorkerRunExecuteRequest;
import com.grdp.studio.softwareintegration.client.WorkerRunSnapshot;
import com.grdp.studio.softwareintegration.controller.SoftwareIntegrationController;
import com.grdp.studio.softwareintegration.controller.SoftwareIntegrationRunController;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationCreateRunRequest;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationProjectEntity;
import com.grdp.studio.softwareintegration.execution.PipesimWellResultValidator;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunDispatcher;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStatus;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStore;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelVersionMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationProjectMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationRunMapper;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationRunService;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationService;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationRunExceptionHandler;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationRunExceptionHandler.RunException;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationStorageKeyNormalizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(properties = "grdp.software-integration.dispatcher-enabled=false")
class SoftwareIntegrationRunFlowTests {
    private static final Path STORAGE_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "grdp-run-tests-" + UUID.randomUUID());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("grdp.software-integration.storage-root", STORAGE_ROOT::toString);
    }

    @BeforeAll
    static void createStorage() throws IOException { Files.createDirectories(STORAGE_ROOT); }

    @AfterAll
    static void removeStorage() { deleteTree(STORAGE_ROOT); }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SoftwareIntegrationProjectMapper projectMapper;
    @Autowired SoftwareIntegrationModelMapper modelMapper;
    @Autowired SoftwareIntegrationModelVersionMapper versionMapper;
    @Autowired SoftwareIntegrationRunMapper runMapper;
    @Autowired SoftwareIntegrationRunStore runStore;
    @Autowired SoftwareIntegrationRunService runService;
    @Autowired SoftwareIntegrationService softwareIntegrationService;
    @Autowired SoftwareIntegrationProperties integrationProperties;
    @Autowired SoftwareIntegrationStorageKeyNormalizer normalizer;
    @Autowired PipesimWellResultValidator resultValidator;
    @Autowired SoftwareIntegrationArtifactPublisher artifactPublisher;
    @Autowired ObjectMapper objectMapper;
    @Autowired FakeWorkerRunClient fakeWorker;

    private MockMvc mockMvc;

    @BeforeEach
    void reset() throws IOException {
        jdbcTemplate.execute("DELETE FROM software_integration_artifact");
        jdbcTemplate.execute("DELETE FROM software_integration_run_event");
        jdbcTemplate.execute("DELETE FROM software_integration_run");
        jdbcTemplate.execute("DELETE FROM software_integration_model_version");
        jdbcTemplate.execute("DELETE FROM software_integration_model");
        jdbcTemplate.execute("DELETE FROM software_integration_project");
        deleteTree(STORAGE_ROOT);
        Files.createDirectories(STORAGE_ROOT);
        fakeWorker.reset();
        mockMvc = MockMvcBuilders.standaloneSetup(new SoftwareIntegrationRunController(runService))
                .setControllerAdvice(new SoftwareIntegrationRunExceptionHandler()).build();
    }

    @Test
    void browserApiRequiresReadyExactStudyAndExplicitNullParameters() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", seed.version().getId())
                        .contentType("application/json")
                        .content("{\"study\":\"Study 1\",\"runType\":\"nodal\",\"parameters\":null}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.parameters").isEmpty());
        mockMvc.perform(get("/software-integration/model-versions/{id}/runs", seed.version().getId()).param("limit", "50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].status").value("QUEUED"));
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", seed.version().getId())
                        .contentType("application/json")
                        .content("{\"study\":\"Study 1\",\"runType\":\"nodal\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));

        SoftwareIntegrationCreateRunRequest wrongStudy = request("Study 1 ", "nodal");
        assertThatThrownBy(() -> runService.create(seed.version().getId(), wrongStudy)).isInstanceOf(RunException.class);
        seed.version().setStatus("INVALID");
        versionMapper.updateById(seed.version());
        assertThatThrownBy(() -> runService.create(seed.version().getId(), request("Study 1", "combined")))
                .isInstanceOf(RunException.class).satisfies(error ->
                        assertThat(((RunException) error).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void queueClaimIsUniqueAndCreationPersistsCreatedThenQueuedEvents() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long first = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        long second = runService.create(seed.version().getId(), request("Study 1", "profile")).id();
        assertThat(runStore.find(first).getParametersJson()).isEqualTo("null");
        assertThat(runStore.events(first)).extracting(event -> event.getStatus()).containsExactly("CREATED", "QUEUED");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> runStore.claimOldest("dispatcher-a"));
            var b = executor.submit(() -> runStore.claimOldest("dispatcher-b"));
            assertThat(java.util.stream.Stream.of(a.get(), b.get()).filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        }
        assertThat(runStore.find(first).getStatus()).isEqualTo("CLAIMED");
        assertThat(runStore.find(second).getStatus()).isEqualTo("QUEUED");
        runStore.acceptWorker(first, "worker-1", "generation-1");
        WorkerRunEvent claimedEvent = new WorkerRunEvent(1, "CLAIMED", Instant.now(), "claimed");
        runStore.persistWorkerEvents(first, List.of(claimedEvent));
        runStore.persistWorkerEvents(first, List.of(claimedEvent));
        assertThat(runStore.events(first).stream().filter(event -> Long.valueOf(1).equals(event.getWorkerSequence())).count())
                .isEqualTo(1);
    }

    @Test
    void fakeWorkerPartialResultEventsAndArtifactsArePersistedWithoutHttpTransaction() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "combined")).id();
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("CLAIMED");

        List<WorkerRunEvent> workerEvents = List.of(
                new WorkerRunEvent(1, "CLAIMED", Instant.now(), "claimed"),
                new WorkerRunEvent(2, "PREPARING", Instant.now(), "preparing"),
                new WorkerRunEvent(3, "RUNNING_NODAL", Instant.now(), "nodal"),
                new WorkerRunEvent(4, "RUNNING_PROFILE", Instant.now(), "profile"),
                new WorkerRunEvent(5, "COLLECTING", Instant.now(), "collecting"));

        Path output = STORAGE_ROOT.resolve("jobs/" + runId + "/output/raw.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "real worker artifact");
        Path manifest = output.getParent().resolve("manifest.json");
        Files.writeString(manifest, """
                {"schemaVersion":"grdp-worker-artifact-manifest/1","runId":%d,"generatedAtUtc":"2026-08-31T00:00:00Z","files":[
                  {"storageKey":"jobs/%d/output/raw.json","size":%d,"sha256":"%s","contentType":"application/json"}]}
                """.formatted(runId, runId, Files.size(output), sha256(output)));
        JsonNode profileError = objectMapper.readTree("{\"category\":\"EXECUTION\",\"code\":\"PROFILE_FAILED\",\"message\":\"profile failed\",\"retryable\":false}");
        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "SUCCEEDED", 5, "worker-1", "generation-1",
                workerEvents,
                partialResult(), profileError,
                List.of(
                        new WorkerRunArtifact("jobs/" + runId + "/output/raw.json",
                                Files.size(output), sha256(output), "application/json"),
                        new WorkerRunArtifact("jobs/" + runId + "/output/manifest.json",
                                Files.size(manifest), sha256(manifest), "application/json")),
                objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));
        dispatcher.poll();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("PARTIAL_SUCCEEDED");
        assertThat(runStore.find(runId).getResultContract()).isEqualTo("VALID_PARTIAL");
        assertThat(runService.get(runId).error().path("code").asText()).isEqualTo("PROFILE_FAILED");
        assertThat(runService.get(runId).error().size()).isEqualTo(4);
        assertThat(runStore.events(runId).stream().filter(event -> event.getWorkerSequence() != null).count()).isEqualTo(5);
        assertThat(runStore.find(runId).getArtifactManifestKey()).isEqualTo("artifacts/" + runId + "/manifest.json");
        assertThat(runStore.artifacts(runId).stream().filter(artifact -> artifact.getArtifactName().equals("raw.json")))
                .singleElement().satisfies(artifact -> {
            assertThat(artifact.getStorageKey()).isEqualTo("artifacts/" + runId + "/raw.json");
            assertThat(artifact.getSha256()).isEqualTo(sha256Unchecked(output));
        });
        assertThat(fakeWorker.transactionActiveDuringHttp).isFalse();
    }

    @Test
    void highPrecisionWorkerDoubleRoundTripsThroughPersistenceAndHttpExactly() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        assertThat(runStore.claimOldest("precision-dispatcher").getId()).isEqualTo(runId);
        runStore.acceptWorker(runId, "worker-1", "generation-1");
        runStore.transition(runId, SoftwareIntegrationRunStatus.PREPARING, null, "preparing", null);
        runStore.transition(runId, SoftwareIntegrationRunStatus.COLLECTING, null, "collecting", null);
        String exactResult = "{\"schemaVersion\":\"pipesim-well-result/1\",\"ipr\":[{\"flow\":110.84152977856141}]}";
        JsonNode result = objectMapper.readTree(exactResult);
        assertThat(runStore.complete(runId, SoftwareIntegrationRunStatus.SUCCEEDED, "VALID_COMPLETE", result,
                null, objectMapper.readTree("{\"processTreeExitConfirmed\":true}"), null)).isTrue();

        assertThat(runStore.find(runId).getResultJson()).isEqualTo(exactResult);
        assertThat(runService.get(runId).result().path("ipr").get(0).path("flow").toString())
                .isEqualTo("110.84152977856141");
        String body = mockMvc.perform(get("/software-integration/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"flow\":110.84152977856141");
        assertThat(objectMapper.readTree(body).path("data").path("result").path("ipr").get(0).path("flow").toString())
                .isEqualTo("110.84152977856141");
    }

    @Test
    void rejectedBeforeAcceptanceAndLostAfterAcceptanceHaveDistinctTerminalSemantics() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long before = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        JsonNode rejection = objectMapper.readTree("{\"category\":\"REQUEST\",\"code\":\"INVALID_RUN_TASK\",\"message\":\"invalid\",\"retryable\":false}");
        fakeWorker.executeError = new WorkerClientException("rejected", 422, rejection);
        dispatcher().dispatch();
        assertThat(runStore.find(before).getStatus()).isEqualTo("FAILED");
        assertThat(runStore.find(before).getErrorCode()).isEqualTo("INVALID_RUN_TASK");
        assertThat(runStore.find(before).getErrorCategory()).isEqualTo("REQUEST");

        long after = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        fakeWorker.executeError = null;
        fakeWorker.getError = new WorkerClientException("lost");
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();
        assertThat(runStore.find(after).getStartedAt()).isNotNull();
        assertThat(runStore.find(after).getDeadlineAt())
                .isEqualTo(runStore.find(after).getStartedAt().plusSeconds(600));
        dispatcher.poll();
        assertThat(runStore.find(after).getStatus()).isEqualTo("WORKER_LOST");
        assertThat(runStore.find(after).getErrorCode()).isEqualTo("WORKER_LOST");
    }

    @Test
    void responseLostButGetFindsRunAndPublishesFinalResult() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "combined")).id();
        Path output = STORAGE_ROOT.resolve("jobs/" + runId + "/output/raw.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "recovered-result");
        String outputSha = sha256(output);
        Path manifest = output.getParent().resolve("manifest.json");
        Files.writeString(manifest, """
                {"schemaVersion":"grdp-worker-artifact-manifest/1","runId":%d,"generatedAtUtc":"2026-08-31T00:00:00Z","files":[
                  {"storageKey":"jobs/%d/output/raw.json","size":%d,"sha256":"%s","contentType":"application/json"}]}
                """.formatted(runId, runId, Files.size(output), outputSha));
        fakeWorker.executeError = new WorkerClientException("response lost");
        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "PARTIAL_SUCCEEDED", 5, "worker-1", "generation-1",
                List.of(
                        new WorkerRunEvent(1, "CLAIMED", Instant.now(), "claimed"),
                        new WorkerRunEvent(2, "PREPARING", Instant.now(), "preparing"),
                        new WorkerRunEvent(3, "RUNNING_NODAL", Instant.now(), "nodal"),
                        new WorkerRunEvent(4, "RUNNING_PROFILE", Instant.now(), "profile"),
                        new WorkerRunEvent(5, "COLLECTING", Instant.now(), "collecting")),
                partialResult(), objectMapper.readTree("{\"category\":\"EXECUTION\",\"code\":\"PROFILE_FAILED\",\"message\":\"partial\",\"retryable\":false}"),
                List.of(
                        new WorkerRunArtifact("jobs/" + runId + "/output/raw.json", Files.size(output), outputSha, "application/json"),
                        new WorkerRunArtifact("jobs/" + runId + "/output/manifest.json", Files.size(manifest), sha256(manifest), "application/json")),
                objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("CLAIMED");
        assertThat(runStore.find(runId).getWorkerId()).isNull();
        assertThat(runStore.find(runId).getAcceptanceUncertainAt()).isNotNull();

        SoftwareIntegrationRunDispatcher recoveredDispatcher = dispatcher();
        recoveredDispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("PARTIAL_SUCCEEDED");
        assertThat(runStore.find(runId).getWorkerId()).isEqualTo("worker-1");
        assertThat(runStore.find(runId).getAcceptanceUncertainAt()).isNull();
        assertThat(runStore.find(runId).getResultJson()).contains("pipesim-well-result/1");
        assertThat(runStore.artifacts(runId)).hasSize(2);
    }

    @Test
    void transientWorkerBusySafelyRequeuesClaim() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        JsonNode busy = objectMapper.readTree("{\"category\":\"COORDINATION\",\"code\":\"WORKER_BUSY\",\"message\":\"busy\",\"retryable\":true}");
        fakeWorker.executeError = new WorkerClientException("busy", 409, busy);
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("QUEUED");
        assertThat(runStore.find(runId).getDispatcherId()).isNull();
        assertThat(runStore.find(runId).getClaimedAt()).isNull();
        assertThat(runStore.events(runId)).anySatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo("REQUEUED");
            assertThat(event.getErrorJson()).contains("WORKER_BUSY");
        });
        dispatcher.dispatch();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("QUEUED");
    }

    @Test
    void explicit404InSameGenerationSafelyRequeuesUncertainAcceptance() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        fakeWorker.executeError = new WorkerClientException("response lost");
        JsonNode missing = objectMapper.readTree("{\"category\":\"REQUEST\",\"code\":\"RUN_NOT_FOUND\",\"message\":\"missing\",\"retryable\":false}");
        fakeWorker.getError = new WorkerClientException("missing", 404, missing);
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();
        assertThat(runStore.find(runId).getAcceptanceUncertainAt()).isNotNull();

        dispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("QUEUED");
        assertThat(runStore.find(runId).getAcceptanceUncertainAt()).isNull();
        assertThat(runStore.events(runId)).anySatisfy(event ->
                assertThat(event.getMessage()).contains("404"));
    }

    @Test
    void uncertainAcceptanceCancellationWaitsForWorkerCleanupBeforeReleasingActiveSlot() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        fakeWorker.executeError = new WorkerClientException("execute accepted but response lost");
        dispatcher.dispatch();
        assertThat(runStore.find(runId).getAcceptanceUncertainAt()).isNotNull();

        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "PREPARING", 1, "worker-1", "generation-1",
                List.of(new WorkerRunEvent(1, "PREPARING", Instant.now(), "preparing")),
                null, null, List.of(), null);
        var cancellation = runService.cancel(runId);
        assertThat(cancellation.httpStatus()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(runStore.find(runId).getStatus()).isEqualTo("CANCEL_REQUESTED");
        assertThat(runStore.find(runId).getWorkerId()).isNull();
        assertThat(fakeWorker.cancelCalls).isEqualTo(1);

        long queuedBehindCancellation = runService.create(seed.version().getId(), request("Study 1", "profile")).id();
        dispatcher.dispatch();
        assertThat(runStore.find(queuedBehindCancellation).getStatus()).isEqualTo("QUEUED");

        dispatcher.poll();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("CANCEL_REQUESTED");
        assertThat(runStore.find(runId).getWorkerId()).isEqualTo("worker-1");
        assertThat(runStore.find(runId).getAcceptanceUncertainAt()).isNotNull();
        assertThat(runStore.find(queuedBehindCancellation).getStatus()).isEqualTo("QUEUED");
        assertThat(fakeWorker.cancelCalls).isGreaterThanOrEqualTo(2);

        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "CANCELLED", 2, "worker-1", "generation-1",
                List.of(new WorkerRunEvent(2, "CANCELLED", Instant.now(), "cancelled")),
                null, runStore.error("RUN_CANCELLED", "cancelled"), List.of(),
                objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));
        dispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("CANCELLED");
        assertThat(runService.get(runId).cleanup().path("processTreeExitConfirmed").asBoolean()).isTrue();
        assertThat(runStore.claimOldest("next-dispatcher").getId()).isEqualTo(queuedBehindCancellation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "PARTIAL_SUCCEEDED", "FAILED"})
    void uncertainCancellationDiscardsTerminalWorkerPayloadAfterCleanupConfirmation(String workerState) throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "combined")).id();
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        fakeWorker.executeError = new WorkerClientException("execute accepted but response lost");
        dispatcher.dispatch();

        JsonNode terminalConflict = objectMapper.readTree(
                "{\"category\":\"STATE\",\"code\":\"RUN_TERMINAL\",\"message\":\"terminal\",\"retryable\":false}");
        fakeWorker.cancelError = new WorkerClientException("terminal", 409, terminalConflict);
        runService.cancel(runId);
        long queuedBehindCancellation = runService.create(seed.version().getId(), request("Study 1", "profile")).id();
        assertThat(runStore.claimOldest("blocked-before-cleanup")).isNull();

        fakeWorker.snapshot = new WorkerRunSnapshot(runId, workerState, 1, "worker-1", "generation-1",
                List.of(), partialResult(),
                "FAILED".equals(workerState) ? runStore.error("WORKER_FAILED", "failed") : null,
                List.of(new WorkerRunArtifact("jobs/" + runId + "/output/must-not-publish.json",
                        10, "a".repeat(64), "application/json")),
                objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));
        dispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("CANCELLED");
        assertThat(runStore.find(runId).getResultContract()).isNull();
        assertThat(runStore.find(runId).getResultJson()).isNull();
        assertThat(runStore.find(runId).getArtifactManifestKey()).isNull();
        assertThat(runStore.artifacts(runId)).isEmpty();
        assertThat(runService.get(runId).cleanup().path("processTreeExitConfirmed").asBoolean()).isTrue();
        JsonNode cancellationError = runService.get(runId).error();
        assertThat(cancellationError.path("category").asText()).isEqualTo("CANCELLATION");
        assertThat(cancellationError.path("code").asText()).isEqualTo("RUN_CANCELLED");
        assertThat(cancellationError.path("retryable").asBoolean()).isFalse();
        assertThat(runStore.claimOldest("after-cleanup").getId()).isEqualTo(queuedBehindCancellation);
    }

    @Test
    void uncertainAcceptanceCancellationKeepsActiveSlotWhileNetworkIsUnknown() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        fakeWorker.executeError = new WorkerClientException("execute accepted but response lost");
        dispatcher.dispatch();

        fakeWorker.cancelError = new WorkerClientException("cancel response unknown");
        fakeWorker.getError = new WorkerClientException("worker network unknown");
        runService.cancel(runId);
        long queuedBehindCancellation = runService.create(seed.version().getId(), request("Study 1", "profile")).id();
        dispatcher.poll();
        dispatcher.dispatch();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("CANCEL_REQUESTED");
        assertThat(runStore.find(runId).getWorkerId()).isNull();
        assertThat(runStore.find(runId).getAcceptanceUncertainAt()).isNotNull();
        assertThat(runStore.find(queuedBehindCancellation).getStatus()).isEqualTo("QUEUED");
        assertThat(runStore.claimOldest("must-not-claim")).isNull();

        runMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity>()
                .eq(com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity::getId, runId)
                .set(com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity::getAcceptanceRecoveryDeadlineAt,
                        LocalDateTime.now().minusSeconds(1)));
        dispatcher.poll();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("WORKER_LOST");
        assertThat(runStore.find(queuedBehindCancellation).getStatus()).isEqualTo("QUEUED");
    }

    @Test
    void cancellationRacingExecuteAcceptancePersistsIdentityAndSendsWorkerCancel() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        fakeWorker.executeHook = ignored -> runService.cancel(runId);

        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("CANCEL_REQUESTED");
        assertThat(runStore.find(runId).getWorkerId()).isEqualTo("worker-1");
        assertThat(runStore.find(runId).getAcceptanceUncertainAt()).isNotNull();
        assertThat(fakeWorker.cancelCalls).isEqualTo(1);

        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "CANCELLED", 1, "worker-1", "generation-1",
                List.of(), null, runStore.error("RUN_CANCELLED", "cancelled"), List.of(),
                objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));
        dispatcher.poll();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void activeRunBlocksProjectDeletionWithScoped409() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        assertThat(runStore.claimOldest("delete-race-dispatcher").getId()).isEqualTo(runId);
        MockMvc projectMvc = MockMvcBuilders.standaloneSetup(new SoftwareIntegrationController(softwareIntegrationService))
                .setControllerAdvice(new SoftwareIntegrationRunExceptionHandler()).build();

        projectMvc.perform(delete("/software-integration/projects/{id}", seed.project().getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
        assertThat(projectMapper.selectById(seed.project().getId()).getDeletedAt()).isNull();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("CLAIMED");
    }

    @Test
    void cancelCasWinningDiscardsLaterWorkerSuccess() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "combined")).id();
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("CLAIMED");

        var cancellation = runService.cancel(runId);
        assertThat(cancellation.httpStatus()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(runStore.find(runId).getStatus()).isEqualTo("CANCEL_REQUESTED");

        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "SUCCEEDED", 0, "worker-1", "generation-1",
                List.of(), partialResult(), null,
                List.of(new WorkerRunArtifact("jobs/" + runId + "/output/normalized-result.json",
                        10, "a".repeat(64), "application/json")),
                objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));
        dispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("CANCELLED");
        assertThat(runStore.find(runId).getResultJson()).isNull();
        assertThat(runStore.find(runId).getResultContract()).isNull();
        assertThat(runStore.artifacts(runId)).isEmpty();
        JsonNode cancellationError = runService.get(runId).error();
        assertThat(cancellationError.path("category").asText()).isEqualTo("CANCELLATION");
        assertThat(cancellationError.path("code").asText()).isEqualTo("RUN_CANCELLED");
        assertThat(cancellationError.path("retryable").asBoolean()).isFalse();

        long cleanupFailureRunId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        SoftwareIntegrationRunDispatcher cleanupDispatcher = dispatcher();
        cleanupDispatcher.dispatch();
        runService.cancel(cleanupFailureRunId);
        JsonNode cleanupError = objectMapper.readTree("{\"category\":\"CLEANUP\",\"code\":\"PROCESS_TREE_EXIT_UNCONFIRMED\",\"message\":\"cleanup failed\",\"retryable\":false}");
        fakeWorker.snapshot = new WorkerRunSnapshot(cleanupFailureRunId, "FAILED", 0, "worker-1", "generation-1",
                List.of(), null, cleanupError, List.of(),
                objectMapper.readTree("{\"processTreeExitConfirmed\":false}"));
        cleanupDispatcher.poll();
        assertThat(runStore.find(cleanupFailureRunId).getStatus()).isEqualTo("FAILED");
        assertThat(runStore.find(cleanupFailureRunId).getErrorCategory()).isEqualTo("CLEANUP");
    }

    @Test
    void timeoutRequiresCleanupConfirmationAndRestartDoesNotRetry() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();
        runMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity>()
                .eq(com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity::getId, runId)
                .set(com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity::getDeadlineAt, LocalDateTime.now().minusSeconds(1)));
        dispatcher.poll();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("CANCEL_REQUESTED");
        assertThat(fakeWorker.cancelCalled).isTrue();
        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "CANCELLED", 0, "worker-1", "generation-1", List.of(), null,
                runStore.error("TIMEOUT", "timed out"), List.of(), objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));
        dispatcher.poll();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("TIMED_OUT");
        assertThat(runService.get(runId).cleanup().path("processTreeExitConfirmed").asBoolean()).isTrue();

        long interrupted = runService.create(seed.version().getId(), request("Study 1", "profile")).id();
        runStore.claimOldest("old-instance");
        runStore.recoverOnStartup();
        assertThat(runStore.find(interrupted).getStatus()).isEqualTo("WORKER_LOST");

        long unclean = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        SoftwareIntegrationRunDispatcher uncleanDispatcher = dispatcher();
        uncleanDispatcher.dispatch();
        runMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity>()
                .eq(com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity::getId, unclean)
                .set(com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity::getDeadlineAt, LocalDateTime.now().minusSeconds(1)));
        uncleanDispatcher.poll();
        fakeWorker.snapshot = new WorkerRunSnapshot(unclean, "CANCELLED", 0, "worker-1", "generation-1",
                List.of(), null, null, List.of(), objectMapper.readTree("{\"processTreeExitConfirmed\":false}"));
        uncleanDispatcher.poll();
        assertThat(runStore.find(unclean).getStatus()).isEqualTo("WORKER_LOST");
    }

    @Test
    void workerCancelRequestedThenTimedOutKeepsSlotUntilCleanupConfirmed() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();
        runMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity>()
                .eq(com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity::getId, runId)
                .set(com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity::getDeadlineAt,
                        LocalDateTime.now().minusSeconds(1)));
        dispatcher.poll();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("CANCEL_REQUESTED");
        assertThat(runStore.find(runId).getCancellationReason()).isEqualTo("TIMEOUT");

        long queuedBehindTimeout = runService.create(seed.version().getId(), request("Study 1", "profile")).id();
        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "CANCEL_REQUESTED", 1, "worker-1", "generation-1",
                List.of(new WorkerRunEvent(1, "CANCEL_REQUESTED", Instant.now(), "cleanup in progress")),
                null, null, List.of(), null);
        dispatcher.poll();
        dispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("CANCEL_REQUESTED");
        assertThat(runStore.find(runId).getErrorCode()).isEqualTo("TIMEOUT");
        assertThat(runStore.find(queuedBehindTimeout).getStatus()).isEqualTo("QUEUED");
        assertThat(runStore.claimOldest("blocked-during-timeout-cleanup")).isNull();
        assertThat(runStore.events(runId).stream()
                .filter(event -> Long.valueOf(1).equals(event.getWorkerSequence())).count()).isEqualTo(1);

        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "TIMED_OUT", 2, "worker-1", "generation-1",
                List.of(new WorkerRunEvent(2, "TIMED_OUT", Instant.now(), "cleanup complete")),
                null, runStore.error("TIMEOUT", "timed out"), List.of(),
                objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));
        dispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("TIMED_OUT");
        assertThat(runStore.find(runId).getStatus()).isNotEqualTo("WORKER_LOST");
        assertThat(runService.get(runId).cleanup().path("processTreeExitConfirmed").asBoolean()).isTrue();
        assertThat(runService.get(runId).error().path("category").asText()).isEqualTo("TIMEOUT");
        assertThat(runService.get(runId).error().path("code").asText()).isEqualTo("TIMEOUT");
        assertThat(runStore.claimOldest("after-timeout-cleanup").getId()).isEqualTo(queuedBehindTimeout);
    }

    @Test
    void workerCancelRequestedDuringUserCancellationKeepsFollowingRunQueued() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();
        runService.cancel(runId);
        long queuedBehindCancellation = runService.create(seed.version().getId(), request("Study 1", "profile")).id();

        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "CANCEL_REQUESTED", 1, "worker-1", "generation-1",
                List.of(new WorkerRunEvent(1, "CANCEL_REQUESTED", Instant.now(), "cleanup in progress")),
                null, null, List.of(), null);
        dispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("CANCEL_REQUESTED");
        assertThat(runStore.find(runId).getCancellationReason()).isEqualTo("USER");
        assertThat(runStore.find(queuedBehindCancellation).getStatus()).isEqualTo("QUEUED");
        assertThat(runStore.claimOldest("blocked-during-user-cleanup")).isNull();

        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "CANCELLED", 2, "worker-1", "generation-1",
                List.of(new WorkerRunEvent(2, "CANCELLED", Instant.now(), "cleanup complete")),
                null, runStore.error("RUN_CANCELLED", "cancelled"), List.of(),
                objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));
        dispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("CANCELLED");
        assertThat(runService.get(runId).cleanup().path("processTreeExitConfirmed").asBoolean()).isTrue();
        assertThat(runStore.claimOldest("after-user-cleanup").getId()).isEqualTo(queuedBehindCancellation);
    }

    @Test
    void cancelIsAtomicAndAbsoluteStorageKeyMigrationIsControlled() throws Exception {
        Path absolute = STORAGE_ROOT.resolve("models/legacy/model.pips").toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Files.writeString(absolute, "legacy");
        Seed seed = seed("READY", absolute.toString());
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        assertThat(versionMapper.selectById(seed.version().getId()).getStorageKey()).isEqualTo("models/legacy/model.pips");
        var cancelled = runService.cancel(runId);
        assertThat(cancelled.httpStatus()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.run().status()).isEqualTo("CANCELLED");
        assertThat(runService.cancel(runId).httpStatus()).isEqualTo(HttpStatus.OK);

        Path outside = STORAGE_ROOT.resolve("../outside-model.pips").toAbsolutePath().normalize();
        Seed outsideSeed = seed("READY", outside.toString());
        assertThatThrownBy(() -> runService.create(outsideSeed.version().getId(), request("Study 1", "nodal")))
                .isInstanceOf(RunException.class).satisfies(error ->
                        assertThat(((RunException) error).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    private SoftwareIntegrationRunDispatcher dispatcher() {
        SoftwareIntegrationRunDispatcher dispatcher = new SoftwareIntegrationRunDispatcher(
                runStore, versionMapper, normalizer, fakeWorker, resultValidator, artifactPublisher, integrationProperties);
        dispatcher.recover();
        return dispatcher;
    }

    private Seed seed(String status, String storageKey) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        SoftwareIntegrationProjectEntity project = new SoftwareIntegrationProjectEntity();
        project.setName("project-" + UUID.randomUUID());
        project.setCreatedBy("administrator");
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        projectMapper.insert(project);
        SoftwareIntegrationModelEntity model = new SoftwareIntegrationModelEntity();
        model.setProjectId(project.getId());
        model.setName("model-" + UUID.randomUUID());
        model.setSimulatorType("PIPESIM_WELL");
        model.setCreatedAt(now);
        model.setUpdatedAt(now);
        modelMapper.insert(model);
        SoftwareIntegrationModelVersionEntity version = new SoftwareIntegrationModelVersionEntity();
        version.setModelId(model.getId());
        version.setVersionNo(1);
        version.setOriginalName("model.pips");
        version.setStorageKey(storageKey);
        version.setSha256("a".repeat(64));
        version.setSizeBytes(5L);
        version.setStatus(status);
        version.setStudiesJson("Study 1\nStudy 2");
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        versionMapper.insert(version);
        if (!Path.of(storageKey).isAbsolute()) {
            Path modelFile = STORAGE_ROOT.resolve(storageKey);
            Files.createDirectories(modelFile.getParent());
            Files.writeString(modelFile, "model");
        }
        return new Seed(project, model, version);
    }

    private SoftwareIntegrationCreateRunRequest request(String study, String runType) {
        SoftwareIntegrationCreateRunRequest request = new SoftwareIntegrationCreateRunRequest();
        request.setStudy(study);
        request.setRunType(runType);
        request.setParameters(null);
        return request;
    }

    private JsonNode partialResult() {
        return objectMapper.readTree("""
                {"schemaVersion":"pipesim-well-result/1","model_kind":"black_oil_liquid","runTask":"combined","resultContract":"VALID_PARTIAL",
                 "units":{"flow":{"displayUnit":null,"semantics":"unspecified"},"pressure":{"displayUnit":null,"semantics":"unspecified"},
                          "depth":{"displayUnit":null,"semantics":"unspecified"},"temperature":{"displayUnit":null,"semantics":"unspecified"}},
                 "ipr":[{"flow":1.0,"pressure":2.0}],"vlp":[{"flow":1.0,"pressure":2.0}],"profile":[]}
                """);
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String sha256Unchecked(Path file) {
        try { return sha256(file); } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private record Seed(SoftwareIntegrationProjectEntity project, SoftwareIntegrationModelEntity model,
                        SoftwareIntegrationModelVersionEntity version) {}

    @TestConfiguration
    static class FakeWorkerConfiguration {
        @Bean
        @Primary
        FakeWorkerRunClient fakeWorkerRunClient() { return new FakeWorkerRunClient(); }
    }

    static class FakeWorkerRunClient implements WorkerRunClient {
        WorkerClientException executeError;
        WorkerClientException getError;
        WorkerClientException cancelError;
        LongConsumer executeHook;
        boolean cancelCalled;
        int cancelCalls;
        boolean transactionActiveDuringHttp;
        WorkerRunSnapshot snapshot;

        void reset() {
            executeError = null;
            getError = null;
            cancelError = null;
            executeHook = null;
            cancelCalled = false;
            cancelCalls = 0;
            transactionActiveDuringHttp = false;
            snapshot = new WorkerRunSnapshot(-1, "PREPARING", 0, "worker-1", "generation-1",
                    List.of(), null, null, List.of(), null);
        }

        @Override
        public WorkerAvailability availability() {
            observeTransaction();
            return new WorkerAvailability(true, "generation-1");
        }

        @Override
        public WorkerRunAccepted execute(WorkerRunExecuteRequest request) {
            observeTransaction();
            if (executeHook != null) executeHook.accept(request.runId());
            if (executeError != null) throw executeError;
            assertThat(request.modelStorageKey()).doesNotMatch("^[A-Za-z]:.*");
            assertThat(request.parameters()).isNull();
            assertThat(request.timeoutSeconds()).isEqualTo(600);
            return new WorkerRunAccepted(request.runId(), "CLAIMED", "worker-1", "generation-1");
        }

        @Override
        public WorkerRunSnapshot get(long runId, long afterSequence) {
            observeTransaction();
            if (getError != null) throw getError;
            if (snapshot.runId() < 0) {
                return new WorkerRunSnapshot(runId, snapshot.state(), snapshot.lastSequence(), snapshot.workerId(),
                        snapshot.generationId(), snapshot.events(), snapshot.result(), snapshot.error(),
                        snapshot.artifacts(), snapshot.cleanup());
            }
            return snapshot;
        }

        @Override
        public void cancel(long runId) {
            observeTransaction();
            cancelCalled = true;
            cancelCalls++;
            if (cancelError != null) throw cancelError;
        }

        private void observeTransaction() {
            transactionActiveDuringHttp |= TransactionSynchronizationManager.isActualTransactionActive();
        }
    }
}
