package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationProjectEntity;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelVersionMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationProjectMapper;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationService;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationStorageKeyNormalizer;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationValidationDispatcher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(properties = "grdp.software-integration.dispatcher-enabled=false")
class SoftwareIntegrationValidationDispatcherTests {
    private static final AtomicReference<String> RESPONSE = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST = new AtomicReference<>();
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    private static HttpServer server;

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SoftwareIntegrationProjectMapper projectMapper;
    @Autowired SoftwareIntegrationModelMapper modelMapper;
    @Autowired SoftwareIntegrationModelVersionMapper versionMapper;
    @Autowired SoftwareIntegrationService softwareIntegrationService;
    @Autowired ObjectMapper objectMapper;

    @BeforeAll
    static void startWorker() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/models/validate", exchange -> {
            REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, RESPONSE_STATUS.get(), RESPONSE.get());
        });
        server.start();
    }

    @AfterAll
    static void stopWorker() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("DELETE FROM software_integration_artifact");
        jdbcTemplate.execute("DELETE FROM software_integration_run_event");
        jdbcTemplate.execute("DELETE FROM software_integration_run");
        jdbcTemplate.execute("DELETE FROM software_integration_model_version");
        jdbcTemplate.execute("DELETE FROM software_integration_model");
        jdbcTemplate.execute("DELETE FROM software_integration_project");
        REQUEST.set(null);
        RESPONSE_STATUS.set(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"network", "black_oil_liquid", "basic_gas"})
    void successfulValidationPersistsSimulatorTypeAndExposesItThroughModelApi(String modelKind) {
        Seed seed = seed();
        RESPONSE.set("""
                {"status":"READY","studies":["Study 1","Network Study"],"message":"模型验证完成","modelKind":"%s"}
                """.formatted(modelKind));
        SoftwareIntegrationProperties properties = new SoftwareIntegrationProperties();
        properties.setWorkerBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        SoftwareIntegrationValidationDispatcher dispatcher = new SoftwareIntegrationValidationDispatcher(
                versionMapper, modelMapper, properties, objectMapper, new SoftwareIntegrationStorageKeyNormalizer(properties));

        dispatcher.validate(seed.versionId());

        String expectedType = "network".equals(modelKind) ? "PIPESIM_NETWORK" : "PIPESIM_WELL";
        assertThat(modelMapper.selectById(seed.modelId()).getSimulatorType()).isEqualTo(expectedType);
        SoftwareIntegrationModelVersionEntity version = versionMapper.selectById(seed.versionId());
        assertThat(version.getStatus()).isEqualTo("READY");
        assertThat(version.getModelKind()).isEqualTo(modelKind);
        assertThat(version.getStudiesJson()).isEqualTo("Study 1\nNetwork Study");
        assertThat(softwareIntegrationService.getProject(seed.projectId()).models()).singleElement()
                .satisfies(model -> {
                    assertThat(model.simulatorType()).isEqualTo(expectedType);
                    assertThat(model.versions()).singleElement()
                            .satisfies(item -> assertThat(item.modelKind()).isEqualTo(modelKind));
                });
        JsonNode request = objectMapper.readTree(REQUEST.get());
        assertThat(request.path("modelStorageKey").asText()).isEqualTo("models/validation/model.pips");
        assertThat(request.path("expectedSha256").asText()).isEqualTo("a".repeat(64));
    }

    @Test
    void failedRevalidationPreservesKnownKindAndSanitizesWorkerDiagnostics() {
        Seed seed = seed();
        SoftwareIntegrationModelVersionEntity existing = versionMapper.selectById(seed.versionId());
        existing.setModelKind("black_oil_liquid");
        versionMapper.updateById(existing);
        RESPONSE.set("""
                {"status":"INVALID","studies":[],"modelKind":"network",
                 "message":"Service net.pipe://localhost/pipe/private-id failed\\nPath C:\\\\Users\\\\operator\\\\model.tnt"}
                """);
        SoftwareIntegrationProperties properties = new SoftwareIntegrationProperties();
        properties.setWorkerBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        SoftwareIntegrationValidationDispatcher dispatcher = new SoftwareIntegrationValidationDispatcher(
                versionMapper, modelMapper, properties, objectMapper, new SoftwareIntegrationStorageKeyNormalizer(properties));

        dispatcher.validate(seed.versionId());

        SoftwareIntegrationModelVersionEntity version = versionMapper.selectById(seed.versionId());
        assertThat(version.getStatus()).isEqualTo("INVALID");
        assertThat(version.getModelKind()).isEqualTo("black_oil_liquid");
        assertThat(version.getValidationMessage())
                .contains("[redacted]", "[local path]")
                .doesNotContain("private-id", "C:\\Users");
        assertThat(softwareIntegrationService.getProject(seed.projectId()).models().get(0).versions().get(0).validationMessage())
                .doesNotContain("private-id", "C:\\Users");
    }

    @Test
    void dataValidationPersistsEclipseTypeKindAndStrictlyEmptyStudies() {
        Seed seed = seed("CASE.DATA");
        RESPONSE.set("""
                {"status":"READY","studies":[],"message":"ECLIPSE validation complete","modelKind":"eclipse_100"}
                """);

        dispatcher().validate(seed.versionId());

        SoftwareIntegrationModelVersionEntity version = versionMapper.selectById(seed.versionId());
        assertThat(version.getStatus()).isEqualTo("READY");
        assertThat(version.getModelKind()).isEqualTo("eclipse_100");
        assertThat(version.getStudiesJson()).isNull();
        assertThat(modelMapper.selectById(seed.modelId()).getSimulatorType()).isEqualTo("ECLIPSE_100");
        assertThat(softwareIntegrationService.getProject(seed.projectId()).models()).singleElement()
                .satisfies(model -> {
                    assertThat(model.simulatorType()).isEqualTo("ECLIPSE_100");
                    assertThat(model.versions().get(0).studies()).isEmpty();
                });
    }

    @Test
    void extensionModelKindMismatchAndEclipseStudiesAreRejected() {
        Seed dataWithPipesimKind = seed("CASE.DATA");
        RESPONSE.set("""
                {"status":"READY","studies":[],"message":"wrong","modelKind":"black_oil_liquid"}
                """);
        dispatcher().validate(dataWithPipesimKind.versionId());
        assertThat(versionMapper.selectById(dataWithPipesimKind.versionId()).getStatus()).isEqualTo("INVALID");
        assertThat(versionMapper.selectById(dataWithPipesimKind.versionId()).getValidationMessage())
                .startsWith("MODEL_KIND_EXTENSION_MISMATCH:");

        Seed pipsWithEclipseKind = seed("model.pips");
        RESPONSE.set("""
                {"status":"READY","studies":[],"message":"wrong","modelKind":"eclipse_100"}
                """);
        dispatcher().validate(pipsWithEclipseKind.versionId());
        assertThat(versionMapper.selectById(pipsWithEclipseKind.versionId()).getStatus()).isEqualTo("INVALID");

        Seed eclipseWithStudy = seed("CASE2.DATA");
        RESPONSE.set("""
                {"status":"READY","studies":["Study 1"],"message":"wrong","modelKind":"eclipse_100"}
                """);
        dispatcher().validate(eclipseWithStudy.versionId());
        assertThat(versionMapper.selectById(eclipseWithStudy.versionId()).getStatus()).isEqualTo("INVALID");
        assertThat(versionMapper.selectById(eclipseWithStudy.versionId()).getValidationMessage())
                .startsWith("ECLIPSE_STUDY_UNSUPPORTED:");
    }

    @Test
    void includeFailureStaysInvalidWhileLauncherFailureIsEnvironmentError() {
        Seed include = seed("CASE.DATA");
        RESPONSE_STATUS.set(503);
        RESPONSE.set("""
                {"status":"INVALID","studies":[],"message":"unsupported","modelKind":null,
                 "error":{"category":"MODEL","code":"ECLIPSE_INCLUDE_UNSUPPORTED","message":"INCLUDE is unsupported","retryable":false}}
                """);
        dispatcher().validate(include.versionId());
        SoftwareIntegrationModelVersionEntity invalid = versionMapper.selectById(include.versionId());
        assertThat(invalid.getStatus()).isEqualTo("INVALID");
        assertThat(invalid.getValidationMessage()).startsWith("ECLIPSE_INCLUDE_UNSUPPORTED:");

        Seed unavailable = seed("CASE2.DATA");
        RESPONSE_STATUS.set(503);
        RESPONSE.set("""
                {"status":"ENVIRONMENT_ERROR","studies":[],"message":"unavailable","modelKind":null,
                 "error":{"category":"ENVIRONMENT","code":"ECLIPSE_UNAVAILABLE","message":"launcher unavailable","retryable":true}}
                """);
        dispatcher().validate(unavailable.versionId());
        SoftwareIntegrationModelVersionEntity environment = versionMapper.selectById(unavailable.versionId());
        assertThat(environment.getStatus()).isEqualTo("ENVIRONMENT_ERROR");
        assertThat(environment.getValidationMessage()).startsWith("ECLIPSE_UNAVAILABLE:");
    }

    private Seed seed() {
        return seed("model.pips");
    }

    private Seed seed(String originalName) {
        LocalDateTime now = LocalDateTime.now();
        SoftwareIntegrationProjectEntity project = new SoftwareIntegrationProjectEntity();
        project.setName("validation-" + UUID.randomUUID());
        project.setCreatedBy("administrator");
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        projectMapper.insert(project);

        SoftwareIntegrationModelEntity model = new SoftwareIntegrationModelEntity();
        model.setProjectId(project.getId());
        model.setName("model");
        model.setSimulatorType("PIPESIM_WELL");
        model.setCreatedAt(now);
        model.setUpdatedAt(now);
        modelMapper.insert(model);

        SoftwareIntegrationModelVersionEntity version = new SoftwareIntegrationModelVersionEntity();
        version.setModelId(model.getId());
        version.setVersionNo(1);
        version.setOriginalName(originalName);
        version.setStorageKey("models/validation/" + originalName);
        version.setSha256("a".repeat(64));
        version.setSizeBytes(1L);
        version.setStatus("UPLOADED");
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        versionMapper.insert(version);
        return new Seed(project.getId(), model.getId(), version.getId());
    }

    private SoftwareIntegrationValidationDispatcher dispatcher() {
        SoftwareIntegrationProperties properties = new SoftwareIntegrationProperties();
        properties.setWorkerBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new SoftwareIntegrationValidationDispatcher(
                versionMapper, modelMapper, properties, objectMapper, new SoftwareIntegrationStorageKeyNormalizer(properties));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Seed(long projectId, long modelId, long versionId) {}
}
