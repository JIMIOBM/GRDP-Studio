package com.grdp.studio.softwareintegration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.softwareintegration.artifact.SoftwareIntegrationArtifactPublisher;
import com.grdp.studio.softwareintegration.client.HttpWorkerRunClient.WorkerClientException;
import com.grdp.studio.softwareintegration.client.WorkerAvailability;
import com.grdp.studio.softwareintegration.client.WorkerRunAccepted;
import com.grdp.studio.softwareintegration.client.WorkerRunArtifact;
import com.grdp.studio.softwareintegration.client.WorkerRunClient;
import com.grdp.studio.softwareintegration.client.WorkerRunEvent;
import com.grdp.studio.softwareintegration.client.WorkerRunExecuteRequest;
import com.grdp.studio.softwareintegration.client.WorkerRunSnapshot;
import com.grdp.studio.softwareintegration.client.WorkerEclipseCapability;
import com.grdp.studio.softwareintegration.controller.SoftwareIntegrationController;
import com.grdp.studio.softwareintegration.controller.SoftwareIntegrationRunController;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationCreateRunRequest;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationProjectEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationArtifactEntity;
import com.grdp.studio.softwareintegration.execution.PipesimWellResultValidator;
import com.grdp.studio.softwareintegration.execution.PipesimResultValidator;
import com.grdp.studio.softwareintegration.execution.EclipseSummaryResultValidator;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunDispatcher;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStatus;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStore;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelVersionMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationProjectMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationRunMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationArtifactMapper;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationRunService;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationService;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationCapabilityService;
import com.grdp.studio.softwareintegration.service.impl.SoftwareIntegrationServiceImpl;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationArtifactCleanup;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProjectCleanup;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationResultCleanup;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationRunExceptionHandler;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationRunExceptionHandler.RunException;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationStorageKeyNormalizer;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationValidationDispatcher;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    @Autowired SoftwareIntegrationArtifactMapper artifactMapper;
    @Autowired SoftwareIntegrationRunStore runStore;
    @Autowired SoftwareIntegrationRunService runService;
    @Autowired com.grdp.studio.softwareintegration.service.EclipseBatchService eclipseBatches;
    @Autowired com.grdp.studio.softwareintegration.service.EclipseHistoryService eclipseHistory;
    @Autowired SoftwareIntegrationService softwareIntegrationService;
    @Autowired SoftwareIntegrationProperties integrationProperties;
    @Autowired SoftwareIntegrationStorageKeyNormalizer normalizer;
    @Autowired PipesimResultValidator resultValidator;
    @Autowired EclipseSummaryResultValidator eclipseResultValidator;
    @Autowired SoftwareIntegrationArtifactPublisher artifactPublisher;
    @Autowired SoftwareIntegrationArtifactCleanup artifactCleanup;
    @Autowired SoftwareIntegrationResultCleanup resultCleanup;
    @Autowired SoftwareIntegrationProjectCleanup projectCleanup;
    @Autowired SoftwareIntegrationCapabilityService capabilityService;
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
        for (String invalidStudy : List.of(
                "{\"runType\":\"nodal\",\"parameters\":null}",
                "{\"study\":null,\"runType\":\"nodal\",\"parameters\":null}",
                "{\"study\":\"\",\"runType\":\"nodal\",\"parameters\":null}")) {
            mockMvc.perform(post("/software-integration/model-versions/{id}/runs", seed.version().getId())
                            .contentType("application/json").content(invalidStudy))
                    .andExpect(status().isBadRequest());
        }

        SoftwareIntegrationCreateRunRequest wrongStudy = request("Study 1 ", "nodal");
        assertThatThrownBy(() -> runService.create(seed.version().getId(), wrongStudy)).isInstanceOf(RunException.class);
        seed.version().setStatus("INVALID");
        versionMapper.updateById(seed.version());
        assertThatThrownBy(() -> runService.create(seed.version().getId(), request("Study 1", "combined")))
                .isInstanceOf(RunException.class).satisfies(error ->
                        assertThat(((RunException) error).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void retryCreatesNewQueuedRunAndPreservesOriginalAuditRecord() throws IOException {
        Seed seed = seed("READY", "models/retry/model.pips");
        var request = request("Study 1", "nodal");
        request.setParameters(java.util.Map.of("schemaVersion", "pipesim-well-parameters/1", "reservoirPressurePsi", 4200));
        var original = runService.create(seed.version().getId(), request);
        assertThat(runStore.claimOldest("retry-test").getId()).isEqualTo(original.id());
        JsonNode workerUnavailable = runStore.error("WORKER_UNREACHABLE", "Worker unavailable");
        runStore.transition(original.id(), SoftwareIntegrationRunStatus.FAILED, patch -> {
            patch.setErrorCode("WORKER_UNREACHABLE");
            patch.setErrorJson(workerUnavailable.toString());
        }, "Worker unavailable", workerUnavailable);

        var retried = runService.retry(original.id());

        assertThat(retried.id()).isNotEqualTo(original.id());
        assertThat(retried.status()).isEqualTo("QUEUED");
        assertThat(retried.modelVersionId()).isEqualTo(original.modelVersionId());
        assertThat(retried.study()).isEqualTo(original.study());
        assertThat(retried.runType()).isEqualTo(original.runType());
        assertThat(retried.parameters()).isEqualTo(original.parameters());
        assertThat(runStore.find(original.id()).getStatus()).isEqualTo("FAILED");
        assertThat(runStore.find(original.id()).getErrorCode()).isEqualTo("WORKER_UNREACHABLE");
    }

    @Test
    void workerLostRunCanBeRetriedWithoutMutatingOriginalHistory() throws IOException {
        Seed seed = seed("READY", "models/retry-terminal-states/model.pips");
        var original = runService.create(seed.version().getId(), request("Study 1", "profile"));
        assertThat(runStore.claimOldest("retry-terminal-state").getId()).isEqualTo(original.id());
        JsonNode terminalError = runStore.error("WORKER_LOST", "Worker 状态已丢失");
        runStore.transition(original.id(), SoftwareIntegrationRunStatus.WORKER_LOST, patch -> {
            patch.setErrorCode("WORKER_LOST");
            patch.setErrorJson(terminalError.toString());
        }, "Worker 状态已丢失", terminalError);

        var retried = runService.retry(original.id());

        assertThat(retried.id()).isNotEqualTo(original.id());
        assertThat(retried.status()).isEqualTo("QUEUED");
        assertThat(retried.modelVersionId()).isEqualTo(original.modelVersionId());
        assertThat(retried.study()).isEqualTo(original.study());
        assertThat(retried.runType()).isEqualTo(original.runType());
        assertThat(runStore.find(original.id()).getStatus()).isEqualTo("WORKER_LOST");
        assertThat(runStore.find(original.id()).getErrorCode()).isEqualTo("WORKER_LOST");
    }

    @Test
    void timedOutRunCanBeRetriedAfterWorkerCleanupConfirmation() throws Exception {
        Seed seed = seed("READY", "models/retry-timeout/model.pips");
        var original = runService.create(seed.version().getId(), request("Study 1", "profile"));
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();
        runMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity>()
                .eq(com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity::getId, original.id())
                .set(com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity::getDeadlineAt, LocalDateTime.now().minusSeconds(1)));
        dispatcher.poll();
        assertThat(runStore.find(original.id()).getStatus()).isEqualTo("CANCEL_REQUESTED");
        fakeWorker.snapshot = new WorkerRunSnapshot(original.id(), "TIMED_OUT", 1, "worker-1", "generation-1",
                List.of(new WorkerRunEvent(1, "TIMED_OUT", Instant.now(), "cleanup complete")), null,
                runStore.error("TIMEOUT", "运行已超时"), List.of(),
                objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));
        dispatcher.poll();

        var retried = runService.retry(original.id());

        assertThat(runStore.find(original.id()).getStatus()).isEqualTo("TIMED_OUT");
        assertThat(retried.id()).isNotEqualTo(original.id());
        assertThat(retried.status()).isEqualTo("QUEUED");
        assertThat(retried.modelVersionId()).isEqualTo(original.modelVersionId());
        assertThat(retried.runType()).isEqualTo(original.runType());
    }

    @Test
    void retryRejectsNonRetryableFailure() throws IOException {
        Seed seed = seed("READY", "models/non-retryable/model.pips");
        var original = runService.create(seed.version().getId(), request("Study 1", "nodal"));
        assertThat(runStore.claimOldest("retry-test").getId()).isEqualTo(original.id());
        JsonNode invalidResult = runStore.error("RESULT_CONTRACT_INVALID", "Result contract invalid");
        runStore.transition(original.id(), SoftwareIntegrationRunStatus.FAILED, patch -> {
            patch.setErrorCode("RESULT_CONTRACT_INVALID");
            patch.setErrorJson(invalidResult.toString());
        }, "Result contract invalid", invalidResult);

        assertThatThrownBy(() -> runService.retry(original.id()))
                .isInstanceOf(RunException.class)
                .satisfies(error -> assertThat(((RunException) error).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void wellScenarioIsPersistedReturnedAndDispatchedWithoutChangingBaseline() throws Exception {
        Seed seed = seed("READY", "models/scenario/model.pips");
        var request = request("Study 1", "nodal");
        request.setParameters(java.util.Map.of("schemaVersion", "pipesim-well-parameters/1", "reservoirPressurePsi", 4000));
        var created = runService.create(seed.version().getId(), request);
        assertThat(created.parameters().path("reservoirPressurePsi").asDouble()).isEqualTo(4000);
        assertThat(runService.get(created.id()).parameters()).isEqualTo(created.parameters());
        assertThat(runStore.find(created.id()).getParametersJson()).contains("reservoirPressurePsi");
        dispatcher().dispatch();
        assertThat(fakeWorker.lastExecuteRequest.parameters()).isEqualTo(created.parameters());
        for (String task : List.of("profile", "combined", "network", "eclipse")) {
            request.setRunType(task);
            assertThatThrownBy(() -> runService.create(seed.version().getId(), request)).isInstanceOf(RunException.class);
        }
    }

    @Test
    void eclipseBrowserRequestRequiresEveryFrozenNullFieldAndAvailableCapability() throws Exception {
        Seed eclipse = seed("READY", "models/eclipse/1/CASE.DATA", "ECLIPSE_100");

        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                        .contentType("application/json")
                        .content("{\"study\":null,\"runType\":\"eclipse\",\"parameters\":null}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.study").isEmpty())
                .andExpect(jsonPath("$.data.runType").value("eclipse"));
        var persisted = runStore.listByVersion(eclipse.version().getId(), 10).get(0);
        assertThat(persisted.getStudyName()).isNull();
        assertThat(persisted.getParametersJson()).isEqualTo("null");
        assertThat(persisted.getTimeoutSeconds()).isEqualTo(1800);

        for (String invalidBody : List.of(
                "{\"runType\":\"eclipse\",\"parameters\":null}",
                "{\"study\":\"\",\"runType\":\"eclipse\",\"parameters\":null}",
                "{\"study\":\"Study 1\",\"runType\":\"eclipse\",\"parameters\":null}",
                "{\"study\":null,\"runType\":\"ECLIPSE\",\"parameters\":null}",
                "{\"study\":null,\"runType\":\"eclipse\"}",
                "{\"study\":null,\"runType\":\"eclipse\",\"parameters\":{}}",
                "{\"study\":null,\"runType\":\"eclipse\",\"parameters\":null,\"executable\":\"eclrun\"}")) {
            mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                            .contentType("application/json").content(invalidBody))
                    .andExpect(status().isBadRequest());
        }

        int before = runStore.listByVersion(eclipse.version().getId(), 100).size();
        fakeWorker.eclipseCapability = new WorkerEclipseCapability(
                null, "UNAVAILABLE", "ECLIPSE_UNAVAILABLE", List.of(), 1800);
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                        .contentType("application/json")
                        .content("{\"study\":null,\"runType\":\"eclipse\",\"parameters\":null}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
        assertThat(runStore.listByVersion(eclipse.version().getId(), 100)).hasSize(before);
        assertThatThrownBy(() -> runService.create(eclipse.version().getId(), request(null, "eclipse")))
                .isInstanceOf(RunException.class)
                .satisfies(error -> assertThat(((RunException) error).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(runStore.listByVersion(eclipse.version().getId(), 100)).hasSize(before);

        fakeWorker.eclipseCapability = new WorkerEclipseCapability(
                "2024.1", "AVAILABLE", null, List.of("eclipse"), 1800);
        eclipse.version().setStudiesJson("Study 1");
        versionMapper.updateById(eclipse.version());
        assertThatThrownBy(() -> runService.create(eclipse.version().getId(), request(null, "eclipse")))
                .isInstanceOf(RunException.class)
                .satisfies(error -> assertThat(((RunException) error).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"eclipse-compositional-inspection.json", "eclipse-thermal-inspection.json", "eclipse-compositional-reinjection-inspection.json", "eclipse-compositional-fluid-property-inspection.json"})
    void e300RequiresOwnCapabilityAndRejectsLegacyEditsBeforeCreatingRun(String resource) throws Exception {
        Seed eclipse = seed("READY", resource.contains("thermal") ? "models/eclipse/e300/THERM3.DATA" : "models/eclipse/e300/SPE3_GASCOND.DATA", "ECLIPSE_100");
        eclipse.version().setModelKind("eclipse_300");
        eclipse.model().setSimulatorType("ECLIPSE_300");
        modelMapper.updateById(eclipse.model());
        eclipse.version().setOriginalName("official.zip");
        try (var input = getClass().getResourceAsStream("/" + resource)) {
            eclipse.version().setInspectionJson(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        versionMapper.updateById(eclipse.version());
        String normal = "{\"study\":null,\"runType\":\"eclipse\",\"parameters\":null}";
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                .contentType("application/json").content(normal)).andExpect(status().isServiceUnavailable());
        assertThat(runStore.listByVersion(eclipse.version().getId(), 100)).isEmpty();
        fakeWorker.compositionalAvailable = true;
        fakeWorker.eclipseCapability = new WorkerEclipseCapability(null, "UNAVAILABLE", "ECLIPSE_UNAVAILABLE", List.of(), 1800);
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                .contentType("application/json").content("{\"study\":null,\"runType\":\"eclipse\",\"parameters\":{}}"))
                .andExpect(status().isBadRequest());
        assertThat(runStore.listByVersion(eclipse.version().getId(), 100)).isEmpty();
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                .contentType("application/json").content(normal)).andExpect(status().isCreated());
        assertThat(runStore.listByVersion(eclipse.version().getId(), 100)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WCONPROD", "WCONINJE"})
    void eclipseBhpHttpBindingSurvivesMapSerializationAndRejectsStalePressure(String keyword) throws Exception {
        Seed eclipse = seed("READY", "models/eclipse/bhp/CASE.DATA", "ECLIPSE_100");
        String inspection = """
          {"schemaVersion":"eclipse-data-inspection/4","caseName":"CASE.DATA","sections":["RUNSPEC","SCHEDULE"],
           "unitSystem":"FIELD","phases":["OIL","WATER"],"dimensions":{"nx":1,"ny":1,"nz":1},"wellNames":["P1","I1"],
           "packageFiles":[{"relativePath":"CASE.DATA","sizeBytes":5,"sha256":"%s"}],"scheduleTimeline":[],
           "scheduleMetadata":{"wells":[{"name":"P1","group":"G","sourceFile":"CASE.DATA","lineNumber":3},
             {"name":"I1","group":"G","sourceFile":"CASE.DATA","lineNumber":4}],"groups":[],"completions":[],"records":[
             {"keyword":"WCONPROD","sourceFile":"CASE.DATA","lineNumber":5,"values":["P1","OPEN","BHP","5*","3000"]},
             {"keyword":"WCONINJE","sourceFile":"CASE.DATA","lineNumber":8,"values":["I1","WATER","OPEN","RESV","1*","1000","6000"]}]}}
          """.formatted("a".repeat(64));
        eclipse.version().setInspectionJson(inspection);
        versionMapper.updateById(eclipse.version());
        var base = runService.create(eclipse.version().getId(), request(null, "eclipse"));
        var baseline = runStore.find(base.id());
        baseline.setStatus("SUCCEEDED"); baseline.setResultContract("VALID_FULL"); baseline.setParametersJson("null");
        runMapper.updateById(baseline);
        boolean injection = keyword.equals("WCONINJE");
        var p = new java.util.HashMap<String, Object>(Map.ofEntries(
            Map.entry("schemaVersion", "eclipse-pressure-control-parameters/1"), Map.entry("baselineRunId", baseline.getId()),
            Map.entry("well", injection ? "I1" : "P1"), Map.entry("phase", "INITIAL"), Map.entry("sourceFile", "CASE.DATA"),
            Map.entry("lineNumber", injection ? 8 : 5), Map.entry("keyword", keyword), Map.entry("originalControlMode", injection ? "RESV" : "BHP"),
            Map.entry("originalBhp", injection ? 6000 : 3000), Map.entry("targetBhp", injection ? 5000 : 3500), Map.entry("unit", "psia"),
            Map.entry("injectionType", injection ? "WATER" : "NONE")));
        // Application non_null serialization must not lose a required production field.
        assertThat(objectMapper.valueToTree(p).size()).isEqualTo(12);
        var bad = new java.util.HashMap<>(p); bad.put("originalBhp", 2900);
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
            .contentType("application/json").content("{\"study\":null,\"runType\":\"eclipse\",\"parameters\":" + objectMapper.writeValueAsString(bad) + "}"))
            .andExpect(status().isBadRequest());
        assertThat(runStore.listByVersion(eclipse.version().getId(), 10)).hasSize(1);
        String body = "{\"study\":null,\"runType\":\"eclipse\",\"parameters\":" + objectMapper.writeValueAsString(p) + "}";
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId()).contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.parameters.keyword").value(keyword))
            .andExpect(jsonPath("$.data.parameters.originalBhp").value(injection ? 6000 : 3000))
            .andExpect(jsonPath("$.data.parameters.injectionType").value(injection ? "WATER" : "NONE"));
        assertThat(runStore.listByVersion(eclipse.version().getId(), 10)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WRAT", "GRAT", "LRAT"})
    void eclipseProductionControlsBindExpandedTargetsAndRejectStaleOriginal(String mode) throws Exception {
        Seed eclipse = seed("READY", "models/eclipse/rates/CASE.DATA", "ECLIPSE_100");
        String defaults = mode.equals("WRAT") ? "1*" : mode.equals("GRAT") ? "2*" : "3*";
        eclipse.version().setInspectionJson("""
          {"schemaVersion":"eclipse-data-inspection/4","caseName":"CASE.DATA","unitSystem":"METRIC",
           "sections":["RUNSPEC","SCHEDULE"],"phases":["OIL","WATER","GAS"],"dimensions":{"nx":1,"ny":1,"nz":1},
           "wellNames":["P1"],"packageFiles":[{"relativePath":"CASE.DATA","sizeBytes":5,"sha256":"%s"}],
           "scheduleTimeline":[{"kind":"TSTEP","steps":["5"],"sourceFile":"CASE.DATA","lineNumber":10}],
           "scheduleMetadata":{"wells":[{"name":"P1","group":"G","sourceFile":"CASE.DATA","lineNumber":3}],
           "groups":[],"completions":[],"records":[{"keyword":"WCONPROD","sourceFile":"CASE.DATA","lineNumber":5,
           "values":["P1","OPEN","%s","%s","1000"]}]}}
          """.formatted("a".repeat(64), mode, defaults));
        versionMapper.updateById(eclipse.version());
        var base = runService.create(eclipse.version().getId(), request(null, "eclipse"));
        var baseline = runStore.find(base.id());
        baseline.setStatus("SUCCEEDED"); baseline.setResultContract("VALID_FULL"); baseline.setParametersJson("null");
        runMapper.updateById(baseline);
        var p = new java.util.HashMap<String, Object>(Map.ofEntries(
            Map.entry("schemaVersion", "eclipse-production-control-parameters/1"), Map.entry("baselineRunId", baseline.getId()),
            Map.entry("well", "P1"), Map.entry("phase", "INITIAL"), Map.entry("sourceFile", "CASE.DATA"),
            Map.entry("lineNumber", 5), Map.entry("controlMode", mode), Map.entry("originalTargetRate", 1000),
            Map.entry("targetRate", 500), Map.entry("unit", "sm3/day")));
        assertThat(com.grdp.studio.softwareintegration.support.EclipseProductionControlParameters.valid(objectMapper.valueToTree(p))).isTrue();
        var persisted = com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator.parsePersisted(eclipse.version().getInspectionJson());
        assertThat(persisted).isNotNull();
        assertThat(com.grdp.studio.softwareintegration.support.EclipseProductionControlParameters.matchesInspection(objectMapper.valueToTree(p), persisted)).isTrue();
        for (String fault : new String[]{"originalTargetRate", "unit", "lineNumber"}) {
            var bad = new java.util.HashMap<>(p); bad.put(fault, fault.equals("unit") ? "STB/day" : 999);
            mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                .contentType("application/json").content("{\"study\":null,\"runType\":\"eclipse\",\"parameters\":" + objectMapper.writeValueAsString(bad) + "}"))
                .andExpect(status().isBadRequest());
        }
        assertThat(runStore.listByVersion(eclipse.version().getId(), 10)).hasSize(1);
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
            .contentType("application/json").content("{\"study\":null,\"runType\":\"eclipse\",\"parameters\":" + objectMapper.writeValueAsString(p) + "}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.parameters.controlMode").value(mode))
            .andExpect(jsonPath("$.data.parameters.originalTargetRate").value(1000))
            .andExpect(jsonPath("$.data.parameters.unit").value("sm3/day"));
        assertThat(runStore.listByVersion(eclipse.version().getId(), 10)).hasSize(2);
    }

    @Test
    void eclipseGridFieldRunRequiresAndPersistsAnExactFullBaselineBinding() throws Exception {
        Seed eclipse = seed("READY", "models/eclipse/grid-field/CASE.DATA", "ECLIPSE_100");
        var baselineSummary = runService.create(eclipse.version().getId(), request(null, "eclipse"));
        var baseline = runStore.find(baselineSummary.id());
        String sha = "b".repeat(64);
        baseline.setStatus("SUCCEEDED");
        baseline.setResultContract("VALID_FULL");
        baseline.setParametersJson("null");
        baseline.setResultJson("""
                {"schemaVersion":"eclipse-summary-result/1","resultContract":"VALID_FULL",
                 "grid":{"nx":2,"ny":2,"nz":1},
                 "fieldIndex":{"files":[{"name":"CASE.INIT","fields":[
                   {"keyword":"PERMX","dataType":"REAL","count":4}]}]},
                 "outputFiles":[{"name":"CASE.INIT","sha256":"%s"}]}
                """.formatted(sha));
        runMapper.updateById(baseline);
        SoftwareIntegrationArtifactEntity artifact = new SoftwareIntegrationArtifactEntity();
        artifact.setRunId(baseline.getId());
        artifact.setArtifactName("eclipse-output-CASE.INIT");
        artifact.setArtifactType("output");
        artifact.setContentType("application/octet-stream");
        artifact.setStorageKey("artifacts/" + baseline.getId() + "/eclipse-output-CASE.INIT");
        artifact.setSizeBytes(48L);
        artifact.setSha256(sha);
        artifact.setCreatedAt(LocalDateTime.now());
        artifact.setExpiresAt(LocalDateTime.now().plusDays(30));
        artifactMapper.insert(artifact);

        var parameters = Map.ofEntries(
                Map.entry("schemaVersion", "eclipse-grid-field-parameters/1"),
                Map.entry("baselineRunId", baseline.getId()), Map.entry("fieldFile", "CASE.INIT"),
                Map.entry("fieldArtifactSha256", sha), Map.entry("nx", 2), Map.entry("ny", 2),
                Map.entry("nz", 1), Map.entry("i", 2), Map.entry("j", 1), Map.entry("k", 1),
                Map.entry("originalValue", 2.5), Map.entry("multiplier", 0.5));
        var request = request(null, "eclipse");
        request.setParameters(parameters);

        var created = runService.create(eclipse.version().getId(), request);

        assertThat(created.parameters().path("schemaVersion").asText()).isEqualTo("eclipse-grid-field-parameters/1");
        assertThat(created.parameters().path("baselineRunId").asLong()).isEqualTo(baseline.getId());
        assertThat(runStore.find(created.id()).getParametersJson()).contains("CASE.INIT");

        var staleArtifactRequest = request(null, "eclipse");
        var staleParameters = new java.util.HashMap<>(parameters);
        staleParameters.put("fieldArtifactSha256", "c".repeat(64));
        staleArtifactRequest.setParameters(staleParameters);
        assertThatThrownBy(() -> runService.create(eclipse.version().getId(), staleArtifactRequest))
                .isInstanceOf(RunException.class)
                .satisfies(error -> assertThat(((RunException) error).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void eclipseRunCreationRequiresValidPersistedInspectionWithoutDisclosingItsContents() throws Exception {
        Seed eclipse = seed("READY", "models/eclipse/inspection/CASE.DATA", "ECLIPSE_100");
        eclipse.version().setInspectionJson(null);
        jdbcTemplate.update("UPDATE software_integration_model_version SET inspection_json = NULL WHERE id = ?",
                eclipse.version().getId());

        assertThatThrownBy(() -> runService.create(eclipse.version().getId(), request(null, "eclipse")))
                .isInstanceOf(RunException.class)
                .satisfies(error -> {
                    assertThat(((RunException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getMessage()).isEqualTo("ECLIPSE 模型版本缺少有效检查信息，请重新验证");
                });
        assertThat(runStore.listByVersion(eclipse.version().getId(), 10)).isEmpty();

        String sensitiveMalformedInspection = """
                {"schemaVersion":"eclipse-data-inspection/1","caseName":"C:\\\\private\\\\CASE.DATA",
                 "sections":[],"unitSystem":null,"phases":[],"dimensions":null,"deck":"private deck content"}
                """;
        eclipse.version().setInspectionJson(sensitiveMalformedInspection);
        versionMapper.updateById(eclipse.version());
        String response = mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                        .contentType("application/json")
                        .content("{\"study\":null,\"runType\":\"eclipse\",\"parameters\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("ECLIPSE 模型版本缺少有效检查信息，请重新验证"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(response).doesNotContain("C:\\\\private", "private deck content");
        assertThat(runStore.listByVersion(eclipse.version().getId(), 10)).isEmpty();

        eclipse.version().setInspectionJson(validEclipseInspection());
        versionMapper.updateById(eclipse.version());
        assertThat(runService.create(eclipse.version().getId(), request(null, "eclipse")).runType()).isEqualTo("eclipse");
    }

    @Test
    void capabilitiesEndpointReturnsOnlyControlledEclipseFieldsAndReasonCodes() throws Exception {
        MockMvc projectMvc = MockMvcBuilders.standaloneSetup(
                        new SoftwareIntegrationController(softwareIntegrationService, capabilityService))
                .setControllerAdvice(new SoftwareIntegrationRunExceptionHandler()).build();

        projectMvc.perform(get("/software-integration/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.worker.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.worker.idle").value(true))
                .andExpect(jsonPath("$.data.pipesimWell.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.pipesimNetwork.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.eclipse100.version").value("2024.1"))
                .andExpect(jsonPath("$.data.eclipse100.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.eclipse100.runTasks[0]").value("eclipse"))
                .andExpect(jsonPath("$.data.eclipse100.maxTimeoutSeconds").value(1800))
                .andExpect(jsonPath("$.data.eclipse100.launcherFound").doesNotExist())
                .andExpect(jsonPath("$.data.eclipse100.path").doesNotExist());

        fakeWorker.eclipseCapability = new WorkerEclipseCapability(
                "2023.1", "AVAILABLE", null, List.of("eclipse"), 1800);
        projectMvc.perform(get("/software-integration/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eclipse100.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.eclipse100.reasonCode").value("ECLIPSE_VERSION_MISMATCH"));

        fakeWorker.capabilityError = new WorkerClientException("unreachable");
        projectMvc.perform(get("/software-integration/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.worker.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.pipesimWell.reasonCode").value("WORKER_UNREACHABLE"))
                .andExpect(jsonPath("$.data.pipesimNetwork.reasonCode").value("WORKER_UNREACHABLE"))
                .andExpect(jsonPath("$.data.eclipse100.reasonCode").value("WORKER_UNREACHABLE"));
    }

    @Test
    void dataAndPipsUploadsRemainTypeIsolated() throws Exception {
        SoftwareIntegrationValidationDispatcher validationDispatcher =
                org.mockito.Mockito.mock(SoftwareIntegrationValidationDispatcher.class);
        SoftwareIntegrationService uploadService = new SoftwareIntegrationServiceImpl(
                projectMapper, modelMapper, versionMapper, integrationProperties, validationDispatcher,
                normalizer, jdbcTemplate);
        SoftwareIntegrationProjectEntity project = new SoftwareIntegrationProjectEntity();
        project.setName("upload-" + UUID.randomUUID());
        project.setCreatedBy("administrator");
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.insert(project);

        uploadService.uploadModel(project.getId(), new MockMultipartFile(
                "file", "CASE.DATA", "text/plain", "RUNSPEC".getBytes(StandardCharsets.UTF_8)));
        uploadService.uploadModel(project.getId(), new MockMultipartFile(
                "file", "WELL.pips", "application/octet-stream", new byte[]{1, 2, 3}));

        var detail = uploadService.getProject(project.getId());
        assertThat(detail.models()).extracting(model -> model.name() + ":" + model.simulatorType())
                .containsExactlyInAnyOrder("CASE:ECLIPSE_100", "WELL:PIPESIM_WELL");
        assertThat(detail.models()).flatExtracting(model -> model.versions())
                .extracting(version -> version.originalName() + ":" + version.status())
                .containsExactlyInAnyOrder("CASE.DATA:UPLOADED", "WELL.pips:UPLOADED");

        assertThatThrownBy(() -> uploadService.uploadModel(project.getId(), new MockMultipartFile(
                "file", "CASE.pips", "application/octet-stream", new byte[]{4})))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(409));
    }

    @Test
    void zipUploadExtractsAndPersistsTheSingleMainModelForWorkerValidation() throws Exception {
        SoftwareIntegrationValidationDispatcher validationDispatcher =
                org.mockito.Mockito.mock(SoftwareIntegrationValidationDispatcher.class);
        SoftwareIntegrationService uploadService = new SoftwareIntegrationServiceImpl(
                projectMapper, modelMapper, versionMapper, integrationProperties, validationDispatcher,
                normalizer, jdbcTemplate);
        SoftwareIntegrationProjectEntity project = new SoftwareIntegrationProjectEntity();
        project.setName("zip-upload-" + UUID.randomUUID());
        project.setCreatedBy("administrator");
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.insert(project);

        uploadService.uploadModel(project.getId(), new MockMultipartFile(
                "file", "NETWORK.zip", "application/zip", zipBytes(
                        new String[]{"case/network.pips", "case/include.inc"},
                        new String[]{"MODEL", "INCLUDE"})));

        var version = versionMapper.selectList(new LambdaQueryWrapper<SoftwareIntegrationModelVersionEntity>())
                .stream().findFirst().orElseThrow();
        assertThat(version.getOriginalName()).isEqualTo("NETWORK.zip");
        assertThat(version.getStorageKey()).isEqualTo("models/" + version.getModelId() + "/1/case/network.pips");
        assertThat(version.getSizeBytes()).isEqualTo(5);
        assertThat(Files.readString(normalizer.resolve(version.getStorageKey()))).isEqualTo("MODEL");
        assertThat(version.getStatus()).isEqualTo("UPLOADED");
    }

    @Test
    void eclipseZipUploadPersistsTheCompleteIncludeProjectPackage() throws Exception {
        SoftwareIntegrationValidationDispatcher validationDispatcher =
                org.mockito.Mockito.mock(SoftwareIntegrationValidationDispatcher.class);
        SoftwareIntegrationService uploadService = new SoftwareIntegrationServiceImpl(
                projectMapper, modelMapper, versionMapper, integrationProperties, validationDispatcher,
                normalizer, jdbcTemplate);
        SoftwareIntegrationProjectEntity project = new SoftwareIntegrationProjectEntity();
        project.setName("eclipse-zip-boundary-" + UUID.randomUUID());
        project.setCreatedBy("administrator");
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.insert(project);

        uploadService.uploadModel(project.getId(), new MockMultipartFile(
                "file", "CASE.zip", "application/zip", zipBytes(
                        new String[]{"CASE.DATA", "GRID.EGRID", "grid.inc"},
                        new String[]{"RUNSPEC\nINCLUDE 'grid.inc' /\n", "grid", "GRID\n"})));
        var detail = uploadService.getProject(project.getId());
        assertThat(detail.models()).hasSize(1);
        var model = detail.models().get(0);
        assertThat(model.simulatorType()).isEqualTo("ECLIPSE_100");
        assertThat(model.versions()).hasSize(1);
        var version = model.versions().get(0);
        assertThat(version.originalName()).isEqualTo("CASE.zip");
        assertThat(Files.exists(normalizer.resolve("models/" + model.id() + "/1/CASE.DATA"))).isTrue();
        assertThat(Files.readString(normalizer.resolve("models/" + model.id() + "/1/grid.inc"))).contains("GRID");
        org.mockito.Mockito.verify(validationDispatcher).enqueue(version.id());
    }

    @Test
    void eclipseGroupInspectionZipCanRunWithoutScenarioForReimport() throws Exception {
        Seed eclipse = seed("READY", "models/eclipse/group/CASE.DATA", "ECLIPSE_100");
        eclipse.version().setOriginalName("applied.zip");
        eclipse.version().setInspectionJson("""
            {"schemaVersion":"eclipse-data-inspection/6","caseName":"CASE.DATA","sections":["RUNSPEC","SCHEDULE"],"unitSystem":"FIELD","phases":["OIL"],"dimensions":null,"wellNames":["P1","P2"],"scheduleTimeline":[],"packageFiles":[{"relativePath":"CASE.DATA","sizeBytes":5,"sha256":"%s"}],"vfpTables":[],"scheduleMetadata":{"wells":[{"name":"P1","group":"G","sourceFile":"CASE.DATA","lineNumber":4},{"name":"P2","group":"G","sourceFile":"CASE.DATA","lineNumber":5}],"groups":[],"records":[],"completions":[]},"groupControls":[{"group":"G","sourceFile":"CASE.DATA","lineNumber":10,"controlMode":"ORAT","unit":"STB/day","originalTargetRate":30000,"members":[{"well":"P1","bhpLimit":2500},{"well":"P2","bhpLimit":2500}]}]}
            """.formatted("a".repeat(64)));
        versionMapper.updateById(eclipse.version());
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                .contentType("application/json").content("{\"study\":null,\"runType\":\"eclipse\",\"parameters\":null}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.parameters").doesNotExist());
    }

    @Test
    void eclipseInjectionGroupInspectionZipCanRunWithoutScenarioForReimport() throws Exception {
        Seed eclipse = seed("READY", "models/eclipse/group/CASE.DATA", "ECLIPSE_100");
        eclipse.version().setOriginalName("applied.zip");
        eclipse.version().setInspectionJson("""
            {"schemaVersion":"eclipse-data-inspection/7","caseName":"CASE.DATA","sections":["RUNSPEC","SCHEDULE"],"unitSystem":"FIELD","phases":["WATER"],"dimensions":null,"wellNames":["P1","P2"],"scheduleTimeline":[],"packageFiles":[{"relativePath":"CASE.DATA","sizeBytes":5,"sha256":"%s"}],"vfpTables":[],"scheduleMetadata":{"wells":[{"name":"P1","group":"G","sourceFile":"CASE.DATA","lineNumber":4},{"name":"P2","group":"G","sourceFile":"CASE.DATA","lineNumber":5}],"groups":[],"records":[],"completions":[]},"injectionGroupControls":[{"group":"G","sourceFile":"CASE.DATA","lineNumber":10,"controlMode":"RATE","injectionPhase":"WATER","unit":"STB/day","originalTargetRate":30000,"members":[{"well":"P1","bhpLimit":6000},{"well":"P2","bhpLimit":6000}]}]}
            """.formatted("a".repeat(64)));
        versionMapper.updateById(eclipse.version());
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                .contentType("application/json").content("{\"study\":null,\"runType\":\"eclipse\",\"parameters\":null}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.parameters").doesNotExist());
    }

    @Test
    void eclipseGasInjectionGroupInspectionZipCanRunWithoutScenarioForReimport() throws Exception {
        Seed eclipse = seed("READY", "models/eclipse/group/CASE.DATA", "ECLIPSE_100");
        eclipse.version().setOriginalName("applied.zip");
        eclipse.version().setInspectionJson("""
            {"schemaVersion":"eclipse-data-inspection/8","caseName":"CASE.DATA","sections":["RUNSPEC","SCHEDULE"],"unitSystem":"FIELD","phases":["GAS"],"dimensions":null,"wellNames":["P1","P2"],"scheduleTimeline":[],"packageFiles":[{"relativePath":"CASE.DATA","sizeBytes":5,"sha256":"%s"}],"vfpTables":[],"scheduleMetadata":{"wells":[{"name":"P1","group":"G","sourceFile":"CASE.DATA","lineNumber":4},{"name":"P2","group":"G","sourceFile":"CASE.DATA","lineNumber":5}],"groups":[],"records":[],"completions":[]},"gasInjectionGroupControls":[{"group":"G","sourceFile":"CASE.DATA","lineNumber":10,"controlMode":"RATE","injectionPhase":"GAS","unit":"Mscf/day","originalTargetRate":30000,"members":[{"well":"P1","bhpLimit":6000},{"well":"P2","bhpLimit":6000}]}]}
            """.formatted("a".repeat(64)));
        versionMapper.updateById(eclipse.version());
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                .contentType("application/json").content("{\"study\":null,\"runType\":\"eclipse\",\"parameters\":null}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.parameters").doesNotExist());
    }

    @Test
    void eclipseWaterProductionGroupInspectionZipCanRunWithoutScenarioForReimport() throws Exception {
        Seed eclipse = seed("READY", "models/eclipse/group/CASE.DATA", "ECLIPSE_100");
        eclipse.version().setOriginalName("applied.zip");
        eclipse.version().setInspectionJson("""
            {"schemaVersion":"eclipse-data-inspection/10","caseName":"CASE.DATA","sections":["RUNSPEC","SCHEDULE"],"unitSystem":"FIELD","phases":["GAS"],"dimensions":null,"wellNames":["P1","P2"],"scheduleTimeline":[],"packageFiles":[{"relativePath":"CASE.DATA","sizeBytes":5,"sha256":"%s"}],"vfpTables":[],"scheduleMetadata":{"wells":[{"name":"P1","group":"G","sourceFile":"CASE.DATA","lineNumber":4},{"name":"P2","group":"G","sourceFile":"CASE.DATA","lineNumber":5}],"groups":[],"records":[],"completions":[]},"waterProductionGroupControls":[{"group":"G","sourceFile":"CASE.DATA","lineNumber":10,"controlMode":"WRAT","unit":"STB/day","originalTargetRate":30000,"members":[{"well":"P1","bhpLimit":2500,"oilRateLimit":20000},{"well":"P2","bhpLimit":2500,"oilRateLimit":20000}]}]}
            """.formatted("a".repeat(64)));
        versionMapper.updateById(eclipse.version());
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                .contentType("application/json").content("{\"study\":null,\"runType\":\"eclipse\",\"parameters\":null}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.parameters").doesNotExist());
    }

    @Test
    void advancedInjectionExplicitNullSurvivesConfiguredMapperAndMissingReferenceRejects() throws Exception {
        Seed eclipse = seed("READY", "models/eclipse/group/CASE.DATA", "ECLIPSE_100");
        eclipse.version().setInspectionJson("""
            {"schemaVersion":"eclipse-data-inspection/11","caseName":"CASE.DATA","sections":["RUNSPEC","SCHEDULE"],"unitSystem":"FIELD","phases":["OIL"],"dimensions":null,"wellNames":["I1","I2"],"scheduleTimeline":[],"packageFiles":[{"relativePath":"CASE.DATA","sizeBytes":5,"sha256":"%s"}],"vfpTables":[],"scheduleMetadata":{"wells":[{"name":"I1","group":"I","sourceFile":"CASE.DATA","lineNumber":4},{"name":"I2","group":"I","sourceFile":"CASE.DATA","lineNumber":5}],"groups":[],"records":[],"completions":[]},"advancedInjectionGroupControls":[{"group":"I","sourceFile":"CASE.DATA","lineNumber":10,"controlMode":"RATE","injectionPhase":"OIL","unit":"STB/day","originalTargetRate":5000,"members":[{"well":"I1","bhpLimit":4900,"surfaceRateLimit":50000},{"well":"I2","bhpLimit":4900,"surfaceRateLimit":50000}],"referenceGroup":null,"referenceMembers":[]}]}
            """.formatted("a".repeat(64)));
        versionMapper.updateById(eclipse.version());
        String parameters="""
            {"schemaVersion":"eclipse-advanced-injection-parameters/1","baselineRunId":99999999,"group":"I","phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":10,"controlMode":"RATE","injectionPhase":"OIL","unit":"STB/day","originalTargetRate":5000,"targetRate":2500,"members":["I1","I2"],"referenceGroup":null,"referenceMembers":[]}
            """;
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                .contentType("application/json").content("{\"study\":null,\"runType\":\"eclipse\",\"parameters\":"+parameters+"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                .contentType("application/json").content("{\"study\":null,\"runType\":\"eclipse\",\"parameters\":"+parameters.replace("\"referenceGroup\":null,", "")+"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void eclipseProductionGroupRateInspectionZipCanRunWithoutScenarioForReimport() throws Exception {
        Seed eclipse = seed("READY", "models/eclipse/group/CASE.DATA", "ECLIPSE_100");
        eclipse.version().setOriginalName("applied.zip");
        eclipse.version().setInspectionJson("""
            {"schemaVersion":"eclipse-data-inspection/9","caseName":"CASE.DATA","sections":["RUNSPEC","SCHEDULE"],"unitSystem":"FIELD","phases":["GAS"],"dimensions":null,"wellNames":["P1","P2"],"scheduleTimeline":[],"packageFiles":[{"relativePath":"CASE.DATA","sizeBytes":5,"sha256":"%s"}],"vfpTables":[],"scheduleMetadata":{"wells":[{"name":"P1","group":"G","sourceFile":"CASE.DATA","lineNumber":4},{"name":"P2","group":"G","sourceFile":"CASE.DATA","lineNumber":5}],"groups":[],"records":[],"completions":[]},"groupRateControls":[{"group":"G","sourceFile":"CASE.DATA","lineNumber":10,"controlMode":"GRAT","unit":"Mscf/day","originalTargetRate":30000,"members":[{"well":"P1","bhpLimit":2500},{"well":"P2","bhpLimit":2500}]}]}
            """.formatted("a".repeat(64)));
        versionMapper.updateById(eclipse.version());
        mockMvc.perform(post("/software-integration/model-versions/{id}/runs", eclipse.version().getId())
                .contentType("application/json").content("{\"study\":null,\"runType\":\"eclipse\",\"parameters\":null}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.parameters").doesNotExist());
    }

    @Test
    void runTypeMustMatchPersistedSimulatorType() throws Exception {
        Seed well = seed("READY", "models/well/1/model.pips");
        assertThatThrownBy(() -> runService.create(well.version().getId(), request("Study 1", "network")))
                .isInstanceOf(RunException.class).satisfies(error ->
                        assertThat(((RunException) error).status()).isEqualTo(HttpStatus.BAD_REQUEST));

        Seed network = seed("READY", "models/network/1/model.pips", "PIPESIM_NETWORK");
        for (String runType : List.of("nodal", "profile", "combined")) {
            assertThatThrownBy(() -> runService.create(network.version().getId(), request("Study 1", runType)))
                    .isInstanceOf(RunException.class).satisfies(error ->
                            assertThat(((RunException) error).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        var created = runService.create(network.version().getId(), request("Study 1", "network"));
        assertThat(created.runType()).isEqualTo("network");
        assertThat(runStore.find(created.id()).getParametersJson()).isEqualTo("null");

        well.version().setOriginalName("wrong.DATA");
        versionMapper.updateById(well.version());
        assertThatThrownBy(() -> runService.create(well.version().getId(), request("Study 1", "nodal")))
                .isInstanceOf(RunException.class)
                .satisfies(error -> assertThat(((RunException) error).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void mixedWellModelRunsOnlyNetworkStudiesThatPassedValidation() throws Exception {
        Seed mixed = seed("READY", "models/mixed-network/1/model.pips");
        mixed.version().setModelKind("basic_gas");
        mixed.version().setInspectionJson(validMixedWellNetworkInspection());
        versionMapper.updateById(mixed.version());

        var created = runService.create(mixed.version().getId(), request("Study 1", "network"));
        assertThat(created.runType()).isEqualTo("network");
        assertThat(runStore.find(created.id()).getParametersJson()).isEqualTo("null");
        assertThatThrownBy(() -> runService.create(mixed.version().getId(), request("Study 2", "network")))
                .isInstanceOf(RunException.class)
                .satisfies(error -> assertThat(((RunException) error).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> runService.create(mixed.version().getId(), request("Study 1", "system-analysis")))
                .isInstanceOf(RunException.class)
                .satisfies(error -> assertThat(((RunException) error).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void networkFlowlineDiameterRequiresSameVersionAndStudySuccessfulBaseline() throws Exception {
        Seed network = seed("READY", "models/network/flowline-variant.pips", "PIPESIM_NETWORK");
        network.version().setInspectionJson("""
                {"schemaVersion":"pipesim-network-inspection/5",
                 "studies":[
                   {"study":"Study 1","boundaries":[{"node":"Source_1","boundaryNodeType":"Source","isActive":true,"isSurfaceCondition":true,"flowRateType":"GasFlowRate","pressure":100,"temperature":80,"gasFlowRate":5,"liquidFlowRate":null,"massFlowRate":null}]},
                   {"study":"Network Study","boundaries":[{"node":"Source_1","boundaryNodeType":"Source","isActive":true,"isSurfaceCondition":true,"flowRateType":"GasFlowRate","pressure":100,"temperature":80,"gasFlowRate":5,"liquidFlowRate":null,"massFlowRate":null}]}],
                 "chokes":[],"packageFiles":[{"relativePath":"model.pips","sizeBytes":5,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],
                 "wellContexts":[],"flowlineContexts":["FL-2"],"flowlines":[{"name":"FL-2","innerDiameter":4.0,"unit":"in"}]}
                """);
        versionMapper.updateById(network.version());

        var sameStudyBaseline = successfulNetworkBaseline(network, "Study 1");
        var otherStudyBaseline = successfulNetworkBaseline(network, "Network Study");
        var validRequest = flowlineDiameterRequest("Study 1", sameStudyBaseline.getId(), 4.0, "in", 5.0);

        var accepted = runService.create(network.version().getId(), validRequest);

        assertThat(accepted.runType()).isEqualTo("network");
        assertThat(runStore.find(accepted.id()).getParametersJson()).contains("pipesim-network-flowline-diameter-parameters/1");
        assertThatThrownBy(() -> runService.create(network.version().getId(),
                flowlineDiameterRequest("Study 1", otherStudyBaseline.getId(), 4.0, "in", 5.0)))
                .isInstanceOf(RunException.class)
                .satisfies(error -> assertThat(((RunException) error).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> runService.create(network.version().getId(),
                flowlineDiameterRequest("Study 1", sameStudyBaseline.getId(), 4.0, "mm", 5.0)))
                .isInstanceOf(RunException.class);
    }

    @Test
    void networkConnectionRedirectRequiresExactFullBaselineTopologyAndStudy() throws Exception {
        Seed network = seed("READY", "models/network/connection-variant.pips", "PIPESIM_NETWORK");
        network.version().setInspectionJson("""
                {"schemaVersion":"pipesim-network-inspection/5",
                 "studies":[{"study":"Study 1","boundaries":[{"node":"Source-1","boundaryNodeType":"Source","isActive":true,"isSurfaceCondition":true,"flowRateType":"GasFlowRate","pressure":100,"temperature":80,"gasFlowRate":5,"liquidFlowRate":null,"massFlowRate":null}]}],
                 "chokes":[],"packageFiles":[{"relativePath":"model.pips","sizeBytes":5,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],
                 "wellContexts":[],"flowlineContexts":["Flowline-1"],"flowlines":[{"name":"Flowline-1","innerDiameter":4.0,"unit":"in"}]}
                """);
        versionMapper.updateById(network.version());

        var baseline = successfulNetworkTopologyBaseline(network, "Study 1");
        var accepted = runService.create(network.version().getId(), networkConnectionRequest(
                "Study 1", baseline.getId(), "Junction-1", "Junction-2"));
        assertThat(accepted.runType()).isEqualTo("network");
        assertThat(runStore.find(accepted.id()).getParametersJson()).contains("pipesim-network-connection-parameters/1");

        assertThatThrownBy(() -> runService.create(network.version().getId(), networkConnectionRequest(
                "Study 1", baseline.getId(), "Missing-Junction", "Junction-2")))
                .isInstanceOf(RunException.class)
                .satisfies(error -> assertThat(((RunException) error).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> runService.create(network.version().getId(), networkConnectionRequest(
                "Study 2", baseline.getId(), "Junction-1", "Junction-2")))
                .isInstanceOf(RunException.class)
                .satisfies(error -> assertThat(((RunException) error).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void networkDerivedScenariosCannotBypassBaselineBindingWhenInspectionIsMissing() throws Exception {
        Seed network = seed("READY", "models/network/no-inspection.pips", "PIPESIM_NETWORK");
        Seed other = seed("READY", "models/network/other-baseline.pips", "PIPESIM_NETWORK");
        var sameVersionBaseline = successfulNetworkTopologyBaseline(network, "Study 1");
        var otherVersionBaseline = successfulNetworkTopologyBaseline(other, "Study 1");
        int queuedBefore = runStore.listByVersion(network.version().getId(), 50).size();
        for (var invalid : List.of(
                networkConnectionRequest("Study 1", Long.MAX_VALUE, "Junction-1", "Junction-2"),
                networkConnectionRequest("Study 1", otherVersionBaseline.getId(), "Junction-1", "Junction-2"),
                networkConnectionRequest("Study 2", sameVersionBaseline.getId(), "Junction-1", "Junction-2"),
                networkConnectionRequest(null, sameVersionBaseline.getId(), "Junction-1", "Junction-2"))) {
            assertThatThrownBy(() -> runService.create(network.version().getId(), invalid))
                    .isInstanceOf(RunException.class)
                    .satisfies(error -> assertThat(((RunException) error).status()).isEqualTo(HttpStatus.CONFLICT));
        }
        var choke = request("Study 1", "network");
        choke.setParameters(Map.of("schemaVersion", "pipesim-network-choke-bean-size-parameters/1",
                "baselineRunId", sameVersionBaseline.getId(), "choke", "Choke",
                "originalBeanSize", 2, "targetBeanSize", 3));
        assertThatThrownBy(() -> runService.create(network.version().getId(), choke))
                .isInstanceOf(RunException.class)
                .satisfies(error -> assertThat(((RunException) error).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(runStore.listByVersion(network.version().getId(), 50)).hasSize(queuedBefore);
        var accepted = runService.create(network.version().getId(), networkConnectionRequest(
                "Study 1", sameVersionBaseline.getId(), "Junction-1", "Junction-2"));
        assertThat(accepted.status()).isEqualTo("QUEUED");
    }

    @Test
    void reorderedAndRepeatedWorkerEventsDoNotRegressTheRunPhase() throws Exception {
        Seed seed = seed("READY", "models/events/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "combined")).id();
        var dispatcher = dispatcher();
        dispatcher.dispatch();
        var preparing = new WorkerRunEvent(1, "PREPARING", Instant.now(), "preparing");
        var nodal = new WorkerRunEvent(2, "RUNNING_NODAL", Instant.now(), "nodal");
        var profile = new WorkerRunEvent(3, "RUNNING_PROFILE", Instant.now(), "profile");
        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "RUNNING_PROFILE", 3, "worker-1", "generation-1",
                List.of(profile, preparing, nodal), null, null, List.of(), null);
        dispatcher.poll();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("RUNNING_PROFILE");
        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "RUNNING_PROFILE", 3, "worker-1", "generation-1",
                List.of(preparing, nodal, profile), null, null, List.of(), null);
        dispatcher.poll();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("RUNNING_PROFILE");
        assertThat(runStore.events(runId).stream().filter(event -> event.getWorkerSequence() != null)).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tubingResultRequiresItsDeclaredDerivedModelArtifact(boolean includeModel) throws Exception {
        Seed seed = seed("READY", "models/artifact-model/model.pips");
        String parameters = """
                {"schemaVersion":"pipesim-well-tubing-diameter-parameters/1","baselineRunId":74,"wellContext":"Well_1",
                 "tubingContext":"Well_1:Tubing","originalInnerDiameter":2.441,"unit":"in","targetInnerDiameter":2.3}
                """;
        long runId = runStore.createQueued(seed.project().getId(), seed.model().getId(), seed.version().getId(),
                "Study 1", "profile", 600, parameters).getId();
        var dispatcher = dispatcher();
        dispatcher.dispatch();
        Path output = STORAGE_ROOT.resolve("jobs/" + runId + "/output");
        Files.createDirectories(output);
        var descriptors = new java.util.ArrayList<WorkerRunArtifact>();
        ArrayNode files = objectMapper.createArrayNode();
        if (includeModel) {
            Path derived = output.resolve("pipesim-well-tubing-applied.pips");
            Files.writeString(derived, "mock derived PIPESIM model");
            String key = "jobs/" + runId + "/output/" + derived.getFileName();
            descriptors.add(new WorkerRunArtifact(key, Files.size(derived), sha256(derived), "application/octet-stream"));
            files.addObject().put("storageKey", key).put("size", Files.size(derived))
                    .put("sha256", sha256(derived)).put("contentType", "application/octet-stream");
        }
        ObjectNode manifest = objectMapper.createObjectNode().put("schemaVersion", "grdp-worker-artifact-manifest/1")
                .put("runId", runId).put("generatedAtUtc", Instant.now().toString());
        manifest.set("files", files);
        Path manifestFile = output.resolve("manifest.json");
        Files.writeString(manifestFile, manifest.toString());
        descriptors.add(new WorkerRunArtifact("jobs/" + runId + "/output/manifest.json",
                Files.size(manifestFile), sha256(manifestFile), "application/json"));
        ObjectNode result = (ObjectNode) partialResult();
        result.put("schemaVersion", "pipesim-well-result/2").put("runTask", "profile").put("resultContract", "VALID_FULL");
        result.putArray("ipr");
        result.putArray("vlp");
        result.putArray("profile").addObject().put("depth", 0).put("pressure", 1500).put("temperature", 80);
        ObjectNode application = (ObjectNode) objectMapper.readTree(parameters);
        application.remove("schemaVersion");
        application.remove("baselineRunId");
        application.put("applied", true).put("isolatedCopy", true).put("sourceModelUnchanged", true)
                .put("artifactName", "pipesim-well-tubing-applied.pips").put("appliedInnerDiameter", 2.3);
        result.set("application", application);
        resultValidator.validate("profile", "Study 1", result, objectMapper.readTree(parameters));
        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "SUCCEEDED", 2, "worker-1", "generation-1",
                List.of(new WorkerRunEvent(1, "PREPARING", Instant.now(), "preparing"),
                        new WorkerRunEvent(2, "RUNNING_PROFILE", Instant.now(), "profile")),
                result, null, descriptors, objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));
        dispatcher.poll();
        assertThat(runStore.find(runId).getStatus()).isEqualTo(includeModel ? "SUCCEEDED" : "FAILED");
        if (!includeModel) {
            assertThat(runService.get(runId).error().path("code").asText()).isEqualTo("ARTIFACT_INVALID");
            assertThat(runStore.artifacts(runId)).isEmpty();
            assertThat(Files.exists(STORAGE_ROOT.resolve("artifacts/" + runId))).isFalse();
        }
    }

    private SoftwareIntegrationRunEntity successfulNetworkTopologyBaseline(Seed network, String study) {
        long runId = runService.create(network.version().getId(), request(study, "network")).id();
        SoftwareIntegrationRunEntity baseline = runStore.find(runId);
        baseline.setStatus("SUCCEEDED");
        baseline.setResultContract("VALID_FULL");
        baseline.setParametersJson("null");
        baseline.setResultJson("""
                {"schemaVersion":"pipesim-network-result/1","model_kind":"network","runTask":"network","resultContract":"VALID_FULL","study":"%s",
                 "topology":{"nodes":[{"id":"Source-1","componentType":"Source"},{"id":"Flowline-1","componentType":"Flowline"},
                   {"id":"Junction-1","componentType":"Junction"},{"id":"Junction-2","componentType":"Junction"},{"id":"Sink-1","componentType":"Sink"}],
                   "edges":[{"source":"Source-1","sourcePort":"","destination":"Flowline-1"},
                     {"source":"Flowline-1","sourcePort":"","destination":"Junction-1"},
                     {"source":"Junction-1","sourcePort":"","destination":"Sink-1"},
                     {"source":"Source-1","sourcePort":"","destination":"Junction-2"},
                     {"source":"Junction-2","sourcePort":"","destination":"Sink-1"}]}}
                """.formatted(study));
        runMapper.updateById(baseline);
        return runStore.find(runId);
    }

    private SoftwareIntegrationCreateRunRequest networkConnectionRequest(
            String study, long baselineRunId, String originalDestination, String newDestination) {
        SoftwareIntegrationCreateRunRequest request = request(study, "network");
        request.setParameters(Map.of(
                "schemaVersion", "pipesim-network-connection-parameters/1",
                "baselineRunId", baselineRunId,
                "source", "Flowline-1",
                "sourcePort", "",
                "originalDestination", originalDestination,
                "newDestination", newDestination));
        return request;
    }

    private SoftwareIntegrationRunEntity successfulNetworkBaseline(Seed network, String study) {
        long runId = runService.create(network.version().getId(), request(study, "network")).id();
        SoftwareIntegrationRunEntity baseline = runStore.find(runId);
        baseline.setStatus("SUCCEEDED");
        baseline.setResultContract("VALID_FULL");
        baseline.setParametersJson("null");
        runMapper.updateById(baseline);
        return runStore.find(runId);
    }

    private SoftwareIntegrationCreateRunRequest flowlineDiameterRequest(
            String study, long baselineRunId, double original, String unit, double target) {
        SoftwareIntegrationCreateRunRequest request = request(study, "network");
        request.setParameters(Map.of(
                "schemaVersion", "pipesim-network-flowline-diameter-parameters/1",
                "baselineRunId", baselineRunId,
                "flowline", "FL-2",
                "originalInnerDiameter", original,
                "unit", unit,
                "targetInnerDiameter", target));
        return request;
    }

    @Test
    void versionModelKindOverridesMutableParentSimulatorType() throws Exception {
        Seed historicalWell = seed("READY", "models/versioned/1/model.pips", "PIPESIM_NETWORK");
        historicalWell.version().setModelKind("black_oil_liquid");
        versionMapper.updateById(historicalWell.version());

        var created = runService.create(historicalWell.version().getId(), request("Study 1", "nodal"));

        assertThat(created.runType()).isEqualTo("nodal");
        assertThatThrownBy(() -> runService.create(
                historicalWell.version().getId(), request("Network Study", "network")))
                .isInstanceOf(RunException.class)
                .satisfies(error -> assertThat(((RunException) error).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void sanitizedStudyNameRoundTripsToTheRawWorkerStudyWithoutBrowserDisclosure() throws Exception {
        Seed seed = seed("READY", "models/versioned/sensitive-study.pips");
        seed.version().setStudiesJson("C:\\Users\\operator\\private-study");
        versionMapper.updateById(seed.version());

        var created = runService.create(seed.version().getId(), request("[local path]", "nodal"));

        assertThat(created.study()).isEqualTo("[local path]");
        assertThat(runStore.find(created.id()).getStudyName()).isEqualTo("C:\\Users\\operator\\private-study");
    }

    @Test
    void readyVersionWithoutKindRequiresRevalidationInsteadOfUsingParentType() throws Exception {
        Seed version = seed("READY", "models/versioned/missing-kind.pips", "PIPESIM_NETWORK");
        jdbcTemplate.update("UPDATE software_integration_model_version SET model_kind = NULL WHERE id = ?",
                version.version().getId());

        assertThatThrownBy(() -> runService.create(version.version().getId(), request("Network Study", "network")))
                .isInstanceOf(RunException.class)
                .satisfies(error -> assertThat(((RunException) error).status()).isEqualTo(HttpStatus.CONFLICT));
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
        var rawArtifact = runStore.artifacts(runId).stream()
                .filter(artifact -> artifact.getArtifactName().equals("raw.json"))
                .findFirst().orElseThrow();
        mockMvc.perform(get("/software-integration/runs/{runId}/artifacts/{artifactId}/download", runId, rawArtifact.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("raw.json")))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string("real worker artifact"));
        jdbcTemplate.update("UPDATE software_integration_artifact SET expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), rawArtifact.getId());
        artifactCleanup.cleanupExpiredArtifacts();
        assertThat(Files.exists(normalizer.resolve(rawArtifact.getStorageKey()))).isFalse();
        assertThat(runStore.artifacts(runId)).noneMatch(artifact -> artifact.getId().equals(rawArtifact.getId()));
        assertThat(fakeWorker.transactionActiveDuringHttp).isFalse();
    }

    @Test
    void networkWorkerPhaseAndFullResultArePersisted() throws Exception {
        Seed seed = seed("READY", "models/network/1/network.pips", "PIPESIM_NETWORK");
        long runId = runService.create(seed.version().getId(), request("Network Study", "network")).id();
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();
        assertThat(fakeWorker.lastExecuteRequest.runTask()).isEqualTo("network");
        assertThat(fakeWorker.lastExecuteRequest.parameters()).isNull();

        Path manifest = STORAGE_ROOT.resolve("jobs/" + runId + "/output/manifest.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, """
                {"schemaVersion":"grdp-worker-artifact-manifest/1","runId":%d,
                 "generatedAtUtc":"2026-09-08T00:00:00Z","files":[]}
                """.formatted(runId));
        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "SUCCEEDED", 4, "worker-1", "generation-1",
                List.of(
                        new WorkerRunEvent(1, "CLAIMED", Instant.now(), "claimed"),
                        new WorkerRunEvent(2, "PREPARING", Instant.now(), "preparing"),
                        new WorkerRunEvent(3, "RUNNING_NETWORK", Instant.now(),
                                "network at net.pipe://localhost/pipe/private-event"),
                        new WorkerRunEvent(4, "COLLECTING", Instant.now(), "collecting")),
                networkResult(), null,
                List.of(new WorkerRunArtifact("jobs/" + runId + "/output/manifest.json",
                        Files.size(manifest), sha256(manifest), "application/json")),
                objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));

        dispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(runStore.find(runId).getResultContract()).isEqualTo("VALID_FULL");
        assertThat(runStore.find(runId).getResultJson()).contains("pipesim-network-result/1");
        var detail = runService.get(runId);
        assertThat(detail.result().toString())
                .doesNotContain("C:\\\\Users", "private-result", "net.pipe://");
        assertThat(detail.events()).allSatisfy(event -> {
            if (event.message() != null) assertThat(event.message()).doesNotContain("private-event", "net.pipe://localhost/pipe/private-event");
        });
        assertThat(runStore.events(runId)).anySatisfy(event -> {
            assertThat(event.getStatus()).isEqualTo("RUNNING_NETWORK");
            assertThat(event.getMessage()).isEqualTo("Worker 正在执行管网模拟");
        });
    }

    @Test
    void partialNetworkResultIsPersistedWithLimitedResultWarning() throws Exception {
        Seed seed = seed("READY", "models/network/partial/network.pips", "PIPESIM_NETWORK");
        long runId = runService.create(seed.version().getId(), request("Network Study", "network")).id();
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();

        Path manifest = STORAGE_ROOT.resolve("jobs/" + runId + "/output/manifest.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, """
                {"schemaVersion":"grdp-worker-artifact-manifest/1","runId":%d,
                 "generatedAtUtc":"2026-09-11T00:00:00Z","files":[]}
                """.formatted(runId));
        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "PARTIAL_SUCCEEDED", 4, "worker-1", "generation-1",
                List.of(
                        new WorkerRunEvent(1, "PREPARING", Instant.now(), "preparing"),
                        new WorkerRunEvent(2, "RUNNING_NETWORK", Instant.now(), "network"),
                        new WorkerRunEvent(3, "COLLECTING", Instant.now(), "collecting"),
                        new WorkerRunEvent(4, "PARTIAL_SUCCEEDED", Instant.now(), "limited result")), partialNetworkResult(), null,
                List.of(new WorkerRunArtifact("jobs/" + runId + "/output/manifest.json",
                        Files.size(manifest), sha256(manifest), "application/json")),
                objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));

        dispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("PARTIAL_SUCCEEDED");
        assertThat(runStore.find(runId).getResultContract()).isEqualTo("VALID_PARTIAL");
        assertThat(runStore.find(runId).getResultJson()).contains("pipesim-network-result/1");
        assertThat(runService.get(runId).error().path("code").asText()).isEqualTo("NETWORK_RESULT_LIMITED");
    }

    @Test
    void rejectsNetworkWorkerStatusAndResultContractMismatch() throws Exception {
        Seed seed = seed("READY", "models/network/mismatch/network.pips", "PIPESIM_NETWORK");
        long runId = runService.create(seed.version().getId(), request("Network Study", "network")).id();
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();
        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "SUCCEEDED", 1, "worker-1", "generation-1",
                List.of(), partialNetworkResult(), null, List.of(), null);

        dispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("FAILED");
        assertThat(runStore.find(runId).getResultJson()).isNull();
        assertThat(runService.get(runId).error().path("code").asText())
                .isEqualTo("INVALID_NETWORK_RESULT_CONTRACT");
    }

    @Test
    void rejectedNetworkResultsPersistAllowlistedSafeReasonClasses() throws Exception {
        ObjectNode schema = (ObjectNode) networkResult();
        schema.put("schemaVersion", "pipesim-network-result/2");
        assertNetworkContractFailure("schema", schema);

        ObjectNode topology = (ObjectNode) networkResult();
        ((ArrayNode) topology.path("topology").path("nodes")).removeAll();
        assertNetworkContractFailure("topology", topology);

        ObjectNode profile = (ObjectNode) networkResult();
        ((ArrayNode) profile.path("profiles")).removeAll();
        assertNetworkContractFailure("profile", profile);

        ObjectNode quality = (ObjectNode) networkResult();
        ((ArrayNode) quality.path("quality")).remove(0);
        assertNetworkContractFailure("quality", quality);

        ObjectNode study = (ObjectNode) networkResult();
        study.put("study", "C:\\private\\model.pips license=secret");
        assertNetworkContractFailure("study", study);

        ObjectNode numeric = (ObjectNode) networkResult();
        ((ObjectNode) numeric.path("system").get(0).path("values").get(0)).put("value", "100");
        assertNetworkContractFailure("numeric", numeric);
    }

    @Test
    void rejectedWellResultRetainsExistingGenericContractFailure() throws Exception {
        Seed seed = seed("READY", "models/well/invalid-contract.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();
        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "SUCCEEDED", 1, "worker-1", "generation-1",
                List.of(), partialResult(), null, List.of(), null);

        dispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("FAILED");
        assertThat(runService.get(runId).error().path("code").asText()).isEqualTo("RESULT_CONTRACT_INVALID");
        assertThat(runService.get(runId).error().has("reasonClass")).isFalse();
    }

    @Test
    void eclipseWorkerEnvelopePhaseValidatedResultArtifactsAndDiagnosticsArePersisted() throws Exception {
        Seed seed = seed("READY", "models/eclipse/1/CASE.DATA", "ECLIPSE_100");
        long runId = runService.create(seed.version().getId(), request(null, "eclipse")).id();
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();

        assertThat(fakeWorker.lastExecuteRequest).satisfies(request -> {
            assertThat(request.runId()).isEqualTo(runId);
            assertThat(request.modelStorageKey()).isEqualTo("models/eclipse/1/CASE.DATA");
            assertThat(request.expectedModelSha256()).isEqualTo("a".repeat(64));
            assertThat(request.study()).isNull();
            assertThat(request.runTask()).isEqualTo("eclipse");
            assertThat(request.parameters()).isNull();
            assertThat(request.timeoutSeconds()).isEqualTo(1800);
        });

        Path eclEnd = STORAGE_ROOT.resolve("jobs/" + runId + "/output/CASE.ECLEND");
        Files.createDirectories(eclEnd.getParent());
        Files.writeString(eclEnd, "ECLEND=0");
        String eclEndSha = sha256(eclEnd);
        Path normalizedResult = eclEnd.getParent().resolve("normalized-result.json");
        Files.writeString(normalizedResult, "{\"result\":\"sanitized\"}");
        Path runLog = eclEnd.getParent().resolve("run.log");
        Files.writeString(runLog, "SUCCEEDED\n");
        Path manifest = eclEnd.getParent().resolve("manifest.json");
        Files.writeString(manifest, """
                {"schemaVersion":"grdp-worker-artifact-manifest/1","runId":%d,"generatedAtUtc":"2026-09-10T00:00:00Z","files":[
                  {"storageKey":"jobs/%d/output/normalized-result.json","size":%d,"sha256":"%s","contentType":"application/json"},
                  {"storageKey":"jobs/%d/output/run.log","size":%d,"sha256":"%s","contentType":"text/plain"}]}
                """.formatted(runId, runId, Files.size(normalizedResult), sha256(normalizedResult),
                runId, Files.size(runLog), sha256(runLog)));
        JsonNode cleanup = objectMapper.readTree("""
                {"processTreeExitConfirmed":true,"licenseServer":"27000@private",
                 "details":{"workingDirectory":"C:\\\\Users\\\\operator\\\\private","note":"/home/operator/private/case"}}
                """);
        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "SUCCEEDED", 4, "worker-1", "generation-1",
                List.of(
                        new WorkerRunEvent(1, "PREPARING", Instant.now(), "deck C:\\Users\\operator\\CASE.DATA"),
                        new WorkerRunEvent(2, "RUNNING_ECLIPSE", Instant.now(), "licenseServer=27000@private"),
                        new WorkerRunEvent(3, "COLLECTING", Instant.now(), "collecting"),
                        new WorkerRunEvent(4, "SUCCEEDED", Instant.now(), "done")),
                eclipseResult(Files.size(eclEnd), eclEndSha), null,
                List.of(
                        new WorkerRunArtifact("jobs/" + runId + "/output/normalized-result.json",
                                Files.size(normalizedResult), sha256(normalizedResult), "application/json"),
                        new WorkerRunArtifact("jobs/" + runId + "/output/run.log",
                                Files.size(runLog), sha256(runLog), "text/plain"),
                        new WorkerRunArtifact("jobs/" + runId + "/output/manifest.json",
                                Files.size(manifest), sha256(manifest), "application/json")), cleanup);

        dispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(runStore.find(runId).getResultContract()).isEqualTo("VALID_FULL");
        assertThat(runStore.artifacts(runId)).extracting(artifact -> artifact.getArtifactName())
                .containsExactlyInAnyOrder("normalized-result.json", "run.log", "manifest.json")
                .doesNotContain("CASE.ECLEND");
        var detail = runService.get(runId);
        assertThat(detail.result().path("summary").path("series").get(0).path("keyword").asText()).isEqualTo("FOPR");
        assertThat(detail.result().path("summary").path("series").get(0).path("points").toString())
                .isEqualTo("[{\"timeDays\":0.0,\"value\":2.0},{\"timeDays\":1.0,\"value\":1.0}]");
        assertThat(detail.result().toString()).doesNotContain("operator", "private-token");
        assertThat(detail.cleanup().toString()).contains("[redacted]", "[local path]")
                .doesNotContain("27000@private", "operator");
        assertThat(detail.events()).allSatisfy(event -> {
            if (event.message() != null) assertThat(event.message()).doesNotContain("operator", "27000@private");
        });
        assertThat(runStore.events(runId)).anySatisfy(event ->
                assertThat(event.getStatus()).isEqualTo("RUNNING_ECLIPSE"));
        assertThat(fakeWorker.transactionActiveDuringHttp).isFalse();
    }

    @Test
    void eclipseRejectsPartialAndSanitizesFailedErrorDetailsWithoutPersistingSuccessResult() throws Exception {
        Seed seed = seed("READY", "models/eclipse/failure/CASE.DATA", "ECLIPSE_100");
        long partialRun = runService.create(seed.version().getId(), request(null, "eclipse")).id();
        SoftwareIntegrationRunDispatcher partialDispatcher = dispatcher();
        partialDispatcher.dispatch();
        fakeWorker.snapshot = new WorkerRunSnapshot(partialRun, "PARTIAL_SUCCEEDED", 1,
                "worker-1", "generation-1", List.of(), eclipseResult(8, "a".repeat(64)), null,
                List.of(), objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));
        partialDispatcher.poll();
        assertThat(runStore.find(partialRun).getStatus()).isEqualTo("FAILED");
        assertThat(runStore.find(partialRun).getResultJson()).isNull();
        assertThat(runStore.find(partialRun).getResultContract()).isNull();
        assertThat(runStore.artifacts(partialRun)).isEmpty();

        long failedRun = runService.create(seed.version().getId(), request(null, "eclipse")).id();
        SoftwareIntegrationRunDispatcher failedDispatcher = dispatcher();
        failedDispatcher.dispatch();
        JsonNode error = objectMapper.readTree("""
                {"category":"SOLVER","code":"ECLIPSE_SOLVER_FAILED","message":"failed at C:\\\\Users\\\\operator\\\\CASE.DATA",
                 "retryable":false,"details":{"errors":2,"licenseServer":"27000@private","nested":[{"command":"eclrun secret"}]}}
                """);
        fakeWorker.snapshot = new WorkerRunSnapshot(failedRun, "FAILED", 1,
                "worker-1", "generation-1", List.of(), eclipseResult(8, "a".repeat(64)), error,
                List.of(), objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));
        failedDispatcher.poll();
        assertThat(runStore.find(failedRun).getStatus()).isEqualTo("FAILED");
        assertThat(runStore.find(failedRun).getResultJson()).isNull();
        assertThat(runService.get(failedRun).error().path("details").path("errors").asInt()).isEqualTo(2);
        assertThat(runService.get(failedRun).error().toString()).contains("[redacted]", "[local path]")
                .doesNotContain("operator", "27000@private", "eclrun secret");

        long invalidPhaseRun = runService.create(seed.version().getId(), request(null, "eclipse")).id();
        SoftwareIntegrationRunDispatcher invalidPhaseDispatcher = dispatcher();
        invalidPhaseDispatcher.dispatch();
        fakeWorker.snapshot = new WorkerRunSnapshot(invalidPhaseRun, "PREPARING", 1,
                "worker-1", "generation-1",
                List.of(new WorkerRunEvent(1, "RUNNING_NETWORK", Instant.now(), "wrong phase")),
                null, null, List.of(), null);
        invalidPhaseDispatcher.poll();
        assertThat(runStore.find(invalidPhaseRun).getStatus()).isEqualTo("WORKER_LOST");
        assertThat(runStore.events(invalidPhaseRun)).noneSatisfy(event ->
                assertThat(event.getWorkerSequence()).isEqualTo(1));

        long busyRun = runService.create(seed.version().getId(), request(null, "eclipse")).id();
        JsonNode busyError = objectMapper.readTree("""
                {"category":"COORDINATION","code":"WORKER_BUSY","message":"busy at C:\\\\Users\\\\operator\\\\CASE.DATA",
                 "retryable":true,"details":{"licenseServer":"27000@private"}}
                """);
        fakeWorker.executeError = new WorkerClientException("busy", 409, busyError);
        dispatcher().dispatch();
        assertThat(runStore.find(busyRun).getStatus()).isEqualTo("QUEUED");
        assertThat(runStore.events(busyRun)).filteredOn(event -> "REQUEUED".equals(event.getEventType()))
                .singleElement().satisfies(event -> assertThat(event.getErrorJson())
                        .contains("[redacted]", "[local path]")
                        .doesNotContain("operator", "27000@private"));
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
    void expiredResultPayloadIsHiddenAndCleanedWhileRunAuditRemains() throws Exception {
        Seed seed = seed("READY", "models/1/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        assertThat(runStore.claimOldest("retention-dispatcher").getId()).isEqualTo(runId);
        runStore.acceptWorker(runId, "worker-1", "generation-1");
        runStore.transition(runId, SoftwareIntegrationRunStatus.PREPARING, null, "preparing", null);
        runStore.transition(runId, SoftwareIntegrationRunStatus.COLLECTING, null, "collecting", null);
        JsonNode result = objectMapper.readTree("{\"schemaVersion\":\"pipesim-well-result/1\",\"ipr\":[{\"flow\":1.0}]}" );
        assertThat(runStore.complete(runId, SoftwareIntegrationRunStatus.SUCCEEDED, "VALID_COMPLETE", result,
                null, objectMapper.readTree("{\"processTreeExitConfirmed\":true}"), null)).isTrue();

        runMapper.update(null, new LambdaUpdateWrapper<SoftwareIntegrationRunEntity>()
                .eq(SoftwareIntegrationRunEntity::getId, runId)
                .set(SoftwareIntegrationRunEntity::getResultExpiresAt, LocalDateTime.now().minusSeconds(1)));

        var expired = runService.get(runId);
        assertThat(expired.resultExpired()).isTrue();
        assertThat(expired.result()).isNull();
        assertThat(expired.resultContract()).isEqualTo("VALID_COMPLETE");

        resultCleanup.cleanupExpiredResults();
        assertThat(runStore.find(runId).getResultJson()).isNull();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(runStore.events(runId)).isNotEmpty();
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
        MockMvc projectMvc = MockMvcBuilders.standaloneSetup(new SoftwareIntegrationController(softwareIntegrationService, capabilityService))
                .setControllerAdvice(new SoftwareIntegrationRunExceptionHandler()).build();

        projectMvc.perform(delete("/software-integration/projects/{id}", seed.project().getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
        assertThat(projectMapper.selectById(seed.project().getId()).getDeletedAt()).isNull();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("CLAIMED");
    }

    @Test
    void completedProjectDeletionSoftDeletesProjectAndRetainsModelHistory() throws Exception {
        Seed seed = seed("READY", "models/delete/completed.pips");
        MockMvc projectMvc = MockMvcBuilders.standaloneSetup(
                        new SoftwareIntegrationController(softwareIntegrationService, capabilityService))
                .setControllerAdvice(new SoftwareIntegrationRunExceptionHandler()).build();

        projectMvc.perform(delete("/software-integration/projects/{id}", seed.project().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(projectMapper.selectById(seed.project().getId()).getDeletedAt()).isNotNull();
        assertThat(softwareIntegrationService.listProjects())
                .noneMatch(project -> project.id().equals(seed.project().getId()));
        assertThat(modelMapper.selectById(seed.model().getId()).getDeletedAt()).isNull();
        assertThat(versionMapper.selectById(seed.version().getId()).getStatus()).isEqualTo("READY");
        assertThatThrownBy(() -> softwareIntegrationService.getProject(seed.project().getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(404));

        projectMvc.perform(get("/software-integration/recycle-bin/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(seed.project().getId()));
        projectMvc.perform(post("/software-integration/recycle-bin/projects/{id}/restore", seed.project().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.project.id").value(seed.project().getId()))
                .andExpect(jsonPath("$.data.models[0].versions[0].status").value("READY"));
        assertThat(softwareIntegrationService.listProjects())
                .anyMatch(project -> project.id().equals(seed.project().getId()));
        assertThat(softwareIntegrationService.getProject(seed.project().getId()).models()).hasSize(1);
    }

    @Test
    void modelDeletionRemovesOwnedHistoryAndStorageButKeepsProject() throws Exception {
        Seed seed = seed("READY", "models/model-delete/1/model.pips");
        String versionRootKey = "models/" + seed.model().getId() + "/1";
        String modelStorageKey = versionRootKey + "/model.pips";
        versionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SoftwareIntegrationModelVersionEntity>()
                .eq(SoftwareIntegrationModelVersionEntity::getId, seed.version().getId())
                .set(SoftwareIntegrationModelVersionEntity::getStorageKey, modelStorageKey));
        Path versionRoot = normalizer.resolve(versionRootKey);
        Files.createDirectories(versionRoot);
        Files.writeString(versionRoot.resolve("model.pips"), "model");
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        runService.cancel(runId);
        Path artifactRoot = normalizer.resolve("artifacts/" + runId);
        Files.createDirectories(artifactRoot);
        Files.writeString(artifactRoot.resolve("result.json"), "artifact");
        MockMvc projectMvc = MockMvcBuilders.standaloneSetup(
                        new SoftwareIntegrationController(softwareIntegrationService, capabilityService))
                .setControllerAdvice(new SoftwareIntegrationRunExceptionHandler()).build();

        projectMvc.perform(delete("/software-integration/projects/{projectId}/models/{modelId}",
                        seed.project().getId(), seed.model().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(projectMapper.selectById(seed.project().getId())).isNotNull();
        assertThat(modelMapper.selectById(seed.model().getId())).isNull();
        assertThat(versionMapper.selectById(seed.version().getId())).isNull();
        assertThat(runMapper.selectById(runId)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM software_integration_run_event WHERE run_id = ?", Integer.class, runId)).isZero();
        assertThat(Files.exists(versionRoot)).isFalse();
        assertThat(Files.exists(artifactRoot)).isFalse();
        assertThat(softwareIntegrationService.getProject(seed.project().getId()).models()).isEmpty();
    }

    @Test
    void activeRunBlocksModelDeletionWithScoped409() throws Exception {
        Seed seed = seed("READY", "models/model-delete-active/1/model.pips");
        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        assertThat(runStore.claimOldest("model-delete-dispatcher").getId()).isEqualTo(runId);
        MockMvc projectMvc = MockMvcBuilders.standaloneSetup(
                        new SoftwareIntegrationController(softwareIntegrationService, capabilityService))
                .setControllerAdvice(new SoftwareIntegrationRunExceptionHandler()).build();

        projectMvc.perform(delete("/software-integration/projects/{projectId}/models/{modelId}",
                        seed.project().getId(), seed.model().getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
        assertThat(modelMapper.selectById(seed.model().getId())).isNotNull();
        assertThat(runStore.find(runId).getStatus()).isEqualTo("CLAIMED");
    }

    @Test
    void modelDeletionRefusesStorageKeyOutsideItsOwnedVersionRoot() throws Exception {
        Seed seed = seed("READY", "models/not-owned/1/model.pips");
        assertThatThrownBy(() -> softwareIntegrationService.deleteModel(seed.project().getId(), seed.model().getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(500));
        assertThat(modelMapper.selectById(seed.model().getId())).isNotNull();
        assertThat(versionMapper.selectById(seed.version().getId())).isNotNull();
    }

    @Test
    void expiredProjectCleanupRemovesOnlyOwnedModelAndRunStorage() throws Exception {
        Seed seed = seed("READY", "models/cleanup/placeholder.pips");
        String versionRootKey = "models/" + seed.model().getId() + "/" + seed.version().getVersionNo();
        String modelKey = versionRootKey + "/model.pips";
        versionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SoftwareIntegrationModelVersionEntity>()
                .eq(SoftwareIntegrationModelVersionEntity::getId, seed.version().getId())
                .set(SoftwareIntegrationModelVersionEntity::getStorageKey, modelKey));
        Path versionRoot = normalizer.resolve(versionRootKey);
        Files.createDirectories(versionRoot);
        Files.writeString(versionRoot.resolve("model.pips"), "model");

        long runId = runService.create(seed.version().getId(), request("Study 1", "nodal")).id();
        runService.cancel(runId);
        Path artifactRoot = normalizer.resolve("artifacts/" + runId);
        Files.createDirectories(artifactRoot);
        Files.writeString(artifactRoot.resolve("result.json"), "artifact");

        softwareIntegrationService.deleteProject(seed.project().getId());
        jdbcTemplate.update("UPDATE software_integration_project SET deleted_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(31), seed.project().getId());

        assertThat(projectCleanup.cleanupExpiredProjectCount()).isEqualTo(1);
        assertThat(projectMapper.selectById(seed.project().getId())).isNull();
        assertThat(modelMapper.selectById(seed.model().getId())).isNull();
        assertThat(versionMapper.selectById(seed.version().getId())).isNull();
        assertThat(runMapper.selectById(runId)).isNull();
        assertThat(Files.exists(versionRoot)).isFalse();
        assertThat(Files.exists(artifactRoot)).isFalse();
    }

    @Test
    void runningNetworkRetainsGlobalSlotBlocksDeletionAndRecoversAsWorkerLost() throws Exception {
        Seed network = seed("READY", "models/network/recovery.pips", "PIPESIM_NETWORK");
        long networkRunId = runService.create(network.version().getId(), request("Network Study", "network")).id();
        assertThat(runStore.claimOldest("network-recovery-dispatcher").getId()).isEqualTo(networkRunId);
        runStore.acceptWorker(networkRunId, "worker-1", "generation-1");
        runStore.transition(networkRunId, SoftwareIntegrationRunStatus.PREPARING, null, "preparing", null);
        runStore.transition(networkRunId, SoftwareIntegrationRunStatus.RUNNING_NETWORK, null, "network", null);

        Seed well = seed("READY", "models/well/queued.pips");
        long queuedRunId = runService.create(well.version().getId(), request("Study 1", "nodal")).id();
        assertThat(runStore.claimOldest("blocked-by-network")).isNull();
        MockMvc projectMvc = MockMvcBuilders.standaloneSetup(new SoftwareIntegrationController(softwareIntegrationService, capabilityService))
                .setControllerAdvice(new SoftwareIntegrationRunExceptionHandler()).build();
        projectMvc.perform(delete("/software-integration/projects/{id}", network.project().getId()))
                .andExpect(status().isConflict());

        runStore.recoverOnStartup();

        assertThat(runStore.find(networkRunId).getStatus()).isEqualTo("WORKER_LOST");
        assertThat(runStore.claimOldest("after-network-recovery").getId()).isEqualTo(queuedRunId);
    }

    @Test
    void runningEclipseRetainsGlobalSlotBlocksDeletionAndRecoversAsWorkerLost() throws Exception {
        Seed eclipse = seed("READY", "models/eclipse/recovery/CASE.DATA", "ECLIPSE_100");
        long eclipseRunId = runService.create(eclipse.version().getId(), request(null, "eclipse")).id();
        assertThat(runStore.claimOldest("eclipse-recovery-dispatcher").getId()).isEqualTo(eclipseRunId);
        runStore.acceptWorker(eclipseRunId, "worker-1", "generation-1");
        runStore.transition(eclipseRunId, SoftwareIntegrationRunStatus.PREPARING, null, "preparing", null);
        runStore.transition(eclipseRunId, SoftwareIntegrationRunStatus.RUNNING_ECLIPSE, null, "eclipse", null);
        assertThat(runStore.find(eclipseRunId).getStatus()).isEqualTo("RUNNING_ECLIPSE");

        Seed well = seed("READY", "models/well/queued-after-eclipse.pips");
        long queuedRunId = runService.create(well.version().getId(), request("Study 1", "nodal")).id();
        assertThat(runStore.claimOldest("blocked-by-eclipse")).isNull();
        MockMvc projectMvc = MockMvcBuilders.standaloneSetup(
                        new SoftwareIntegrationController(softwareIntegrationService, capabilityService))
                .setControllerAdvice(new SoftwareIntegrationRunExceptionHandler()).build();
        projectMvc.perform(delete("/software-integration/projects/{id}", eclipse.project().getId()))
                .andExpect(status().isConflict());
        runStore.transition(eclipseRunId, SoftwareIntegrationRunStatus.COLLECTING, null, "collecting", null);

        runStore.recoverOnStartup();

        assertThat(runStore.find(eclipseRunId).getStatus()).isEqualTo("WORKER_LOST");
        assertThat(runStore.find(eclipseRunId).getResultJson()).isNull();
        assertThat(runStore.claimOldest("after-eclipse-recovery").getId()).isEqualTo(queuedRunId);
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
                runStore, versionMapper, normalizer, fakeWorker, resultValidator, eclipseResultValidator,
                artifactPublisher, integrationProperties, objectMapper);
        dispatcher.recover();
        return dispatcher;
    }

    private Seed seed(String status, String storageKey) throws IOException {
        return seed(status, storageKey, "PIPESIM_WELL");
    }

    private Seed seed(String status, String storageKey, String simulatorType) throws IOException {
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
        model.setSimulatorType(simulatorType);
        model.setCreatedAt(now);
        model.setUpdatedAt(now);
        modelMapper.insert(model);
        SoftwareIntegrationModelVersionEntity version = new SoftwareIntegrationModelVersionEntity();
        version.setModelId(model.getId());
        version.setVersionNo(1);
        boolean eclipse = "ECLIPSE_100".equals(simulatorType);
        version.setOriginalName(eclipse ? "CASE.DATA" : "model.pips");
        version.setStorageKey(storageKey);
        version.setSha256("a".repeat(64));
        version.setSizeBytes(5L);
        version.setStatus(status);
        version.setModelKind(eclipse ? "eclipse_100"
                : ("PIPESIM_NETWORK".equals(simulatorType) ? "network" : "black_oil_liquid"));
        version.setStudiesJson(eclipse ? null : "Study 1\nStudy 2\nNetwork Study");
        version.setInspectionJson(eclipse ? validEclipseInspection() : null);
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

    @Test
    void verifiedRestartForecastRejectsStaleReportsAndChangedStateWithoutCreatingRuns() throws Exception {
        Seed eclipse = seed("READY", "models/eclipse/restart/EX5.DATA", "ECLIPSE_100");
        try (var input = getClass().getResourceAsStream("/eclipse-restart-inspection.json")) {
            eclipse.version().setInspectionJson(objectMapper.writeValueAsString(objectMapper.readTree(input)));
        }
        versionMapper.updateById(eclipse.version());
        var baselineId = runService.create(eclipse.version().getId(), request(null, "eclipse")).id();
        var baseline = runStore.find(baselineId); baseline.setStatus("SUCCEEDED"); baseline.setResultContract("VALID_FULL"); baseline.setParametersJson("null"); runMapper.updateById(baseline);
        ObjectNode source;
        try (var input = getClass().getResourceAsStream("/eclipse-restart-source-catalog.json")) { source = (ObjectNode)objectMapper.readTree(input); }
        // Small published-file fixture tests the HTTP/storage binding. Commercial state proof is separate.
        Path statePath = normalizer.resolve("artifacts/" + baselineId + "/eclipse-output-EX5.FUNRST"); Files.createDirectories(statePath.getParent()); Files.writeString(statePath, "state");
        var catalog = (ObjectNode)source.path("catalog"); var selected = ((ObjectNode)catalog.path("reports").get(80)).deepCopy();
        selected.put("byteOffset", 0).put("sizeBytes", 5).put("sha256", sha256(statePath));
        catalog.putArray("reports").add(selected); catalog.put("artifactSizeBytes", 5).put("artifactSha256", sha256(statePath));
        Path catalogPath = statePath.resolveSibling("eclipse-restart-catalog.json"); Files.write(catalogPath, objectMapper.writeValueAsBytes(source));
        for (var path : List.of(statePath, catalogPath)) {
            var artifact = new SoftwareIntegrationArtifactEntity(); artifact.setRunId(baselineId); artifact.setArtifactName(path.getFileName().toString());
            artifact.setArtifactType("output"); artifact.setContentType(path.equals(statePath) ? "application/octet-stream" : "application/json");
            artifact.setStorageKey("artifacts/" + baselineId + "/" + path.getFileName()); artifact.setSizeBytes(Files.size(path)); artifact.setSha256(sha256(path));
            artifact.setCreatedAt(LocalDateTime.now()); artifact.setExpiresAt(LocalDateTime.now().plusDays(30)); artifactMapper.insert(artifact);
        }
        var parameters = objectMapper.createObjectNode().put("schemaVersion", "eclipse-history-forecast-parameters/2").put("historyRunId", baselineId)
                .put("forecastDataFile", "FOREA.DATA").put("restartArtifactName", "eclipse-output-EX5.FUNRST").put("restartArtifactSha256", sha256(statePath))
                .put("restartReport", 80).put("restartTimeDays", 2190).put("restartDate", "1975-12-31").put("physicsSha256", source.path("physicsSha256").asText());
        var valid = request(null, "eclipse"); valid.setParameters(parameters);
        assertThat(runService.create(eclipse.version().getId(), valid).parameters().path("schemaVersion").asText()).isEqualTo("eclipse-history-forecast-parameters/2");
        int before = runStore.listByVersion(eclipse.version().getId(), 100).size();
        for (var fault : new String[] {"restartReport", "restartTimeDays", "restartDate", "physicsSha256", "forecastDataFile"}) {
            var p = parameters.deepCopy(); switch (fault) { case "restartReport" -> p.put(fault, 79); case "restartTimeDays" -> p.put(fault, 2191);
                case "restartDate" -> p.put(fault, "1975-12-30"); case "forecastDataFile" -> p.put(fault, "OTHER.DATA"); default -> p.put(fault, "b".repeat(64)); }
            var bad = request(null, "eclipse"); bad.setParameters(p);
            assertThatThrownBy(() -> runService.create(eclipse.version().getId(), bad)).isInstanceOf(RunException.class)
                    .satisfies(e -> assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
            assertThat(runStore.listByVersion(eclipse.version().getId(), 100)).hasSize(before);
        }
        Files.writeString(statePath, "other");
        assertThatThrownBy(() -> runService.create(eclipse.version().getId(), valid)).isInstanceOf(RunException.class)
                .satisfies(e -> assertThat(((RunException)e).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(runStore.listByVersion(eclipse.version().getId(), 100)).hasSize(before);
    }

    @Test
    void verifiedBinaryRestartForecastRejectsStaleReportsAndChangedStateWithoutCreatingRuns() throws Exception {
        Seed eclipse = seed("READY", "models/eclipse/restart/EX5.DATA", "ECLIPSE_100");
        try (var input = getClass().getResourceAsStream("/eclipse-binary-restart-inspection.json")) {
            eclipse.version().setInspectionJson(objectMapper.writeValueAsString(objectMapper.readTree(input)));
        }
        versionMapper.updateById(eclipse.version());
        var baselineId = runService.create(eclipse.version().getId(), request(null, "eclipse")).id();
        var baseline = runStore.find(baselineId); baseline.setStatus("SUCCEEDED"); baseline.setResultContract("VALID_FULL"); baseline.setParametersJson("null"); runMapper.updateById(baseline);
        ObjectNode source;
        try (var input = getClass().getResourceAsStream("/eclipse-binary-restart-source-catalog.json")) { source = (ObjectNode)objectMapper.readTree(input); }
        // Small published-file fixture tests the HTTP/storage binding. Commercial state proof is separate.
        Path statePath = normalizer.resolve("artifacts/" + baselineId + "/eclipse-output-EX5.UNRST"); Files.createDirectories(statePath.getParent()); Files.writeString(statePath, "state");
        var catalog = (ObjectNode)source.path("catalog"); var selected = ((ObjectNode)catalog.path("reports").get(80)).deepCopy();
        selected.put("byteOffset", 0).put("sizeBytes", 5).put("sha256", sha256(statePath));
        catalog.putArray("reports").add(selected); catalog.put("artifactSizeBytes", 5).put("artifactSha256", sha256(statePath));
        Path catalogPath = statePath.resolveSibling("eclipse-restart-catalog.json"); Files.write(catalogPath, objectMapper.writeValueAsBytes(source));
        for (var path : List.of(statePath, catalogPath)) {
            var artifact = new SoftwareIntegrationArtifactEntity(); artifact.setRunId(baselineId); artifact.setArtifactName(path.getFileName().toString());
            artifact.setArtifactType("output"); artifact.setContentType(path.equals(statePath) ? "application/octet-stream" : "application/json");
            artifact.setStorageKey("artifacts/" + baselineId + "/" + path.getFileName()); artifact.setSizeBytes(Files.size(path)); artifact.setSha256(sha256(path));
            artifact.setCreatedAt(LocalDateTime.now()); artifact.setExpiresAt(LocalDateTime.now().plusDays(30)); artifactMapper.insert(artifact);
        }
        var parameters = objectMapper.createObjectNode().put("schemaVersion", "eclipse-history-forecast-parameters/3").put("historyRunId", baselineId)
                .put("forecastDataFile", "FOREA.DATA").put("restartArtifactName", "eclipse-output-EX5.UNRST").put("restartArtifactSha256", sha256(statePath))
                .put("restartReport", 80).put("restartTimeDays", 2190).put("restartDate", "1975-12-31").put("physicsSha256", source.path("physicsSha256").asText());
        var valid = request(null, "eclipse"); valid.setParameters(parameters);
        assertThat(runService.create(eclipse.version().getId(), valid).parameters().path("schemaVersion").asText()).isEqualTo("eclipse-history-forecast-parameters/3");
        int before = runStore.listByVersion(eclipse.version().getId(), 100).size();
        for (var fault : new String[] {"restartReport", "restartTimeDays", "restartDate", "physicsSha256", "forecastDataFile"}) {
            var p = parameters.deepCopy(); switch (fault) { case "restartReport" -> p.put(fault, 79); case "restartTimeDays" -> p.put(fault, 2191);
                case "restartDate" -> p.put(fault, "1975-12-30"); case "forecastDataFile" -> p.put(fault, "OTHER.DATA"); default -> p.put(fault, "b".repeat(64)); }
            var bad = request(null, "eclipse"); bad.setParameters(p);
            assertThatThrownBy(() -> runService.create(eclipse.version().getId(), bad)).isInstanceOf(RunException.class)
                    .satisfies(e -> assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
            assertThat(runStore.listByVersion(eclipse.version().getId(), 100)).hasSize(before);
        }
        Files.writeString(statePath, "other");
        assertThatThrownBy(() -> runService.create(eclipse.version().getId(), valid)).isInstanceOf(RunException.class)
                .satisfies(e -> assertThat(((RunException)e).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(runStore.listByVersion(eclipse.version().getId(), 100)).hasSize(before);
    }

    private record BatchSeed(Seed seed,long baselineId,ObjectNode request) {}
    private BatchSeed eclipseBatchSeed()throws Exception {
        var seed=seed("READY","models/eclipse/batch/CASE.DATA","ECLIPSE_100");
        var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-pvdo-inspection.json"));
        seed.version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.version());
        long id=runService.create(seed.version().getId(),request(null,"eclipse")).id();var baseline=runStore.find(id);
        baseline.setStatus("SUCCEEDED");baseline.setResultContract("VALID_FULL");baseline.setResultJson(objectMapper.writeValueAsString(eclipseResult(0,"a".repeat(64))));baseline.setResultExpiresAt(LocalDateTime.now().plusDays(1));runMapper.updateById(baseline);
        var request=objectMapper.createObjectNode();request.put("requestKey",UUID.randomUUID().toString());request.put("name","油黏度批量方案");request.put("baselineRunId",id);var cases=request.putArray("cases");var c=inspection.path("oilPvtContext");
        for(int m=2;m<=3;m++){var item=cases.addObject();item.put("name","油黏度×"+m);var p=item.putObject("parameters");p.put("schemaVersion","eclipse-pvdo-parameters/1");p.put("baselineRunId",id);p.put("sourceFile",c.path("sourceFile").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.set("keywordLineNumber",c.path("keywordLineNumber").deepCopy());p.put("unit","cP");p.put("multiplier",m);p.set("rows",c.path("rows").deepCopy());}
        return new BatchSeed(seed,id,request);
    }
    @Test void eclipseBatchPersistsEveryChildAndRepeatedSubmissionDoesNotDuplicateRuns()throws Exception {
        var seed=eclipseBatchSeed();var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());
        assertThat(batch.members()).hasSize(2);assertThat(batch.status()).isEqualTo("QUEUED");assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(3);
        var replay=eclipseBatches.create(seed.seed().version().getId(),seed.request().deepCopy());assertThat(replay.id()).isEqualTo(batch.id());assertThat(replay.members().stream().map(m->m.run().id())).containsExactlyElementsOf(batch.members().stream().map(m->m.run().id()).toList());assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(3);
        assertThat(eclipseBatches.list(seed.seed().version().getId())).hasSize(1);assertThat(eclipseBatches.list(seed.seed().version().getId()).get(0).caseCount()).isEqualTo(2);assertThat(eclipseBatches.list(seed.seed().version().getId()).get(0).status()).isEqualTo("QUEUED");assertThat(eclipseBatches.get(batch.id()).baselineRunId()).isEqualTo(seed.baselineId());
        var conflicting=seed.request().deepCopy();conflicting.put("name","另一内容");assertThatThrownBy(()->eclipseBatches.create(seed.seed().version().getId(),conflicting)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.CONFLICT));
    }
    @Test void eclipseOilVolumeAndViscosityShareBaselineButPersistDistinctTypedScenarios()throws Exception {
        var seed=eclipseBatchSeed();var oil=(ObjectNode)seed.request().path("cases").path(0).path("parameters");oil.put("schemaVersion","eclipse-pvdo-parameters/2");oil.put("field","FORMATION_VOLUME_FACTOR");oil.put("unit","rb/stb");oil.put("multiplier",1.02);
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(oil.toString());assertThat(batch.members().get(1).run().parameters().path("schemaVersion").asText()).isEqualTo("eclipse-pvdo-parameters/1");
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","multiplier","sourceSha256","field"}){var bad=oil.deepCopy();switch(fault){case "unit"->bad.put(fault,"rm3/sm3");case "multiplier"->bad.put(fault,8);case "field"->bad.put(fault,"PRESSURE");default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseGasVolumeAndViscosityBindDryGasSourceWithDistinctFieldUnits()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-pvdg-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var context=inspection.path("gasPvtContext");
        for(int i=0;i<2;i++){var p=(ObjectNode)seed.request().path("cases").path(i).path("parameters");p.put("sourceFile",context.path("sourceFile").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.set("keywordLineNumber",context.path("keywordLineNumber").deepCopy());p.set("rows",context.path("rows").deepCopy());p.put("schemaVersion",i==0?"eclipse-pvdg-parameters/2":"eclipse-pvdg-parameters/1");p.put("unit",i==0?"rb/Mscf":"cP");p.put("multiplier",i==0?1.02:2);if(i==0)p.put("field","FORMATION_VOLUME_FACTOR");}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());assertThat(batch.members().get(1).run().parameters().path("schemaVersion").asText()).isEqualTo("eclipse-pvdg-parameters/1");
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","multiplier","sourceSha256","field"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"rb/stb");case "multiplier"->bad.put(fault,101);case "field"->bad.put(fault,"PRESSURE");default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseLiveOilBatchBindsAllBranchesAndRejectsMissingUndersaturationWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-pvto-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var context=inspection.path("liveOilPvtContext");
        for(int i=0;i<2;i++){var p=(ObjectNode)seed.request().path("cases").path(i).path("parameters");p.remove("rows");p.put("sourceFile",context.path("sourceFile").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.set("keywordLineNumber",context.path("keywordLineNumber").deepCopy());p.set("records",context.path("records").deepCopy());p.put("schemaVersion","eclipse-pvto-parameters/1");p.put("field",i==0?"FORMATION_VOLUME_FACTOR":"VISCOSITY");p.put("unit",i==0?"rb/stb":"cP");p.put("multiplier",i==0?1.02:2);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","multiplier","sourceSha256","field","records"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"rm3/sm3");case "multiplier"->bad.put(fault,8);case "field"->bad.put(fault,"PRESSURE");case "records"->((tools.jackson.databind.node.ArrayNode)bad.path("records").path(8).path("rows")).remove(1);default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseWetGasBatchBindsAllBranchesAndRejectsMissingHighestPressureBranchWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-pvtg-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var context=inspection.path("wetGasPvtContext");
        for(int i=0;i<2;i++){var p=(ObjectNode)seed.request().path("cases").path(i).path("parameters");p.remove("rows");p.put("sourceFile",context.path("sourceFile").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.set("keywordLineNumber",context.path("keywordLineNumber").deepCopy());p.set("records",context.path("records").deepCopy());p.put("schemaVersion","eclipse-pvtg-parameters/1");p.put("field",i==0?"FORMATION_VOLUME_FACTOR":"VISCOSITY");p.put("unit",i==0?"rm3/sm3":"cP");p.put("multiplier",i==0?1.02:2);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","multiplier","sourceSha256","field","records"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"rb/Mscf");case "multiplier"->bad.put(fault,101);case "field"->bad.put(fault,"PRESSURE");case "records"->((tools.jackson.databind.node.ArrayNode)bad.path("records").path(12).path("rows")).removeAll();default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseGasRelpermBatchBindsCapillaryPressureAndRejectsSourceEditsWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-sgfn-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var context=inspection.path("relpermContext").path("tables").path(1);
        for(int i=0;i<2;i++){var p=(ObjectNode)seed.request().path("cases").path(i).path("parameters");p.remove("field");p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.set("keywordLineNumber",context.path("keywordLineNumber").deepCopy());p.set("rows",context.path("rows").deepCopy());p.put("schemaVersion","eclipse-relperm-parameters/2");p.put("keyword","SGFN");p.put("unit","1");p.put("multiplier",i==0?.5:.75);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","multiplier","sourceSha256","schemaVersion","rows"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"cP");case "multiplier"->bad.put(fault,2);case "schemaVersion"->bad.put(fault,"eclipse-relperm-parameters/1");case "rows"->((tools.jackson.databind.node.ArrayNode)bad.path("rows")).removeAll();default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseCoupledRelpermBatchBindsBothTablesAndRejectsInvalidWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-coupled-relperm-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var context=inspection.path("coupledRelpermContext");
        for(int i=0;i<2;i++){var p=(ObjectNode)seed.request().path("cases").path(i).path("parameters");p.remove("field");p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.remove("rows");p.remove("keywordLineNumber");p.set("tables",context.path("tables").deepCopy());p.put("schemaVersion","eclipse-coupled-relperm-parameters/1");p.put("field",i==0?"WATER_KR":"OIL_KR");p.put("unit","1");p.put("multiplier",i==0?.5:.75);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","multiplier","sourceSha256","schemaVersion","tables"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"cP");case "multiplier"->bad.put(fault,3);case "schemaVersion"->bad.put(fault,"eclipse-relperm-parameters/1");case "tables"->((tools.jackson.databind.node.ArrayNode)bad.path("tables")).removeAll();default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseMultiSatRelpermBatchBindsBothTablesAndRejectsInvalidWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-sat-relperm-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var context=inspection.path("multiSatRelpermContext").path("regions").path(1);
        for(int i=0;i<2;i++){var p=(ObjectNode)seed.request().path("cases").path(i).path("parameters");p.remove("field");p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.remove("rows");p.remove("keywordLineNumber");p.set("tables",context.path("tables").deepCopy());p.put("schemaVersion","eclipse-multi-sat-relperm-parameters/1");p.put("regionNumber",2);p.put("field",i==0?"WATER_KR":"OIL_KR");p.put("unit","1");p.put("multiplier",i==0?.5:.75);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","multiplier","sourceSha256","schemaVersion","tables"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"cP");case "multiplier"->bad.put(fault,3);case "schemaVersion"->bad.put(fault,"eclipse-relperm-parameters/1");case "tables"->((tools.jackson.databind.node.ArrayNode)bad.path("tables")).removeAll();default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseMultiSatFamilyIIBatchBindsBothTablesAndRejectsInvalidWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-sat-family-ii-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var context=inspection.path("multiSatRelpermContext").path("regions").path(1);
        for(int i=0;i<2;i++){var p=(ObjectNode)seed.request().path("cases").path(i).path("parameters");p.remove("field");p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.remove("rows");p.remove("keywordLineNumber");p.set("tables",context.path("tables").deepCopy());p.put("schemaVersion","eclipse-multi-sat-relperm-parameters/2");p.put("regionNumber",2);p.put("field",i==0?"WATER_KR":"OIL_KR");p.put("unit","1");p.put("multiplier",i==0?.5:.75);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","multiplier","sourceSha256","schemaVersion","tables"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"cP");case "multiplier"->bad.put(fault,3);case "schemaVersion"->bad.put(fault,"eclipse-relperm-parameters/1");case "tables"->((tools.jackson.databind.node.ArrayNode)bad.path("tables")).removeAll();default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseMultiPvtBatchBindsBothTablesAndRejectsInvalidWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-pvt-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var context=inspection.path("multiPvtContext").path("regions").path(1);
        for(int i=0;i<2;i++){var p=(ObjectNode)seed.request().path("cases").path(i).path("parameters");p.remove("field");p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.remove("rows");p.remove("keywordLineNumber");p.set("tables",context.path("tables").deepCopy());p.put("schemaVersion","eclipse-multi-pvt-parameters/1");p.put("regionNumber",2);p.put("keyword",i==0?"PVDO":"PVDG");p.put("field","VISCOSITY");p.put("unit","cP");p.put("multiplier",i==0?2:3);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","multiplier","sourceSha256","schemaVersion","tables"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"rb/stb");case "multiplier"->bad.put(fault,101);case "schemaVersion"->bad.put(fault,"eclipse-relperm-parameters/1");case "tables"->((tools.jackson.databind.node.ArrayNode)bad.path("tables")).removeAll();default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseMultiLiveOilPvtBatchBindsBothTablesAndRejectsInvalidWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-pvto-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var context=inspection.path("multiLiveOilPvtContext").path("regions").path(1);
        for(int i=0;i<2;i++){var p=(ObjectNode)seed.request().path("cases").path(i).path("parameters");p.remove("field");p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.remove("rows");p.put("keywordLineNumber",context.path("keywordLineNumber").asInt());p.set("records",context.path("records").deepCopy());p.set("gasTable",context.path("gasTable").deepCopy());p.put("schemaVersion","eclipse-multi-pvto-parameters/1");p.put("regionNumber",2);p.put("field",i==0?"VISCOSITY":"FORMATION_VOLUME_FACTOR");p.put("unit",i==0?"cP":"rb/stb");p.put("multiplier",i==0?2:1.02);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","multiplier","sourceSha256","schemaVersion","records","regionNumber"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"rb/stb");case "multiplier"->bad.put(fault,101);case "schemaVersion"->bad.put(fault,"eclipse-relperm-parameters/1");case "records"->((tools.jackson.databind.node.ArrayNode)bad.path("records")).removeAll();case "regionNumber"->bad.put(fault,1);default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseIndependentSatPvtBatchUsesSevenCasesFromOneBaseline()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-sat-pvt-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());
        var cases=objectMapper.createArrayNode();var template=seed.request().path("cases").path(0);
        for(var spec:new String[][]{{"PVDO","VISCOSITY"},{"PVDO","FORMATION_VOLUME_FACTOR"},{"PVDG","VISCOSITY"},{"PVDG","FORMATION_VOLUME_FACTOR"},{"SWFN","WATER_KR"},{"SGFN","GAS_KR"},{"SOF3","OIL_KR"}}){
            boolean fluid=spec[0].equals("PVDO")||spec[0].equals("PVDG");var child=(ObjectNode)template.deepCopy();child.put("name",spec[0]+" "+spec[1]);var p=(ObjectNode)child.path("parameters");p.remove("rows");p.remove("keywordLineNumber");p.remove("keyword");
            p.put("schemaVersion",fluid?"eclipse-multi-pvt-parameters/1":"eclipse-multi-sat-relperm-parameters/2");p.put("regionNumber",2);p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.put("field",spec[1]);p.put("unit",fluid?(spec[1].equals("VISCOSITY")?"cP":spec[0].equals("PVDO")?"rb/stb":"rb/Mscf"):"1");p.put("multiplier",fluid?(spec[1].equals("VISCOSITY")?2:1.02):.5);p.set("tables",inspection.path(fluid?"multiPvtContext":"multiSatRelpermContext").path("regions").path(1).path("tables").deepCopy());if(fluid)p.put("keyword",spec[0]);cases.add(child);
        }
        seed.request().set("cases",cases);var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(7);
        for(int n=0;n<7;n++)assertThat(batch.members().get(n).run().parameters().toString()).isEqualTo(cases.path(n).path("parameters").toString());
    }
    @Test void eclipseIndependentCoupledSatPvtBatchUsesSevenCasesFromOneBaseline()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-sat-family-i-pvt-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());
        var cases=objectMapper.createArrayNode();var template=seed.request().path("cases").path(0);
        for(var spec:new String[][]{{"PVDO","VISCOSITY"},{"PVDO","FORMATION_VOLUME_FACTOR"},{"PVDG","VISCOSITY"},{"PVDG","FORMATION_VOLUME_FACTOR"},{"SWOF","WATER_KR"},{"SGOF","GAS_KR"},{"SWOF","OIL_KR"}}){
            boolean fluid=spec[0].equals("PVDO")||spec[0].equals("PVDG");var child=(ObjectNode)template.deepCopy();child.put("name",spec[0]+" "+spec[1]);var p=(ObjectNode)child.path("parameters");p.remove("rows");p.remove("keywordLineNumber");p.remove("keyword");
            p.put("schemaVersion",fluid?"eclipse-multi-pvt-parameters/1":"eclipse-multi-sat-relperm-parameters/1");p.put("regionNumber",2);p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.put("field",spec[1]);p.put("unit",fluid?(spec[1].equals("VISCOSITY")?"cP":spec[0].equals("PVDO")?"rb/stb":"rb/Mscf"):"1");p.put("multiplier",fluid?(spec[1].equals("VISCOSITY")?2:1.02):.5);p.set("tables",inspection.path(fluid?"multiPvtContext":"multiSatRelpermContext").path("regions").path(1).path("tables").deepCopy());if(fluid)p.put("keyword",spec[0]);cases.add(child);
        }
        seed.request().set("cases",cases);var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(7);
        for(int n=0;n<7;n++)assertThat(batch.members().get(n).run().parameters().toString()).isEqualTo(cases.path(n).path("parameters").toString());
    }
    @Test void eclipseMultiWetGasPvtBatchBindsBothTablesAndRejectsInvalidWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-pvtg-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var context=inspection.path("multiWetGasPvtContext").path("regions").path(1);
        for(int i=0;i<2;i++){var p=(ObjectNode)seed.request().path("cases").path(i).path("parameters");p.remove("field");p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.remove("rows");p.put("keywordLineNumber",context.path("keywordLineNumber").asInt());p.set("records",context.path("records").deepCopy());p.set("oilTable",context.path("oilTable").deepCopy());p.put("schemaVersion","eclipse-multi-pvtg-parameters/1");p.put("regionNumber",2);p.put("field",i==0?"VISCOSITY":"FORMATION_VOLUME_FACTOR");p.put("unit",i==0?"cP":"rm3/sm3");p.put("multiplier",i==0?2:1.02);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","multiplier","sourceSha256","schemaVersion","records","regionNumber"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"rm3/sm3");case "multiplier"->bad.put(fault,101);case "schemaVersion"->bad.put(fault,"eclipse-relperm-parameters/1");case "records"->((tools.jackson.databind.node.ArrayNode)bad.path("records")).removeAll();case "regionNumber"->bad.put(fault,1);default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseMultiWaterPvtBatchBindsSourceRecordAndRejectsInvalidWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-pvtw-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var region=inspection.path("multiWaterPvtContext").path("regions").path(1);
        for(int i=0;i<2;i++){var p=objectMapper.createObjectNode();p.put("schemaVersion","eclipse-multi-pvtw-parameters/1");p.put("baselineRunId",seed.baselineId());p.put("regionNumber",2);p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.put("lineNumber",region.path("lineNumber").asInt());p.put("field",i==0?"VISCOSITY":"VISCOSIBILITY");p.put("unit",i==0?"cP":"1/psi");p.put("originalValue",i==0?.4:0);p.put("targetValue",i==0?.8:1e-6);p.set("recordValues",region.path("recordValues").deepCopy());((ObjectNode)seed.request().path("cases").path(i)).set("parameters",p);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","targetValue","sourceSha256","recordValues","regionNumber","lineNumber"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"1/psi");case "targetValue"->bad.put(fault,0);case "recordValues"->((tools.jackson.databind.node.ArrayNode)bad.path("recordValues")).remove(4);case "regionNumber"->bad.put(fault,1);case "lineNumber"->bad.put(fault,region.path("lineNumber").asInt()+1);default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseMultiPureWaterPvtBatchBindsSourceRecordAndRejectsInvalidWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-pure-pvtw-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var region=inspection.path("multiWaterPvtContext").path("regions").path(1);
        for(int i=0;i<2;i++){var p=objectMapper.createObjectNode();p.put("schemaVersion","eclipse-multi-pvtw-parameters/1");p.put("baselineRunId",seed.baselineId());p.put("regionNumber",2);p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.put("lineNumber",region.path("lineNumber").asInt());p.put("field",i==0?"VISCOSITY":"VISCOSIBILITY");p.put("unit",i==0?"cP":"1/bar");p.put("originalValue",i==0?.5:0);p.put("targetValue",i==0?1:1e-6);p.set("recordValues",region.path("recordValues").deepCopy());((ObjectNode)seed.request().path("cases").path(i)).set("parameters",p);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","targetValue","sourceSha256","recordValues","regionNumber","lineNumber"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"1/bar");case "targetValue"->bad.put(fault,0);case "recordValues"->((tools.jackson.databind.node.ArrayNode)bad.path("recordValues")).remove(4);case "regionNumber"->bad.put(fault,1);case "lineNumber"->bad.put(fault,region.path("lineNumber").asInt()+1);default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseDefaultMultiWaterPvtBatchBindsOriginalTokensAndRejectsInvalidWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-pvtw-default-three-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var region=inspection.path("multiWaterPvtContext").path("regions").path(1);
        for(int i=0;i<2;i++){var p=objectMapper.createObjectNode();p.put("schemaVersion","eclipse-multi-pvtw-parameters/2");p.put("baselineRunId",seed.baselineId());p.put("regionNumber",2);p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.put("lineNumber",region.path("lineNumber").asInt());p.put("field",i==0?"VISCOSITY":"VISCOSIBILITY");p.put("unit",i==0?"cP":"1/psi");p.put("originalValue",i==0?.4:0);p.put("targetValue",i==0?.8:1e-6);p.set("recordValues",region.path("recordValues").deepCopy());((ObjectNode)seed.request().path("cases").path(i)).set("parameters",p);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","targetValue","sourceSha256","recordValues","regionNumber","lineNumber"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"1/psi");case "targetValue"->bad.put(fault,0);case "recordValues"->((tools.jackson.databind.node.ArrayNode)bad.path("recordValues")).remove(3);case "regionNumber"->bad.put(fault,1);case "lineNumber"->bad.put(fault,region.path("lineNumber").asInt()+1);default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseDefaultMultiPureWaterPvtBatchBindsOriginalTokensAndRejectsInvalidWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-pvtw-default-pure-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var region=inspection.path("multiWaterPvtContext").path("regions").path(1);
        for(int i=0;i<2;i++){var p=objectMapper.createObjectNode();p.put("schemaVersion","eclipse-multi-pvtw-parameters/2");p.put("baselineRunId",seed.baselineId());p.put("regionNumber",2);p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.put("lineNumber",region.path("lineNumber").asInt());p.put("field",i==0?"VISCOSITY":"VISCOSIBILITY");p.put("unit",i==0?"cP":"1/bar");p.put("originalValue",i==0?.5:0);p.put("targetValue",i==0?1:1e-6);p.set("recordValues",region.path("recordValues").deepCopy());((ObjectNode)seed.request().path("cases").path(i)).set("parameters",p);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","targetValue","sourceSha256","recordValues","regionNumber","lineNumber"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"1/bar");case "targetValue"->bad.put(fault,0);case "recordValues"->((tools.jackson.databind.node.ArrayNode)bad.path("recordValues")).remove(3);case "regionNumber"->bad.put(fault,1);case "lineNumber"->bad.put(fault,region.path("lineNumber").asInt()+1);default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseCombinedSatLiveOilBindsIndependentAxesAndRejectsStaleTablesWithoutRuns()throws Exception{
        var seed=eclipseBatchSeed();var i=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-sat-pvto-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i));versionMapper.updateById(seed.seed().version());
        for(int k=0;k<2;k++){boolean fluid=k==0;var r=i.path(fluid?"multiLiveOilPvtContext":"multiSatRelpermContext").path("regions").path(1);var p=objectMapper.createObjectNode();p.put("schemaVersion",fluid?"eclipse-multi-pvto-parameters/1":"eclipse-multi-sat-relperm-parameters/2");p.put("baselineRunId",seed.baselineId());p.put("regionNumber",2);p.put("sourceFile",i.path("caseName").asText());p.put("sourceSha256",i.path("packageFiles").path(0).path("sha256").asText());p.put("field",fluid?"VISCOSITY":"OIL_KR");p.put("unit",fluid?"cP":"1");p.put("multiplier",fluid?2:.5);if(fluid){p.put("keywordLineNumber",r.path("keywordLineNumber").asInt());p.set("records",r.path("records").deepCopy());p.set("gasTable",r.path("gasTable").deepCopy());}else p.set("tables",r.path("tables").deepCopy());((ObjectNode)seed.request().path("cases").path(k)).set("parameters",p);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);int before=runStore.listByVersion(seed.seed().version().getId(),100).size();
        for(int k=0;k<2;k++)for(var fault:new String[]{"regionNumber","sourceSha256","unit"}){var p=((ObjectNode)seed.request().path("cases").path(k).path("parameters")).deepCopy();if(fault.equals("regionNumber"))p.put(fault,1);else p.put(fault,fault.equals("unit")?"mD":"0".repeat(64));var r=request(null,"eclipse");r.setParameters(p);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),r)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseCombinedFamilyISatLiveOilBindsIndependentAxesAndRejectsStaleTablesWithoutRuns()throws Exception{
        var seed=eclipseBatchSeed();var i=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-sat-family-i-pvto-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i));versionMapper.updateById(seed.seed().version());
        for(int k=0;k<2;k++){boolean fluid=k==0;var r=i.path(fluid?"multiLiveOilPvtContext":"multiSatRelpermContext").path("regions").path(1);var p=objectMapper.createObjectNode();p.put("schemaVersion",fluid?"eclipse-multi-pvto-parameters/1":"eclipse-multi-sat-relperm-parameters/1");p.put("baselineRunId",seed.baselineId());p.put("regionNumber",2);p.put("sourceFile",i.path("caseName").asText());p.put("sourceSha256",i.path("packageFiles").path(0).path("sha256").asText());p.put("field",fluid?"VISCOSITY":"OIL_KR");p.put("unit",fluid?"cP":"1");p.put("multiplier",fluid?2:.5);if(fluid){p.put("keywordLineNumber",r.path("keywordLineNumber").asInt());p.set("records",r.path("records").deepCopy());p.set("gasTable",r.path("gasTable").deepCopy());}else p.set("tables",r.path("tables").deepCopy());((ObjectNode)seed.request().path("cases").path(k)).set("parameters",p);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);int before=runStore.listByVersion(seed.seed().version().getId(),100).size();
        for(int k=0;k<2;k++)for(var fault:new String[]{"regionNumber","sourceSha256","unit"}){var p=((ObjectNode)seed.request().path("cases").path(k).path("parameters")).deepCopy();if(fault.equals("regionNumber"))p.put(fault,1);else p.put(fault,fault.equals("unit")?"mD":"0".repeat(64));var r=request(null,"eclipse");r.setParameters(p);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),r)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseCombinedSatWetGasBindsIndependentAxesAndRejectsStaleTablesWithoutRuns()throws Exception{
        var seed=eclipseBatchSeed();var i=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-sat-pvtg-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i));versionMapper.updateById(seed.seed().version());
        for(int k=0;k<2;k++){boolean fluid=k==0;var r=i.path(fluid?"multiWetGasPvtContext":"multiSatRelpermContext").path("regions").path(1);var p=objectMapper.createObjectNode();p.put("schemaVersion",fluid?"eclipse-multi-pvtg-parameters/1":"eclipse-multi-sat-relperm-parameters/2");p.put("baselineRunId",seed.baselineId());p.put("regionNumber",2);p.put("sourceFile",i.path("caseName").asText());p.put("sourceSha256",i.path("packageFiles").path(0).path("sha256").asText());p.put("field",fluid?"VISCOSITY":"OIL_KR");p.put("unit",fluid?"cP":"1");p.put("multiplier",fluid?2:.5);if(fluid){p.put("keywordLineNumber",r.path("keywordLineNumber").asInt());p.set("records",r.path("records").deepCopy());p.set("oilTable",r.path("oilTable").deepCopy());}else p.set("tables",r.path("tables").deepCopy());((ObjectNode)seed.request().path("cases").path(k)).set("parameters",p);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);int before=runStore.listByVersion(seed.seed().version().getId(),100).size();
        for(int k=0;k<2;k++)for(var fault:new String[]{"regionNumber","sourceSha256","unit"}){var p=((ObjectNode)seed.request().path("cases").path(k).path("parameters")).deepCopy();if(fault.equals("regionNumber"))p.put(fault,1);else p.put(fault,fault.equals("unit")?"mD":"0".repeat(64));var r=request(null,"eclipse");r.setParameters(p);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),r)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseCombinedFamilyISatWetGasBindsIndependentAxesAndRejectsStaleTablesWithoutRuns()throws Exception{
        var seed=eclipseBatchSeed();var i=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-sat-family-i-pvtg-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i));versionMapper.updateById(seed.seed().version());
        for(int k=0;k<2;k++){boolean fluid=k==0;var r=i.path(fluid?"multiWetGasPvtContext":"multiSatRelpermContext").path("regions").path(1);var p=objectMapper.createObjectNode();p.put("schemaVersion",fluid?"eclipse-multi-pvtg-parameters/1":"eclipse-multi-sat-relperm-parameters/1");p.put("baselineRunId",seed.baselineId());p.put("regionNumber",2);p.put("sourceFile",i.path("caseName").asText());p.put("sourceSha256",i.path("packageFiles").path(0).path("sha256").asText());p.put("field",fluid?"VISCOSITY":"OIL_KR");p.put("unit",fluid?"cP":"1");p.put("multiplier",fluid?2:.5);if(fluid){p.put("keywordLineNumber",r.path("keywordLineNumber").asInt());p.set("records",r.path("records").deepCopy());p.set("oilTable",r.path("oilTable").deepCopy());}else p.set("tables",r.path("tables").deepCopy());((ObjectNode)seed.request().path("cases").path(k)).set("parameters",p);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);int before=runStore.listByVersion(seed.seed().version().getId(),100).size();
        for(int k=0;k<2;k++)for(var fault:new String[]{"regionNumber","sourceSha256","unit"}){var p=((ObjectNode)seed.request().path("cases").path(k).path("parameters")).deepCopy();if(fault.equals("regionNumber"))p.put(fault,1);else p.put(fault,fault.equals("unit")?"mD":"0".repeat(64));var r=request(null,"eclipse");r.setParameters(p);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),r)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseBatchLaterSourceRejectionRollsBackEarlierQueuedChildAndCollection()throws Exception {
        var seed=eclipseBatchSeed();((ObjectNode)seed.request().path("cases").path(1).path("parameters")).put("sourceSha256","0".repeat(64));
        assertThatThrownBy(()->eclipseBatches.create(seed.seed().version().getId(),seed.request())).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(1);assertThat(eclipseBatches.list(seed.seed().version().getId())).isEmpty();
    }
    @Test void eclipseBatchFailedChildDoesNotBlockFollowingQueueOrCreateFakeResult()throws Exception {
        var seed=eclipseBatchSeed();var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());long first=batch.members().get(0).run().id(),second=batch.members().get(1).run().id();
        assertThat(runStore.claimOldest("batch-test").getId()).isEqualTo(first);assertThat(runStore.claimOldest("must-remain-serial")).isNull();
        var cleanup=objectMapper.readTree("{\"inputDeleted\":true,\"workDirectoryDeleted\":true,\"processTreeExitConfirmed\":true}");
        assertThat(runStore.complete(first,SoftwareIntegrationRunStatus.FAILED,null,null,runStore.error("ECLIPSE_SOURCE_REJECTED","source rejected"),cleanup,null)).isTrue();
        runStore.recoverOnStartup();assertThat(runStore.find(second).getStatus()).isEqualTo("QUEUED");assertThat(runStore.claimOldest("next-after-failure").getId()).isEqualTo(second);
        runStore.transition(second,SoftwareIntegrationRunStatus.PREPARING,null,"preparing actual child",null);
        runStore.transition(second,SoftwareIntegrationRunStatus.COLLECTING,null,"collecting actual child",null);
        assertThat(runStore.complete(second,SoftwareIntegrationRunStatus.SUCCEEDED,"VALID_FULL",eclipseResult(0,"a".repeat(64)),null,cleanup,null)).isTrue();
        var current=eclipseBatches.get(batch.id());assertThat(current.status()).isEqualTo("PARTIAL_SUCCEEDED");assertThat(current.members().get(0).run().result().isNull()).isTrue();assertThat(current.members().get(0).run().error().path("code").asText()).isEqualTo("ECLIPSE_SOURCE_REJECTED");assertThat(current.members().get(1).run().result().isNull()).isFalse();
    }
    @Test void eclipseBatchRejectsE300BaselineWithoutCreatingChildren()throws Exception {
        var seed=eclipseBatchSeed();var baseline=runStore.find(seed.baselineId());var result=(ObjectNode)objectMapper.readTree(baseline.getResultJson());result.put("modelKind","eclipse_300");baseline.setResultJson(objectMapper.writeValueAsString(result));runMapper.updateById(baseline);
        assertThatThrownBy(()->eclipseBatches.create(seed.seed().version().getId(),seed.request())).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(1);assertThat(eclipseBatches.list(seed.seed().version().getId())).isEmpty();
    }
    @Test void eclipseBatchCancelsOnlyOwnQueuedChildrenAndLeavesBaselineIntact()throws Exception {
        var seed=eclipseBatchSeed();var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());var cancelled=eclipseBatches.cancel(batch.id());assertThat(cancelled.status()).isEqualTo("CANCELLED");assertThat(cancelled.members()).allSatisfy(m->assertThat(m.run().status()).isEqualTo("CANCELLED"));assertThat(runStore.find(seed.baselineId()).getStatus()).isEqualTo("SUCCEEDED");
    }

    private ObjectNode eclipseHistoryRequest(long baselineId,String csv){
        var request=objectMapper.createObjectNode();request.put("name","观察验算样例");request.put("sourceFile","observations.csv");request.put("baselineRunId",baselineId);request.put("csv",csv);return request;
    }
    @Test void eclipseHistoryPreservesRawCsvHashAndReusesIdenticalImport()throws Exception {
        var seed=eclipseBatchSeed();String csv="\uFEFFwell,keyword,unit,timeDays,value\r\n,FOPR,STB/DAY,0,2\r\n,FOPR,STB/DAY,1,1\r\n";
        var request=eclipseHistoryRequest(seed.baselineId(),csv);var dataset=eclipseHistory.create(seed.seed().version().getId(),request);
        assertThat(dataset.csv()).isEqualTo(csv);assertThat(dataset.metadata().sourceSha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv.getBytes(StandardCharsets.UTF_8))));
        assertThat(dataset.observations()).hasSize(2);assertThat(eclipseHistory.get(dataset.metadata().id())).isEqualTo(dataset);
        assertThat(eclipseHistory.create(seed.seed().version().getId(),request.deepCopy()).metadata().id()).isEqualTo(dataset.metadata().id());assertThat(eclipseHistory.list(seed.seed().version().getId())).hasSize(1);
        var comparison=eclipseHistory.compare(dataset.metadata().id(),seed.baselineId());assertThat(comparison.comparison().points()).hasSize(2);assertThat(comparison.comparison().metrics().getFirst().rmse()).isZero();
        assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(1);
    }
    @Test void eclipseHistoryRejectsUnknownSourceUnitsColumnsAndParameterizedBaselineWithoutCreatingDataset()throws Exception {
        var seed=eclipseBatchSeed();String csv="well,keyword,unit,timeDays,value\n,FOPR,STB/DAY,0,2";
        for(int i=0;i<5;i++){
            var bad=eclipseHistoryRequest(seed.baselineId(),csv);
            switch(i){case 0->bad.put("extra",1);case 1->bad.put("sourceFile","../observations.csv");case 2->bad.put("csv",csv.replace("STB/DAY","SM3/DAY"));case 3->bad.put("csv",csv.replace("FOPR","FXXX"));default->bad.put("csv",csv+"\n,FOPR,STB/DAY,0.0,1");}
            assertThatThrownBy(()->eclipseHistory.create(seed.seed().version().getId(),bad)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        var baseline=runStore.find(seed.baselineId());baseline.setParametersJson("{\"schemaVersion\":\"other\"}");runMapper.updateById(baseline);
        assertThatThrownBy(()->eclipseHistory.create(seed.seed().version().getId(),eclipseHistoryRequest(seed.baselineId(),csv))).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(eclipseHistory.list(seed.seed().version().getId())).isEmpty();
    }
    @Test void eclipseHistoryApiKeepsMissingPointAndMapeExplicitlyNull()throws Exception {
        var seed=eclipseBatchSeed();var dataset=eclipseHistory.create(seed.seed().version().getId(),eclipseHistoryRequest(seed.baselineId(),"well,keyword,unit,timeDays,value\n,FOPR,STB/DAY,0,0\n,FOPR,STB/DAY,3,5"));
        var json=objectMapper.readTree(objectMapper.writeValueAsString(eclipseHistory.compare(dataset.metadata().id(),seed.baselineId())));
        var missing=json.path("comparison").path("points").path(1);assertThat(missing.has("simulated")).isTrue();assertThat(missing.path("simulated").isNull()).isTrue();assertThat(missing.has("difference")).isTrue();assertThat(missing.path("difference").isNull()).isTrue();
        var metric=json.path("comparison").path("metrics").path(0);assertThat(metric.has("mape")).isTrue();assertThat(metric.path("mape").isNull()).isTrue();
    }
    @Test void eclipseHistoryFailedOrExpiredRunCannotFallbackToBaselineOrFakeFit()throws Exception {
        var seed=eclipseBatchSeed();var dataset=eclipseHistory.create(seed.seed().version().getId(),eclipseHistoryRequest(seed.baselineId(),"well,keyword,unit,timeDays,value\n,FOPR,STB/DAY,0,2\n,FOPR,STB/DAY,3,5"));
        var response=eclipseHistory.compare(dataset.metadata().id(),seed.baselineId());assertThat(response.comparison().metrics().getFirst().coverage()).isEqualTo(.5);assertThat(response.comparison().points().getLast().simulated()).isNull();
        var baseline=runStore.find(seed.baselineId());baseline.setStatus("FAILED");runMapper.updateById(baseline);
        assertThatThrownBy(()->eclipseHistory.compare(dataset.metadata().id(),seed.baselineId())).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.CONFLICT));
        baseline.setStatus("SUCCEEDED");baseline.setResultExpiresAt(LocalDateTime.now().minusMinutes(1));runMapper.updateById(baseline);
        assertThatThrownBy(()->eclipseHistory.compare(dataset.metadata().id(),seed.baselineId())).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(eclipseHistory.get(dataset.metadata().id()).observations()).hasSize(2);assertThat(eclipseHistory.list(seed.seed().version().getId())).hasSize(1);
    }

    private SoftwareIntegrationCreateRunRequest request(String study, String runType) {
        SoftwareIntegrationCreateRunRequest request = new SoftwareIntegrationCreateRunRequest();
        request.setStudy(study);
        request.setRunType(runType);
        request.setParameters(null);
        return request;
    }

    private static String validEclipseInspection() {
        return """
                {"schemaVersion":"eclipse-data-inspection/1","caseName":"CASE.DATA","sections":[],
                 "unitSystem":null,"phases":[],"dimensions":null}
                """;
    }

    private static String validMixedWellNetworkInspection() {
        String manifest = "[{\"relativePath\":\"model.pips\",\"sizeBytes\":5,\"sha256\":\"" + "a".repeat(64) + "\"}]";
        return """
                {"schemaVersion":"pipesim-well-inspection/7","reservoirPressure":null,"packageFiles":%s,
                 "well":"Well_1","espContexts":[],
                 "wellContexts":[{"context":"Well_1","espContexts":[],"completionContexts":["Well_1:Completion"],"tubingContexts":["Well_1:Tubing"]}],
                 "networkCapability":{"schemaVersion":"pipesim-network-capability/1","studies":["Study 1"],
                   "networkInspection":{"schemaVersion":"pipesim-network-inspection/3",
                     "studies":[{"study":"Study 1","boundaries":[{"node":"Source_1","boundaryNodeType":"Source","isActive":true,"isSurfaceCondition":true,"flowRateType":"GasFlowRate","pressure":120,"temperature":80,"gasFlowRate":5,"liquidFlowRate":null,"massFlowRate":null}]}],
                     "chokes":[],"packageFiles":%s}}}
                """.formatted(manifest, manifest);
    }

    private JsonNode partialResult() {
        return objectMapper.readTree("""
                {"schemaVersion":"pipesim-well-result/1","model_kind":"black_oil_liquid","runTask":"combined","resultContract":"VALID_PARTIAL",
                 "units":{"flow":{"displayUnit":null,"semantics":"unspecified"},"pressure":{"displayUnit":null,"semantics":"unspecified"},
                          "depth":{"displayUnit":null,"semantics":"unspecified"},"temperature":{"displayUnit":null,"semantics":"unspecified"}},
                 "ipr":[{"flow":1.0,"pressure":2.0}],"vlp":[{"flow":1.0,"pressure":2.0}],"profile":[]}
                """);
    }

    private void assertNetworkContractFailure(String reasonClass, JsonNode result) throws Exception {
        Seed seed = seed("READY", "models/network/invalid-" + UUID.randomUUID() + ".pips", "PIPESIM_NETWORK");
        long runId = runService.create(seed.version().getId(), request("Network Study", "network")).id();
        SoftwareIntegrationRunDispatcher dispatcher = dispatcher();
        dispatcher.dispatch();
        fakeWorker.snapshot = new WorkerRunSnapshot(runId, "SUCCEEDED", 1, "worker-1", "generation-1",
                List.of(), result, null, List.of(), null);

        dispatcher.poll();

        assertThat(runStore.find(runId).getStatus()).isEqualTo("FAILED");
        assertThat(runStore.find(runId).getResultJson()).isNull();
        JsonNode error = runService.get(runId).error();
        assertThat(error.path("category").asText()).isEqualTo("VALIDATION");
        assertThat(error.path("code").asText()).isEqualTo("INVALID_NETWORK_RESULT_CONTRACT");
        assertThat(error.path("message").asText()).isEqualTo("Simulator returned data but it was not accepted for display.");
        assertThat(error.path("reasonClass").asText()).isEqualTo(reasonClass);
        assertThat(error.path("retryable").asBoolean()).isFalse();
        assertThat(error.toString()).doesNotContain("C:\\private", "license=secret");
    }

    private JsonNode networkResult() {
        return objectMapper.readTree("""
                {"schemaVersion":"pipesim-network-result/1","model_kind":"network","runTask":"network",
                 "resultContract":"VALID_FULL","study":"Network Study","simulationState":"Completed",
                 "topology":{"nodes":[{"id":"Source 1","componentType":"SOURCE"},{"id":"Sink 1","componentType":"SINK"}],
                   "edges":[{"source":"Source 1","destination":"Sink 1","sourcePort":"OUTLET"}],
                   "counts":{"nodes":2,"edges":1,"sources":1,"sinks":1,"flowlines":1}},
                   "system":[{"variable":"Pressure","unit":"bar","values":[{"name":"Network","value":{"average":100.0}}]}],
                 "node":[{"variable":"Pressure","unit":"bar","values":[{"name":"Sink 1","value":null}]},
                   {"variable":"IsInjectingIntoCompletion","unit":"","values":[{"name":"Sink 1","value":false}]},
                   {"variable":"LimitedBy","unit":"","values":[{"name":"Sink 1","value":"SPEED"}]}],
                 "profiles":[{"branch":"Source 1 -> Sink 1","pointCount":2,"variables":[
                   {"variable":"TotalDistance","unit":"m","values":[0.0,100.0]},
                   {"variable":"Pressure","unit":"bar","values":[100.0,null]},
                   {"variable":"Temperature","unit":"degC","values":[20.0]}]}],
                  "summary":{"info":["completed"],"warnings":[],"errors":[]},
                    "messages":["Network simulation completed"],
                  "quality":[
                    {"path":"node.Pressure.Sink 1","code":"UNAVAILABLE"},
                    {"path":"profiles.Source 1 -> Sink 1.Pressure[1]","code":"NON_FINITE"}]}
                """);
    }

    private JsonNode partialNetworkResult() {
        ObjectNode result = (ObjectNode) networkResult();
        result.put("resultContract", "VALID_PARTIAL");
        result.remove(List.of("system", "node", "profiles", "summary", "messages", "quality"));
        return result;
    }

    private JsonNode eclipseResult(long eclEndSize, String eclEndSha) {
        return objectMapper.readTree("""
                {"schemaVersion":"eclipse-summary-result/1","modelKind":"eclipse_100","runTask":"eclipse",
                 "resultContract":"VALID_FULL","caseName":"CASE.DATA",
                 "eclEnd":{"comments":null,"warnings":0,"problems":0,"errors":0,"bugs":0},
                 "summary":{"series":[{"keyword":"FOPR","objectName":null,"unit":"STB/DAY","points":[
                   {"timeDays":0.0,"value":2.0},{"timeDays":1.0,"value":1.0}]}]},
                 "messages":[{"category":"SOLVER","code":"NOTICE","message":"token=private-token","retryable":false}],
                 "outputFiles":[{"name":"CASE.ECLEND","sizeBytes":%d,"sha256":"%s"}]}
                """.formatted(eclEndSize, eclEndSha));
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static byte[] zipBytes(String[] names, String[] contents) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (int index = 0; index < names.length; index++) {
                    zip.putNextEntry(new ZipEntry(names[index]));
                    zip.write(contents[index].getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) { throw new AssertionError(exception); }
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
        WorkerRunExecuteRequest lastExecuteRequest;
        WorkerEclipseCapability eclipseCapability;
        boolean compositionalAvailable;
        boolean workerIdle;
        WorkerClientException capabilityError;

        void reset() {
            executeError = null;
            getError = null;
            cancelError = null;
            executeHook = null;
            cancelCalled = false;
            cancelCalls = 0;
            transactionActiveDuringHttp = false;
            lastExecuteRequest = null;
            eclipseCapability = new WorkerEclipseCapability(
                    "2024.1", "AVAILABLE", null, List.of("eclipse"), 1800);
            workerIdle = true;
            compositionalAvailable = false;
            capabilityError = null;
            snapshot = new WorkerRunSnapshot(-1, "PREPARING", 0, "worker-1", "generation-1",
                    List.of(), null, null, List.of(), null);
        }

        @Override
        public WorkerAvailability availability() {
            observeTransaction();
            return new WorkerAvailability(true, "generation-1");
        }

        @Override
        public com.grdp.studio.softwareintegration.client.WorkerCapabilities capabilities() {
            observeTransaction();
            if (capabilityError != null) throw capabilityError;
            var well = new com.grdp.studio.softwareintegration.client.WorkerSimulatorCapability(
                    "2022.1", "AVAILABLE", null, List.of("nodal", "profile", "combined", "sensitivity", "gas-lift-performance", "gas-lift-diagnostics", "vfp-tables", "esp-curves", "esp-parameter", "trajectory"), 600);
            var network = new com.grdp.studio.softwareintegration.client.WorkerSimulatorCapability(
                    "2022.1", "AVAILABLE", null, List.of("network", "system-analysis", "network-optimizer", "network-well-performance-curves"), 600);
            var eclipse = new com.grdp.studio.softwareintegration.client.WorkerSimulatorCapability(
                    eclipseCapability.version(), eclipseCapability.status(), eclipseCapability.reasonCode(),
                    eclipseCapability.runTasks(), eclipseCapability.maxTimeoutSeconds());
            return new com.grdp.studio.softwareintegration.client.WorkerCapabilities(
                    workerIdle, well, network, eclipse, compositionalAvailable ?
                    new com.grdp.studio.softwareintegration.client.WorkerSimulatorCapability("2024.1", "AVAILABLE", null, List.of("eclipse"), 1800) : null);
        }

        @Override
        public WorkerEclipseCapability eclipseCapability() {
            observeTransaction();
            if (capabilityError != null) throw capabilityError;
            return eclipseCapability;
        }

        @Override
        public WorkerRunAccepted execute(WorkerRunExecuteRequest request) {
            observeTransaction();
            lastExecuteRequest = request;
            if (executeHook != null) executeHook.accept(request.runId());
            if (executeError != null) throw executeError;
            assertThat(request.modelStorageKey()).doesNotMatch("^[A-Za-z]:.*");
            assertThat(request.timeoutSeconds()).isIn(600, 1800);
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
    }    @Test void eclipseStarMultiWaterPvtBatchBindsOriginalTokensAndRejectsInvalidWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-water-star-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var region=inspection.path("multiWaterPvtContext").path("regions").path(1);
        for(int i=0;i<2;i++){var p=objectMapper.createObjectNode();p.put("schemaVersion","eclipse-multi-pvtw-parameters/3");p.put("baselineRunId",seed.baselineId());p.put("regionNumber",2);p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.put("lineNumber",region.path("lineNumber").asInt());p.put("field",i==0?"VISCOSITY":"VISCOSIBILITY");p.put("unit",i==0?"cP":"1/psi");p.put("originalValue",i==0?.4:0);p.put("targetValue",i==0?.8:1e-6);p.set("recordValues",region.path("recordValues").deepCopy());((ObjectNode)seed.request().path("cases").path(i)).set("parameters",p);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","targetValue","sourceSha256","recordValues","regionNumber","lineNumber"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"1/psi");case "targetValue"->bad.put(fault,0);case "recordValues"->((tools.jackson.databind.node.ArrayNode)bad.path("recordValues")).remove(3);case "regionNumber"->bad.put(fault,1);case "lineNumber"->bad.put(fault,region.path("lineNumber").asInt()+1);default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }
    @Test void eclipseStarMultiPureWaterPvtBatchBindsOriginalTokensAndRejectsInvalidWithoutRuns()throws Exception {
        var seed=eclipseBatchSeed();var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-multi-pure-water-star-inspection.json"));seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());var region=inspection.path("multiWaterPvtContext").path("regions").path(1);
        for(int i=0;i<2;i++){var p=objectMapper.createObjectNode();p.put("schemaVersion","eclipse-multi-pvtw-parameters/3");p.put("baselineRunId",seed.baselineId());p.put("regionNumber",2);p.put("sourceFile",inspection.path("caseName").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.put("lineNumber",region.path("lineNumber").asInt());p.put("field",i==0?"VISCOSITY":"VISCOSIBILITY");p.put("unit",i==0?"cP":"1/bar");p.put("originalValue",i==0?.5:0);p.put("targetValue",i==0?1:1e-6);p.set("recordValues",region.path("recordValues").deepCopy());((ObjectNode)seed.request().path("cases").path(i)).set("parameters",p);}
        var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(2);assertThat(batch.members().get(0).run().parameters().toString()).isEqualTo(seed.request().path("cases").path(0).path("parameters").toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();for(String fault:new String[]{"unit","targetValue","sourceSha256","recordValues","regionNumber","lineNumber"}){var bad=((ObjectNode)seed.request().path("cases").path(0).path("parameters")).deepCopy();switch(fault){case "unit"->bad.put(fault,"1/bar");case "targetValue"->bad.put(fault,0);case "recordValues"->((tools.jackson.databind.node.ArrayNode)bad.path("recordValues")).remove(3);case "regionNumber"->bad.put(fault,1);case "lineNumber"->bad.put(fault,region.path("lineNumber").asInt()+1);default->bad.put(fault,"0".repeat(64));}var request=request(null,"eclipse");request.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);}
    }

    @Test void eclipseIncludeInitialProductionTargetBindsPhysicalFileAndRejectsStaleWithoutRuns() throws Exception {
        var seed=eclipseBatchSeed();
        var inspection=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-include-production-inspection.json"));
        seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection));versionMapper.updateById(seed.seed().version());
        var t=inspection.path("packageProductionTargetContext").path("targets").path(0);
        var good=objectMapper.createObjectNode().put("schemaVersion","eclipse-production-target-parameters/2").put("baselineRunId",seed.baselineId()).put("phase","INITIAL").put("unit","STB/day").put("targetOilRate",10000);
        for(var key:new String[]{"well","sourceFile","sourceSha256","lineNumber","originalOilRate"})good.set(key,t.get(key));
        var validRequest=request(null,"eclipse");validRequest.setParameters(good);
        var accepted=runService.create(seed.seed().version().getId(),validRequest);
        assertThat(accepted.parameters().toString()).isEqualTo(good.toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();
        for(var key:new String[]{"sourceFile","sourceSha256","lineNumber","originalOilRate"}){
            var bad=good.deepCopy();switch(key){case "sourceFile"->bad.put(key,"INITIAL.INC");case "lineNumber"->bad.put(key,999);case "originalOilRate"->bad.put(key,19999);default->bad.put(key,"0".repeat(64));}
            var invalidRequest=request(null,"eclipse");invalidRequest.setParameters(bad);
            assertThatThrownBy(()->runService.create(seed.seed().version().getId(),invalidRequest)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
            assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);
        }
    }

    @Test void eclipsePackageDatedPlanBindsExecutionOrderAndEveryPhysicalSourceBeforeCreatingRuns() throws Exception {
        var seed = eclipseBatchSeed();
        var inspection = objectMapper.readTree(getClass().getResourceAsStream("/eclipse-include-control-plan-inspection.json"));
        seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection)); versionMapper.updateById(seed.seed().version());
        var good = objectMapper.createObjectNode().put("schemaVersion", "eclipse-control-plan-parameters/2").put("baselineRunId", seed.baselineId());
        var edits = good.putArray("edits");
        for (int index : new int[] {4, 7, 8}) {
            var record = inspection.path("packageProductionControlPlanContext").path("records").get(index); var e = edits.addObject();
            for (var key : new String[] {"well", "sourceFile", "sourceSha256", "lineNumber", "date", "timeDays", "unit", "originalOilRate"}) e.set(key, record.get(key));
            e.put("targetOilRate", index == 4 ? 250 : index == 7 ? 350 : 450);
        }
        var validRequest = request(null, "eclipse"); validRequest.setParameters(good);
        assertThat(runService.create(seed.seed().version().getId(), validRequest).parameters().toString()).isEqualTo(good.toString());
        int before = runStore.listByVersion(seed.seed().version().getId(), 100).size();
        for (var fault : new String[] {"sourceFile", "sourceSha256", "lineNumber", "date", "timeDays", "originalOilRate"}) {
            var bad = good.deepCopy(); var e = (ObjectNode)bad.path("edits").get(1);
            switch (fault) { case "sourceFile" -> e.put(fault, "controls/YEAR1.INC"); case "lineNumber" -> e.put(fault, 999);
                case "date" -> e.put(fault, "1984-10-19"); case "timeDays" -> e.put(fault, 731); case "originalOilRate" -> e.put(fault, 499); default -> e.put(fault, "0".repeat(64)); }
            var invalidRequest = request(null, "eclipse"); invalidRequest.setParameters(bad);
            assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), invalidRequest)).isInstanceOf(RunException.class)
                .satisfies(e2 -> assertThat(((RunException)e2).status()).isEqualTo(HttpStatus.BAD_REQUEST));
            assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(before);
        }
    }

    @Test void eclipseDatedWaterInjectionPlanBindsThePhysicalWaterChainBeforeCreatingRuns() throws Exception {
        var seed = eclipseBatchSeed();
        var inspection = objectMapper.readTree(getClass().getResourceAsStream("/eclipse-water-injection-plan-inspection.json"));
        seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection)); versionMapper.updateById(seed.seed().version());
        var good = objectMapper.createObjectNode().put("schemaVersion", "eclipse-water-injection-plan-parameters/1").put("baselineRunId", seed.baselineId());
        var edits = good.putArray("edits");
        for (int index : new int[] {2, 5, 6}) {
            var record = inspection.path("waterInjectionControlPlanContext").path("records").get(index); var e = edits.addObject();
            for (var key : new String[] {"well", "sourceFile", "sourceSha256", "lineNumber", "date", "timeDays", "unit", "originalInjectionRate"}) e.set(key, record.get(key));
            e.put("targetInjectionRate", index == 2 ? .5 : index == 5 ? .7 : .9);
        }
        var validRequest = request(null, "eclipse"); validRequest.setParameters(good);
        assertThat(runService.create(seed.seed().version().getId(), validRequest).parameters().toString()).isEqualTo(good.toString());
        int before = runStore.listByVersion(seed.seed().version().getId(), 100).size();
        for (var fault : new String[] {"sourceFile", "sourceSha256", "lineNumber", "date", "timeDays", "originalInjectionRate"}) {
            var bad = good.deepCopy(); var e = (ObjectNode)bad.path("edits").get(1);
            switch (fault) { case "sourceFile" -> e.put(fault, "controls/YEAR1.INC"); case "lineNumber" -> e.put(fault, 999);
                case "date" -> e.put(fault, "1984-10-19"); case "timeDays" -> e.put(fault, 731); case "originalInjectionRate" -> e.put(fault, .99); default -> e.put(fault, "0".repeat(64)); }
            var invalidRequest = request(null, "eclipse"); invalidRequest.setParameters(bad);
            assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), invalidRequest)).isInstanceOf(RunException.class)
                .satisfies(e2 -> assertThat(((RunException)e2).status()).isEqualTo(HttpStatus.BAD_REQUEST));
            assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(before);
        }
    }

    @Test void e300GasTargetUsesQualifiedSourceAndRejectsStaleOrForeignRequestsBeforeRunCreation() throws Exception {
        var seed = eclipseBatchSeed();
        var i = objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-production-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300"); modelMapper.updateById(seed.seed().model());
        seed.seed().version().setModelKind("eclipse_300"); seed.seed().version().setOriginalName("SPE3_GASCOND.DATA");
        seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i)); versionMapper.updateById(seed.seed().version());
        fakeWorker.compositionalAvailable = true;
        var c = i.path("compositionalProductionContext"); var r = c.path("records").get(0);
        var p = objectMapper.createObjectNode().put("schemaVersion", "eclipse-compositional-production-parameters/1").put("baselineRunId", seed.baselineId()).put("targetGasRate", 4500);
        for (var k : new String[]{"sourceFile", "sourceSha256"}) p.set(k, c.get(k));
        for (var k : new String[]{"well", "lineNumber", "unit", "originalGasRate"}) p.set(k, r.get(k));
        var good = request(null, "eclipse"); good.setParameters(p);
        assertThat(runService.create(seed.seed().version().getId(), good).parameters().toString()).isEqualTo(p.toString());
        int before = runStore.listByVersion(seed.seed().version().getId(), 100).size();
        for (var fault : new String[]{"hash", "line", "original", "baseline", "legacy"}) {
            var bad = p.deepCopy();
            switch (fault) {case "hash" -> bad.put("sourceSha256", "0".repeat(64)); case "line" -> bad.put("lineNumber", 431);
                case "original" -> bad.put("originalGasRate", 6199); case "baseline" -> bad.put("baselineRunId", 999999); default -> bad.put("schemaVersion", "eclipse-production-target-parameters/1");}
            var req = request(null, "eclipse"); req.setParameters(bad);
            assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), req)).isInstanceOf(RunException.class);
            assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(before);
        }
    }

    @Test void e300ProducerPressureUsesSeparateSourceRowAndRejectsInvalidBindingsBeforeRunCreation() throws Exception {
        var seed = eclipseBatchSeed();
        var i = objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-production-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300"); modelMapper.updateById(seed.seed().model());
        seed.seed().version().setModelKind("eclipse_300"); seed.seed().version().setOriginalName("SPE3_GASCOND.DATA");
        seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i)); versionMapper.updateById(seed.seed().version());
        fakeWorker.compositionalAvailable = true;
        var c = i.path("compositionalProductionContext"); var r = c.path("records").get(0);
        var p = objectMapper.createObjectNode().put("schemaVersion", "eclipse-compositional-pressure-parameters/1").put("baselineRunId", seed.baselineId()).put("targetBhpLowerLimit", 1500);
        for (var k : new String[]{"sourceFile", "sourceSha256"}) p.set(k, c.get(k));
        p.set("well", r.get("well")); p.set("lineNumber", r.get("bhpLineNumber")); p.put("unit", "psia"); p.set("originalBhpLowerLimit", r.get("bhpLimit"));
        var good = request(null, "eclipse"); good.setParameters(p);
        assertThat(runService.create(seed.seed().version().getId(), good).parameters().toString()).isEqualTo(p.toString());
        int before = runStore.listByVersion(seed.seed().version().getId(), 100).size();
        for (var fault : new String[]{"hash", "line", "original", "baseline", "legacy"}) {
            var bad = p.deepCopy();
            switch (fault) {case "hash" -> bad.put("sourceSha256", "0".repeat(64)); case "line" -> bad.put("lineNumber", r.path("lineNumber").asInt());
                case "original" -> bad.put("originalBhpLowerLimit", 499); case "baseline" -> bad.put("baselineRunId", 999999); default -> bad.put("schemaVersion", "eclipse-compositional-production-parameters/1");}
            var req = request(null, "eclipse"); req.setParameters(bad);
            assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), req)).isInstanceOf(RunException.class);
            assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(before);
        }
    }

    @Test void e300AvailableGasReinjectionUsesExactGroupAndRejectsInvalidSourcesBeforeRunCreation() throws Exception {
        var seed = eclipseBatchSeed();
        var i = objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-reinjection-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300"); modelMapper.updateById(seed.seed().model());
        seed.seed().version().setModelKind("eclipse_300"); seed.seed().version().setOriginalName("SPE3_GASCOND.DATA");
        seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i)); versionMapper.updateById(seed.seed().version());
        fakeWorker.compositionalAvailable = true;
        var c = i.path("compositionalReinjectionContext"); var r = c.path("records").get(0);
        var p = objectMapper.createObjectNode().put("schemaVersion", "eclipse-compositional-reinjection-parameters/1").put("baselineRunId", seed.baselineId()).put("targetFraction", .5);
        for (var k : new String[]{"sourceFile", "sourceSha256"}) p.set(k, c.get(k));
        for (var k : new String[]{"group", "phase", "lineNumber", "originalFraction"}) p.set(k, r.get(k)); p.put("unit", "fraction");
        var good = request(null, "eclipse"); good.setParameters(p);
        assertThat(runService.create(seed.seed().version().getId(), good).parameters().toString()).isEqualTo(p.toString());
        int before = runStore.listByVersion(seed.seed().version().getId(), 100).size();
        for (var fault : new String[]{"hash", "line", "original", "baseline", "legacy"}) {
            var bad = p.deepCopy();
            switch (fault) {case "hash" -> bad.put("sourceSha256", "0".repeat(64)); case "line" -> bad.put("lineNumber", r.path("salesLineNumber").asInt());
                case "original" -> bad.put("originalFraction", .9); case "baseline" -> bad.put("baselineRunId", 999999); default -> bad.put("schemaVersion", "eclipse-compositional-production-parameters/1");}
            var req = request(null, "eclipse"); req.setParameters(bad);
            assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), req)).isInstanceOf(RunException.class);
            assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(before);
        }
    }

    @Test void e300ConstantGasSalesUsesExactSalesRowAndRejectsInvalidSourcesBeforeRunCreation() throws Exception {
        var seed = eclipseBatchSeed();
        var i = objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-reinjection-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300"); modelMapper.updateById(seed.seed().model());
        seed.seed().version().setModelKind("eclipse_300"); seed.seed().version().setOriginalName("SPE3_GASCOND.DATA");
        seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i)); versionMapper.updateById(seed.seed().version());
        fakeWorker.compositionalAvailable = true;
        var c = i.path("compositionalReinjectionContext"); var r = c.path("records").get(0);
        var p = objectMapper.createObjectNode().put("schemaVersion", "eclipse-compositional-gas-sales-parameters/1").put("baselineRunId", seed.baselineId()).put("targetSalesTarget", 3000);
        for (var k : new String[]{"sourceFile", "sourceSha256"}) p.set(k, c.get(k));
        for (var k : new String[]{"group", "phase"}) p.set(k, r.get(k)); p.set("lineNumber", r.get("salesLineNumber")); p.set("originalSalesTarget", r.get("salesTarget")); p.put("unit", "Mscf/day");
        var good = request(null, "eclipse"); good.setParameters(p);
        assertThat(runService.create(seed.seed().version().getId(), good).parameters().toString()).isEqualTo(p.toString());
        int before = runStore.listByVersion(seed.seed().version().getId(), 100).size();
        for (var fault : new String[]{"hash", "line", "original", "baseline", "legacy"}) {
            var bad = p.deepCopy();
            switch (fault) {case "hash" -> bad.put("sourceSha256", "0".repeat(64)); case "line" -> bad.put("lineNumber", r.path("lineNumber").asInt());
                case "original" -> bad.put("originalSalesTarget", 1499); case "baseline" -> bad.put("baselineRunId", 999999); default -> bad.put("schemaVersion", "eclipse-compositional-production-parameters/1");}
            var req = request(null, "eclipse"); req.setParameters(bad);
            assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), req)).isInstanceOf(RunException.class);
            assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(before);
        }
    }

    @Test void e300InjectorUpperPressureUsesExactInjectionRowAndRejectsInvalidSourcesBeforeRunCreation() throws Exception {
        var seed = eclipseBatchSeed();
        var i = objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-reinjection-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300"); modelMapper.updateById(seed.seed().model());
        seed.seed().version().setModelKind("eclipse_300"); seed.seed().version().setOriginalName("SPE3_GASCOND.DATA");
        seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i)); versionMapper.updateById(seed.seed().version());
        fakeWorker.compositionalAvailable = true;
        var c = i.path("compositionalReinjectionContext"); var r = c.path("records").get(0);
        var p = objectMapper.createObjectNode().put("schemaVersion", "eclipse-compositional-injection-pressure-parameters/1").put("baselineRunId", seed.baselineId()).put("targetBhpUpperLimit", 3550);
        for (var k : new String[]{"sourceFile", "sourceSha256"}) p.set(k, c.get(k));
        p.set("well", r.get("injectionWell")); p.set("lineNumber", r.get("injectionLineNumber")); p.set("originalBhpUpperLimit", r.get("injectionBhpUpperLimit")); p.put("unit", "psia");
        var good = request(null, "eclipse"); good.setParameters(p);
        assertThat(runService.create(seed.seed().version().getId(), good).parameters().toString()).isEqualTo(p.toString());
        int before = runStore.listByVersion(seed.seed().version().getId(), 100).size();
        for (var fault : new String[]{"hash", "line", "original", "baseline", "legacy"}) {
            var bad = p.deepCopy();
            switch (fault) {case "hash" -> bad.put("sourceSha256", "0".repeat(64)); case "line" -> bad.put("lineNumber", 433);
                case "original" -> bad.put("originalBhpUpperLimit", 3999); case "baseline" -> bad.put("baselineRunId", 999999); default -> bad.put("schemaVersion", "eclipse-compositional-production-parameters/1");}
            var req = request(null, "eclipse"); req.setParameters(bad);
            assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), req)).isInstanceOf(RunException.class);
            assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(before);
        }
    }

    @Test void e300SaveForecastBindsPublishedNativeStateAndRejectsStaleRequestsWithoutCreatingRuns() throws Exception {
        var seed = eclipseBatchSeed();
        var inspection = objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-fluid-property-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300"); modelMapper.updateById(seed.seed().model());
        seed.seed().version().setModelKind("eclipse_300"); seed.seed().version().setOriginalName("SPE3_GASCOND.DATA");
        seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(inspection)); versionMapper.updateById(seed.seed().version());
        fakeWorker.compositionalAvailable = true;
        var history = runStore.find(seed.baselineId());
        history.setParametersJson("null"); history.setResultJson(objectMapper.writeValueAsString(objectMapper.readTree(
            getClass().getResourceAsStream("/eclipse-compositional-save-native-result.json")))); runMapper.updateById(history);
        var catalog = objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-save-catalog.json"));
        var folder = normalizer.resolve("artifacts/" + history.getId() + "/placeholder").getParent(); Files.createDirectories(folder);
        var published = new ArrayList<Path>();
        for (var file : catalog.path("files")) {
            var path = folder.resolve(file.path("artifactName").asText());
            try (var input = getClass().getResourceAsStream("/eclipse-compositional-save-files/" + file.path("name").asText())) { Files.write(path, input.readAllBytes()); }
            published.add(path);
        }
        var catalogPath = folder.resolve("eclipse-compositional-save-catalog.json"); Files.write(catalogPath, objectMapper.writeValueAsBytes(catalog)); published.add(catalogPath);
        for (var path : published) {
            var artifact = new SoftwareIntegrationArtifactEntity(); artifact.setRunId(history.getId()); artifact.setArtifactName(path.getFileName().toString()); artifact.setArtifactType("output");
            artifact.setContentType(path.equals(catalogPath) ? "application/json" : "application/octet-stream");
            artifact.setStorageKey("artifacts/" + history.getId() + "/" + path.getFileName()); artifact.setSizeBytes(Files.size(path)); artifact.setSha256(sha256(path));
            artifact.setCreatedAt(LocalDateTime.now()); artifact.setExpiresAt(LocalDateTime.now().plusDays(30)); artifactMapper.insert(artifact);
        }
        var p = objectMapper.createObjectNode().put("schemaVersion", "eclipse-compositional-forecast-parameters/1").put("historyRunId", history.getId())
            .put("sourceFile", catalog.path("sourceFile").asText()).put("sourceSha256", catalog.path("sourceSha256").asText())
            .put("saveCatalogSha256", sha256(catalogPath)).put("saveReport", 10).putNull("targetGasRate");
        var inherited = request(null, "eclipse"); inherited.setParameters(p);
        assertThat(runService.create(seed.seed().version().getId(), inherited).parameters().toString()).isEqualTo(p.toString());
        var explicitNull = objectMapper.readValue(p.toString(), java.util.LinkedHashMap.class);
        assertThat(explicitNull).containsKey("targetGasRate"); assertThat(explicitNull.get("targetGasRate")).isNull();
        var fromHttp = request(null, "eclipse"); fromHttp.setParameters(explicitNull);
        var inheritedHttp = runService.create(seed.seed().version().getId(), fromHttp);
        assertThat(inheritedHttp.parameters().path("targetGasRate").isNull()).isTrue();
        assertThat(inheritedHttp.parameters().has("targetGasRate")).isTrue();
        var missingTarget = new java.util.LinkedHashMap<>(explicitNull); missingTarget.remove("targetGasRate");
        var missingRequest = request(null, "eclipse"); missingRequest.setParameters(missingTarget);
        int beforeMissing = runStore.listByVersion(seed.seed().version().getId(), 100).size();
        assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), missingRequest)).isInstanceOf(RunException.class);
        assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(beforeMissing);
        p.put("targetGasRate", 4500); var changed = request(null, "eclipse"); changed.setParameters(p);
        assertThat(runService.create(seed.seed().version().getId(), changed).parameters().toString()).isEqualTo(p.toString());
        int before = runStore.listByVersion(seed.seed().version().getId(), 100).size();
        for (var fault : new String[]{"history", "source", "catalog", "report", "same", "zero", "legacy"}) {
            var bad = p.deepCopy();
            switch (fault) {
                case "history" -> bad.put("historyRunId", 999999); case "source" -> bad.put("sourceSha256", "0".repeat(64));
                case "catalog" -> bad.put("saveCatalogSha256", "0".repeat(64)); case "report" -> bad.put("saveReport", 11);
                case "same" -> bad.put("targetGasRate", 6200); case "zero" -> bad.put("targetGasRate", 0);
                default -> bad.put("schemaVersion", "eclipse-history-forecast-parameters/3");
            }
            var request = request(null, "eclipse"); request.setParameters(bad);
            assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), request)).isInstanceOf(RunException.class);
            assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(before);
        }
        var statePath = folder.resolve("eclipse-output-SPE3_GASCOND.V0010"); var bytes = Files.readAllBytes(statePath); bytes[bytes.length - 1] ^= 1; Files.write(statePath, bytes);
        assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), changed)).isInstanceOf(RunException.class)
            .satisfies(e -> assertThat(((RunException)e).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(before);
    }

    @Test void e300ComponentCriticalPressureUsesExactFieldAndComponentSourceAndRejectsInvalidSourcesBeforeRunCreation() throws Exception {
        var seed = eclipseBatchSeed();
        var i = objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-fluid-property-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300"); modelMapper.updateById(seed.seed().model());
        seed.seed().version().setModelKind("eclipse_300"); seed.seed().version().setOriginalName("SPE3_GASCOND.DATA");
        seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i)); versionMapper.updateById(seed.seed().version());
        fakeWorker.compositionalAvailable = true;
        var c = i.path("compositionalFluidPropertyContext"); var r = c.path("records").get(2);
        var p = objectMapper.createObjectNode().put("schemaVersion", "eclipse-compositional-fluid-property-parameters/1").put("baselineRunId", seed.baselineId()).put("targetValue", 700);
        for (var k : new String[]{"sourceFile", "sourceSha256"}) p.set(k, c.get(k));
        for (var k : new String[]{"componentIndex", "componentName", "field", "lineNumber", "unit", "originalValue"}) p.set(k, r.get(k));
        var good = request(null, "eclipse"); good.setParameters(p);
        assertThat(runService.create(seed.seed().version().getId(), good).parameters().toString()).isEqualTo(p.toString());
        int before = runStore.listByVersion(seed.seed().version().getId(), 100).size();
        for (var fault : new String[]{"hash", "line", "original", "baseline", "legacy"}) {
            var bad = p.deepCopy();
            switch (fault) {case "hash" -> bad.put("sourceSha256", "0".repeat(64)); case "line" -> bad.put("lineNumber", 123);
                case "original" -> bad.put("originalValue", 667); case "baseline" -> bad.put("baselineRunId", 999999); default -> bad.put("schemaVersion", "eclipse-compositional-production-parameters/1");}
            var req = request(null, "eclipse"); req.setParameters(bad);
            assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), req)).isInstanceOf(RunException.class);
            assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(before);
        }
    }

    @Test void e300ComponentCriticalTemperatureUsesRankineAndKeepsPressureAndRestartCapabilitiesAndRejectsInvalidSourcesBeforeRunCreation() throws Exception {
        var seed = eclipseBatchSeed();
        var i = objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-temperature-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300"); modelMapper.updateById(seed.seed().model());
        seed.seed().version().setModelKind("eclipse_300"); seed.seed().version().setOriginalName("SPE3_GASCOND.DATA");
        seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i)); versionMapper.updateById(seed.seed().version());
        fakeWorker.compositionalAvailable = true;
        var c = i.path("compositionalTemperatureContext"); var r = c.path("records").get(2);
        var p = objectMapper.createObjectNode().put("schemaVersion", "eclipse-compositional-temperature-parameters/1").put("baselineRunId", seed.baselineId()).put("targetValue", 350);
        for (var k : new String[]{"sourceFile", "sourceSha256"}) p.set(k, c.get(k));
        for (var k : new String[]{"componentIndex", "componentName", "field", "lineNumber", "unit", "originalValue"}) p.set(k, r.get(k));
        var good = request(null, "eclipse"); good.setParameters(p);
        assertThat(runService.create(seed.seed().version().getId(), good).parameters().toString()).isEqualTo(p.toString());
        int before = runStore.listByVersion(seed.seed().version().getId(), 100).size();
        for (var fault : new String[]{"hash", "line", "original", "baseline", "legacy"}) {
            var bad = p.deepCopy();
            switch (fault) {case "hash" -> bad.put("sourceSha256", "0".repeat(64)); case "line" -> bad.put("lineNumber", 122);
                case "original" -> bad.put("originalValue", 343); case "baseline" -> bad.put("baselineRunId", 999999); default -> bad.put("schemaVersion", "eclipse-compositional-production-parameters/1");}
            var req = request(null, "eclipse"); req.setParameters(bad);
            assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), req)).isInstanceOf(RunException.class);
            assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(before);
        }
    }

    @Test void e300ComponentCriticalZUsesDimensionlessSourceAndPreservesOtherCapabilitiesAndRejectsInvalidSourcesBeforeRunCreation() throws Exception {
        var seed = eclipseBatchSeed();
        var i = objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-critical-z-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300"); modelMapper.updateById(seed.seed().model());
        seed.seed().version().setModelKind("eclipse_300"); seed.seed().version().setOriginalName("SPE3_GASCOND.DATA");
        seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i)); versionMapper.updateById(seed.seed().version());
        fakeWorker.compositionalAvailable = true;
        var c = i.path("compositionalCriticalZContext"); var r = c.path("records").get(2);
        var p = objectMapper.createObjectNode().put("schemaVersion", "eclipse-compositional-critical-z-parameters/1").put("baselineRunId", seed.baselineId()).put("targetValue", 0.3);
        for (var k : new String[]{"sourceFile", "sourceSha256"}) p.set(k, c.get(k));
        for (var k : new String[]{"componentIndex", "componentName", "field", "lineNumber", "unit", "originalValue"}) p.set(k, r.get(k));
        var good = request(null, "eclipse"); good.setParameters(p);
        assertThat(runService.create(seed.seed().version().getId(), good).parameters().toString()).isEqualTo(p.toString());
        int before = runStore.listByVersion(seed.seed().version().getId(), 100).size();
        for (var fault : new String[]{"hash", "line", "original", "baseline", "legacy"}) {
            var bad = p.deepCopy();
            switch (fault) {case "hash" -> bad.put("sourceSha256", "0".repeat(64)); case "line" -> bad.put("lineNumber", 122);
                case "original" -> bad.put("originalValue", 343); case "baseline" -> bad.put("baselineRunId", 999999); default -> bad.put("schemaVersion", "eclipse-compositional-production-parameters/1");}
            var req = request(null, "eclipse"); req.setParameters(bad);
            assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), req)).isInstanceOf(RunException.class);
            assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(before);
        }
    }

    @Test void e300ComponentAcentricFactorUsesDimensionlessSourceAndPreservesOtherCapabilitiesAndRejectsInvalidSourcesBeforeRunCreation() throws Exception {
        var seed = eclipseBatchSeed();
        var i = objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-acentric-factor-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300"); modelMapper.updateById(seed.seed().model());
        seed.seed().version().setModelKind("eclipse_300"); seed.seed().version().setOriginalName("SPE3_GASCOND.DATA");
        seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i)); versionMapper.updateById(seed.seed().version());
        fakeWorker.compositionalAvailable = true;
        var c = i.path("compositionalAcentricFactorContext"); var r = c.path("records").get(2);
        var p = objectMapper.createObjectNode().put("schemaVersion", "eclipse-compositional-acentric-factor-parameters/1").put("baselineRunId", seed.baselineId()).put("targetValue", 0.03);
        for (var k : new String[]{"sourceFile", "sourceSha256"}) p.set(k, c.get(k));
        for (var k : new String[]{"componentIndex", "componentName", "field", "lineNumber", "unit", "originalValue"}) p.set(k, r.get(k));
        var good = request(null, "eclipse"); good.setParameters(p);
        assertThat(runService.create(seed.seed().version().getId(), good).parameters().toString()).isEqualTo(p.toString());
        int before = runStore.listByVersion(seed.seed().version().getId(), 100).size();
        for (var fault : new String[]{"hash", "line", "original", "baseline", "legacy"}) {
            var bad = p.deepCopy();
            switch (fault) {case "hash" -> bad.put("sourceSha256", "0".repeat(64)); case "line" -> bad.put("lineNumber", 122);
                case "original" -> bad.put("originalValue", 343); case "baseline" -> bad.put("baselineRunId", 999999); default -> bad.put("schemaVersion", "eclipse-compositional-production-parameters/1");}
            var req = request(null, "eclipse"); req.setParameters(bad);
            assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), req)).isInstanceOf(RunException.class);
            assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(before);
        }
    }

    @Test void e300MolecularWeightUsesDimensionlessSourceAndPreservesOtherCapabilitiesAndRejectsInvalidSourcesBeforeRunCreation() throws Exception {
        var seed = eclipseBatchSeed();
        var i = objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-eos-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300"); modelMapper.updateById(seed.seed().model());
        seed.seed().version().setModelKind("eclipse_300"); seed.seed().version().setOriginalName("SPE3_GASCOND.DATA");
        seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i)); versionMapper.updateById(seed.seed().version());
        fakeWorker.compositionalAvailable = true;
        var c = i.path("compositionalEosPropertyContexts").get(0); var r = c.path("records").get(2);
        var p = objectMapper.createObjectNode().put("schemaVersion", "eclipse-compositional-eos-property-parameters/1").put("baselineRunId", seed.baselineId()).put("targetValue", 17.0);
        for (var k : new String[]{"sourceFile", "sourceSha256"}) p.set(k, c.get(k));
        for (var k : new String[]{"componentIndex", "componentName", "field", "lineNumber", "unit", "originalValue"}) p.set(k, r.get(k));
        var good = request(null, "eclipse"); good.setParameters(p);
        assertThat(runService.create(seed.seed().version().getId(), good).parameters().toString()).isEqualTo(p.toString());
        int before = runStore.listByVersion(seed.seed().version().getId(), 100).size();
        for (var fault : new String[]{"hash", "line", "original", "baseline", "legacy"}) {
            var bad = p.deepCopy();
            switch (fault) {case "hash" -> bad.put("sourceSha256", "0".repeat(64)); case "line" -> bad.put("lineNumber", 122);
                case "original" -> bad.put("originalValue", 343); case "baseline" -> bad.put("baselineRunId", 999999); default -> bad.put("schemaVersion", "eclipse-compositional-production-parameters/1");}
            var req = request(null, "eclipse"); req.setParameters(bad);
            assertThatThrownBy(() -> runService.create(seed.seed().version().getId(), req)).isInstanceOf(RunException.class);
            assertThat(runStore.listByVersion(seed.seed().version().getId(), 100)).hasSize(before);
        }
    }

    @Test void thermalQualityAndTemperatureRequireTheirOwnPhysicalSourceAndOriginalBaseline() throws Exception {
        var seed=eclipseBatchSeed();var i=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-injection-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300");modelMapper.updateById(seed.seed().model());
        seed.seed().version().setModelKind("eclipse_300");seed.seed().version().setOriginalName("THERM3.DATA");seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i));versionMapper.updateById(seed.seed().version());fakeWorker.compositionalAvailable=true;
        var c=i.path("thermalInjectionContext");var r=c.path("records").get(0);
        var p=objectMapper.createObjectNode().put("schemaVersion","eclipse-thermal-injection-parameters/1").put("baselineRunId",seed.baselineId()).put("field","STEAM_QUALITY").put("unit","fraction").put("originalValue",.7).put("targetValue",.5);
        for(var k:new String[]{"sourceFile","sourceSha256"})p.set(k,c.get(k));for(var k:new String[]{"well","lineNumber"})p.set(k,r.get(k));
        var good=request(null,"eclipse");good.setParameters(p);assertThat(runService.create(seed.seed().version().getId(),good).parameters().toString()).isEqualTo(p.toString());
        var t=p.deepCopy().put("field","INJECTION_TEMPERATURE").put("unit","degF").put("originalValue",450).put("targetValue",400);good=request(null,"eclipse");good.setParameters(t);assertThat(runService.create(seed.seed().version().getId(),good).parameters().toString()).isEqualTo(t.toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();
        for(var fault:new String[]{"hash","line","original","baseline","foreign"}){
            var bad=p.deepCopy();switch(fault){case "hash"->bad.put("sourceSha256","0".repeat(64));case "line"->bad.put("lineNumber",1);case "original"->bad.put("originalValue",.6);case "baseline"->bad.put("baselineRunId",999999);default->bad.put("schemaVersion","eclipse-compositional-production-parameters/1");}
            var invalid=request(null,"eclipse");invalid.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),invalid)).isInstanceOf(RunException.class);assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);
        }
    }

    @Test void thermalRateAndUpperPressureUseSchemaTwoAndTheSeparateControlRow() throws Exception {
        var seed=eclipseBatchSeed();var i=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-injection-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300");modelMapper.updateById(seed.seed().model());seed.seed().version().setModelKind("eclipse_300");seed.seed().version().setOriginalName("THERM3.DATA");seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i));versionMapper.updateById(seed.seed().version());fakeWorker.compositionalAvailable=true;
        var c=i.path("thermalInjectionContext");var r=c.path("records").get(0);
        var p=objectMapper.createObjectNode().put("schemaVersion","eclipse-thermal-injection-parameters/2").put("baselineRunId",seed.baselineId()).put("field","SURFACE_INJECTION_RATE").put("unit","STB/day").put("originalValue",37.5).put("targetValue",25).put("lineNumber",429);
        for(var k:new String[]{"sourceFile","sourceSha256"})p.set(k,c.get(k));p.set("well",r.get("well"));var good=request(null,"eclipse");good.setParameters(p);assertThat(runService.create(seed.seed().version().getId(),good).parameters().toString()).isEqualTo(p.toString());
        var pressure=p.deepCopy().put("field","BHP_UPPER_LIMIT").put("unit","psia").put("originalValue",1000).put("targetValue",700);good=request(null,"eclipse");good.setParameters(pressure);assertThat(runService.create(seed.seed().version().getId(),good).parameters().toString()).isEqualTo(pressure.toString());
        int before=runStore.listByVersion(seed.seed().version().getId(),100).size();
        for(var fault:new String[]{"line","unit","original","schema","baseline"}){
            var bad=p.deepCopy();switch(fault){case "line"->bad.put("lineNumber",433);case "unit"->bad.put("unit","psia");case "original"->bad.put("originalValue",37);case "schema"->bad.put("schemaVersion","eclipse-thermal-injection-parameters/1");default->bad.put("baselineRunId",999999);}
            var invalid=request(null,"eclipse");invalid.setParameters(bad);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),invalid)).isInstanceOf(RunException.class);assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(before);
        }
    }


    private BatchSeed compositionalBatchSeed() throws Exception {
        var original=eclipseBatchSeed();var seed=original.seed();var i=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-eos-inspection.json"));
        seed.model().setSimulatorType("ECLIPSE_300");modelMapper.updateById(seed.model());seed.version().setModelKind("eclipse_300");seed.version().setOriginalName("SPE3_GASCOND.DATA");seed.version().setInspectionJson(objectMapper.writeValueAsString(i));versionMapper.updateById(seed.version());fakeWorker.compositionalAvailable=true;
        var baseline=runStore.find(original.baselineId());baseline.setResultJson(objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-batch-baseline-result.json")).toString());runMapper.updateById(baseline);
        var req=objectMapper.createObjectNode().put("requestKey",UUID.randomUUID().toString()).put("name","组分物性批量").put("baselineRunId",baseline.getId());var cases=req.putArray("cases");
        for(int j=0;j<4;j++){
            var c=j==0?i.path("compositionalAcentricFactorContext"):i.path("compositionalEosPropertyContexts").get(j-1);var row=c.path("records").get(2);var p=cases.addObject().put("name",row.path("field").asText()).putObject("parameters").put("schemaVersion",j==0?"eclipse-compositional-acentric-factor-parameters/1":"eclipse-compositional-eos-property-parameters/1").put("baselineRunId",baseline.getId()).put("targetValue",new double[]{.03,17,.54,.078}[j]);
            for(var k:new String[]{"sourceFile","sourceSha256"})p.set(k,c.get(k));for(var k:new String[]{"componentIndex","componentName","field","lineNumber","unit","originalValue"})p.set(k,row.get(k));
        }
        return new BatchSeed(seed,baseline.getId(),req);
    }
    @Test void compositionalBatchPersistsFourSourceBoundChildrenAndRetriesWithoutDuplicates() throws Exception {
        var seed=compositionalBatchSeed();var batch=eclipseBatches.create(seed.seed().version().getId(),seed.request());assertThat(batch.members()).hasSize(4);assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(5);
        for(int j=0;j<4;j++)assertThat(com.grdp.studio.softwareintegration.support.EclipseBatchParameters.sameContent(batch.members().get(j).run().parameters(),seed.request().path("cases").get(j).path("parameters"))).isTrue();
        var replay=eclipseBatches.create(seed.seed().version().getId(),seed.request().deepCopy());assertThat(replay.id()).isEqualTo(batch.id());assertThat(replay.members().stream().map(m->m.run().id())).containsExactlyElementsOf(batch.members().stream().map(m->m.run().id()).toList());assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(5);
        var conflict=seed.request().deepCopy().put("name","不同内容");assertThatThrownBy(()->eclipseBatches.create(seed.seed().version().getId(),conflict)).isInstanceOf(RunException.class);
    }
    @Test void invalidSecondCompositionalSourceRollsBackTheEntireCollectionAndEveryQueuedChild() throws Exception {
        var seed=compositionalBatchSeed();((ObjectNode)seed.request().path("cases").get(1).path("parameters")).put("sourceSha256","0".repeat(64));
        assertThatThrownBy(()->eclipseBatches.create(seed.seed().version().getId(),seed.request())).isInstanceOf(RunException.class);assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(1);assertThat(eclipseBatches.list(seed.seed().version().getId())).isEmpty();
    }
    @Test void compositionalBatchCannotUseAnE100BaselineEvenWhenEveryParameterIsStructurallyValid() throws Exception {
        var comp=compositionalBatchSeed();var old=eclipseBatchSeed();var request=comp.request().deepCopy().put("baselineRunId",old.baselineId());for(var item:request.path("cases"))((ObjectNode)item.path("parameters")).put("baselineRunId",old.baselineId());
        assertThatThrownBy(()->eclipseBatches.create(old.seed().version().getId(),request)).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.CONFLICT));assertThat(runStore.listByVersion(old.seed().version().getId(),100)).hasSize(1);assertThat(eclipseBatches.list(old.seed().version().getId())).isEmpty();
    }
    private String compositionalHistoryCsv() {
        var result=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-batch-baseline-result.json"));String csv="well,keyword,unit,timeDays,value,vectorIndex\n";
        for(var curve:result.path("summary").path("series"))if("FOPT".equals(curve.path("keyword").asText()))csv+=",FOPT,STB,5475,2593602.25,"+curve.path("vectorIndex").asInt()+"\n";return csv;
    }
    @Test void compositionalHistoryPersistsBothRawOilColumnsAndReusesSource()throws Exception {
        var seed=compositionalBatchSeed();var request=eclipseHistoryRequest(seed.baselineId(),compositionalHistoryCsv());var dataset=eclipseHistory.create(seed.seed().version().getId(),request);
        assertThat(dataset.observations()).hasSize(2);assertThat(dataset.csv()).isEqualTo(compositionalHistoryCsv());
        assertThat(eclipseHistory.create(seed.seed().version().getId(),request).metadata().id()).isEqualTo(dataset.metadata().id());
        var comparison=eclipseHistory.compare(dataset.metadata().id(),seed.baselineId());assertThat(comparison.comparison().metrics()).hasSize(2);assertThat(comparison.comparison().metrics()).allSatisfy(m->{assertThat(m.rmse()).isZero();assertThat(m.vectorIndex()).isNotNull();});
    }
    @Test void compositionalHistoryRejectsAmbiguousLegacyCsvAndForeignColumnBeforeSaving()throws Exception {
        var seed=compositionalBatchSeed();
        for(var csv:new String[]{"well,keyword,unit,timeDays,value\n,FOPT,STB,5475,1",compositionalHistoryCsv().replace("FOPT","FPR"),compositionalHistoryCsv().replace("STB","SM3")})assertThatThrownBy(()->eclipseHistory.create(seed.seed().version().getId(),eclipseHistoryRequest(seed.baselineId(),csv))).isInstanceOf(RunException.class).satisfies(e->assertThat(((RunException)e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(eclipseHistory.list(seed.seed().version().getId())).isEmpty();
    }

    @Test void thermalStageBoundaryIsValidatedBeforeAnyQueueEntry()throws Exception {
        var seed=eclipseBatchSeed();var i=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-injection-inspection.json"));seed.seed().model().setSimulatorType("ECLIPSE_300");modelMapper.updateById(seed.seed().model());seed.seed().version().setModelKind("eclipse_300");seed.seed().version().setOriginalName("THERM3.DATA");seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i));versionMapper.updateById(seed.seed().version());fakeWorker.compositionalAvailable=true;
        var c=i.path("thermalInjectionContext");var p=objectMapper.createObjectNode().put("schemaVersion","eclipse-thermal-injection-parameters/3").put("baselineRunId",seed.baselineId()).put("field","SURFACE_INJECTION_RATE").put("unit","STB/day").put("originalValue",37.5).put("targetValue",25).put("lineNumber",429).put("well","I").put("startTimeDays",1825);for(var k:new String[]{"sourceFile","sourceSha256"})p.set(k,c.get(k));
        for(int day:new int[]{0,1824,3650}){var bad=request(null,"eclipse");bad.setParameters(p.deepCopy().put("startTimeDays",day));assertThatThrownBy(()->runService.create(seed.seed().version().getId(),bad)).isInstanceOf(RunException.class);assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(1);}
        var good=request(null,"eclipse");good.setParameters(p);assertThat(runService.create(seed.seed().version().getId(),good).parameters().toString()).isEqualTo(p.toString());
    }


    @Test void thermalMultiStagePlanChecksEveryBoundarySourceAndBaselineBeforeQueue() throws Exception {
        var seed=eclipseBatchSeed();var i=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-injection-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300");modelMapper.updateById(seed.seed().model());seed.seed().version().setModelKind("eclipse_300");seed.seed().version().setOriginalName("THERM3.DATA");seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i));versionMapper.updateById(seed.seed().version());fakeWorker.compositionalAvailable=true;
        var c=i.path("thermalInjectionContext");var r=c.path("records").get(0);
        var p=objectMapper.createObjectNode().put("schemaVersion","eclipse-thermal-injection-plan-parameters/1").put("baselineRunId",seed.baselineId()).put("temperatureUnit","degF").put("rateUnit","STB/day").put("pressureUnit","psia");
        for(var k:new String[]{"sourceFile","sourceSha256"})p.set(k,c.get(k));for(var k:new String[]{"well","lineNumber","controlLineNumber"})p.set(k,r.get(k));
        p.putObject("original").put("surfaceInjectionRate",37.5).put("injectionTemperature",450).put("steamQuality",.7).put("bhpUpperLimit",1000);
        var stages=p.putArray("stages");stages.addObject().put("startTimeDays",1095).put("surfaceInjectionRate",25).put("injectionTemperature",450).put("steamQuality",.7).put("bhpUpperLimit",1000);
        stages.addObject().put("startTimeDays",2190).put("surfaceInjectionRate",25).put("injectionTemperature",400).put("steamQuality",.7).put("bhpUpperLimit",1000);
        for(var fault:new String[]{"boundary","end","hash","baseline","noop"}) {
            var invalid=p.deepCopy();switch(fault){case "boundary"->((ObjectNode)invalid.path("stages").get(0)).put("startTimeDays",1094);case "end"->((ObjectNode)invalid.path("stages").get(1)).put("startTimeDays",3650);case "hash"->invalid.put("sourceSha256","0".repeat(64));case "baseline"->invalid.put("baselineRunId",Long.MAX_VALUE);default->invalid.path("original").properties().forEach(e->((ObjectNode)invalid.path("stages").get(0)).set(e.getKey(),e.getValue()));}
            var bad=request(null,"eclipse");bad.setParameters(invalid);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),bad)).isInstanceOf(RunException.class);assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(1);
        }
        var good=request(null,"eclipse");good.setParameters(p);assertThat(runService.create(seed.seed().version().getId(),good).parameters().toString()).isEqualTo(p.toString());
    }

    @Test void thermalRockSourceBoundaryAndBaselineAreCheckedBeforeQueue() throws Exception {
        var seed=eclipseBatchSeed();var i=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-rock-official-inspection.json"));
        seed.seed().model().setSimulatorType("ECLIPSE_300");modelMapper.updateById(seed.seed().model());seed.seed().version().setModelKind("eclipse_300");seed.seed().version().setOriginalName("THERM3.DATA");seed.seed().version().setInspectionJson(objectMapper.writeValueAsString(i));versionMapper.updateById(seed.seed().version());fakeWorker.compositionalAvailable=true;
        var c=i.path("thermalRockContext");var r=c.path("records").get(0);
        var p=objectMapper.createObjectNode().put("schemaVersion","eclipse-thermal-rock-parameters/1").put("baselineRunId",seed.baselineId()).put("targetValue",45);
        for(var k:new String[]{"sourceFile","sourceSha256","cellCount"})p.set(k,c.get(k));for(var k:new String[]{"field","lineNumber","unit","originalValue"})p.set(k,r.get(k));
        for(var fault:new String[]{"source","line","count","original","unit","zero","baseline"}){
            var invalid=p.deepCopy();switch(fault){case "source"->invalid.put("sourceSha256","0".repeat(64));case "line"->invalid.put("lineNumber",1);case "count"->invalid.put("cellCount",100);case "original"->invalid.put("originalValue",34);case "unit"->invalid.put("unit","degF");case "zero"->invalid.put("targetValue",0);default->invalid.put("baselineRunId",Long.MAX_VALUE);}
            var bad=request(null,"eclipse");bad.setParameters(invalid);assertThatThrownBy(()->runService.create(seed.seed().version().getId(),bad)).isInstanceOf(RunException.class);assertThat(runStore.listByVersion(seed.seed().version().getId(),100)).hasSize(1);
        }
        var good=request(null,"eclipse");good.setParameters(p);assertThat(runService.create(seed.seed().version().getId(),good).parameters().toString()).isEqualTo(p.toString());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void nativeThermal23PublishesAsVersionThreeAndRejectsWrongTemperatureUnits(boolean wrongUnit) throws Exception {
        var seed=seed("READY","models/eclipse/thermal-rock/THERM3.DATA","ECLIPSE_100");
        var i=objectMapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-rock-output-inspection.json"));
        seed.model().setSimulatorType("ECLIPSE_300");modelMapper.updateById(seed.model());seed.version().setModelKind("eclipse_300");seed.version().setOriginalName("THERM3.DATA");seed.version().setInspectionJson(objectMapper.writeValueAsString(i));versionMapper.updateById(seed.version());fakeWorker.compositionalAvailable=true;
        long id=runService.create(seed.version().getId(),request(null,"eclipse")).id();var dispatcher=dispatcher();dispatcher.dispatch();
        var result=(ObjectNode)objectMapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-rock-native-temperature-result.json"));
        if(wrongUnit){boolean changed=false;for(var series:result.path("summary").path("series"))if("WTEMP".equals(series.path("keyword").asText())){((ObjectNode)series).put("unit","DEG C");changed=true;break;}assertThat(changed).isTrue();}
        var folder=STORAGE_ROOT.resolve("jobs/"+id+"/output");Files.createDirectories(folder);
        var normalized=folder.resolve("normalized-result.json");Files.writeString(normalized,objectMapper.writeValueAsString(result));var log=folder.resolve("run.log");Files.writeString(log,"SUCCEEDED\n");
        var manifest=folder.resolve("manifest.json");Files.writeString(manifest,"""
          {"schemaVersion":"grdp-worker-artifact-manifest/1","runId":%d,"generatedAtUtc":"2026-10-19T00:00:00Z","files":[
          {"storageKey":"jobs/%d/output/normalized-result.json","size":%d,"sha256":"%s","contentType":"application/json"},
          {"storageKey":"jobs/%d/output/run.log","size":%d,"sha256":"%s","contentType":"text/plain"}]}
          """.formatted(id,id,Files.size(normalized),sha256(normalized),id,Files.size(log),sha256(log)));
        fakeWorker.snapshot=new WorkerRunSnapshot(id,"SUCCEEDED",4,"worker-1","generation-1",List.of(
            new WorkerRunEvent(1,"PREPARING",Instant.now(),"prepare"),new WorkerRunEvent(2,"RUNNING_ECLIPSE",Instant.now(),"solve"),new WorkerRunEvent(3,"COLLECTING",Instant.now(),"collect"),new WorkerRunEvent(4,"SUCCEEDED",Instant.now(),"done")),result,null,List.of(
            new WorkerRunArtifact("jobs/"+id+"/output/normalized-result.json",Files.size(normalized),sha256(normalized),"application/json"),
            new WorkerRunArtifact("jobs/"+id+"/output/run.log",Files.size(log),sha256(log),"text/plain"),
            new WorkerRunArtifact("jobs/"+id+"/output/manifest.json",Files.size(manifest),sha256(manifest),"application/json")),objectMapper.readTree("{\"processTreeExitConfirmed\":true}"));
        dispatcher.poll();
        if(wrongUnit){assertThat(runService.get(id).status()).isEqualTo("FAILED");assertThat(runService.get(id).error().path("message").asText()).contains("eclipse-summary-result/3");}
        else{assertThat(runService.get(id).status()).isEqualTo("SUCCEEDED");assertThat(runService.get(id).result().path("schemaVersion").asText()).isEqualTo("eclipse-summary-result/3");assertThat(runService.get(id).result().path("summary").path("series").size()).isEqualTo(343);}
    }
}
