package com.grdp.studio.softwareintegration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationProjectEntity;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelVersionMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationProjectMapper;
import com.grdp.studio.softwareintegration.client.WorkerTemplateCreationClient;
import com.grdp.studio.softwareintegration.service.TemplateCreationWorkflow;
import com.grdp.studio.softwareintegration.support.TemplateCreationJobStore;
import com.grdp.studio.softwareintegration.service.impl.SoftwareIntegrationServiceImpl;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationSchemaInitializer;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationStorageKeyNormalizer;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationValidationDispatcher;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationValidationJobStore;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real JDBC/MyBatis transactions and independent service instances; no run-flow fixture changes. */
public class SoftwareIntegrationUploadConcurrencyTests {
    @TempDir Path storage;
    private Database db;
    private SoftwareIntegrationProperties properties;
    private SoftwareIntegrationStorageKeyNormalizer normalizer;
    private SoftwareIntegrationValidationDispatcher dispatcher;
    private SoftwareIntegrationServiceImpl service;
    private long projectId;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database("jdbc:h2:mem:upload-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new SoftwareIntegrationSchemaInitializer(db.jdbc).run(null);
        properties = new SoftwareIntegrationProperties();
        properties.setStorageRoot(storage.toString());
        normalizer = new SoftwareIntegrationStorageKeyNormalizer(properties);
        dispatcher = mock(SoftwareIntegrationValidationDispatcher.class);
        service = service(db, properties, dispatcher, db.versions);
        SoftwareIntegrationProjectEntity project = new SoftwareIntegrationProjectEntity();
        project.setName("upload-test"); project.setCreatedBy("test");
        project.setCreatedAt(LocalDateTime.now()); project.setUpdatedAt(LocalDateTime.now());
        db.projects.insert(project);
        projectId = project.getId();
    }

    @ParameterizedTest
    @ValueSource(strings = {"same.pips", "same.DATA", "same.zip"})
    void concurrentFirstUploadsCreateOneModelAndPreserveBothPayloads(String name) throws Exception {
        var secondService = service(db, properties, dispatcher, db.versions);
        CountDownLatch inCopy = new CountDownLatch(1), release = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
        // ZIP is inspected more than once; stop only on the extract call (third stream opening).
        var firstFile = blockingFile(name, payload(name, "FIRST"), name.endsWith(".zip") ? 3 : 1, inCopy, release);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.uploadModel(projectId, firstFile));
            assertThat(inCopy.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return secondService.uploadModel(projectId, file(name, payload(name, "SECOND")), null);
            });
            try {
                assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(db.models.selectCount(new LambdaQueryWrapper<>())).isEqualTo(1);
        assertPayloads("FIRST", "SECOND");
    }

    @Test
    void separateJvmCannotOverwriteTheVersionAnotherProcessIsAllocating() throws Exception {
        service.uploadModel(projectId, file("same.pips", bytes("BASE")));
        CountDownLatch inCopy = new CountDownLatch(1), release = new CountDownLatch(1);
        Server server = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
        Process child = null;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.uploadModel(projectId,
                    blockingFile("same.pips", bytes("FIRST"), 1, inCopy, release)));
            assertThat(inCopy.await(5, TimeUnit.SECONDS)).isTrue();
            String remoteUrl = "jdbc:h2:tcp://localhost:" + server.getPort() + "/mem:"
                    + db.source.getURL().substring("jdbc:h2:mem:".length());
            Path ready = storage.resolve("child-ready"), done = storage.resolve("child-done"), log = storage.resolve("child.log");
            child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    SoftwareIntegrationUploadConcurrencyTests.class.getName(), remoteUrl, storage.toString(),
                    Long.toString(projectId), ready.toString(), done.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!Files.exists(ready) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
                assertThat(Files.exists(ready)).as("Child ready: %s", Files.readString(log)).isTrue();
                assertThat(child.waitFor(300, TimeUnit.MILLISECONDS)).isFalse();
                assertThat(Files.exists(done)).isFalse();
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
            assertThat(child.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).as("Child output: %s", Files.readString(log)).isZero();
            assertThat(Files.exists(done)).isTrue();
            assertPayloads("BASE", "FIRST", "SECOND");
        } finally {
            release.countDown();
            if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
            server.stop();
        }
    }

    /** Child process entry point: independent MyBatis, JDBC connections and transaction manager. */
    public static void main(String[] args) throws Exception {
        Database child = new Database(args[0]);
        var properties = new SoftwareIntegrationProperties(); properties.setStorageRoot(args[1]);
        var versions = child.versions;
        if (args.length > 5 && args[5].equals("crash")) {
            versions = spy(versions);
            // Terminate after file persistence/hash calculation, before the version INSERT.
            doAnswer(call -> { Runtime.getRuntime().halt(23); return null; })
                    .when(versions).insert(any(SoftwareIntegrationModelVersionEntity.class));
        }
        var service = service(child, properties, mock(SoftwareIntegrationValidationDispatcher.class), versions);
        Files.writeString(Path.of(args[3]), "ready");
        service.uploadModel(Long.parseLong(args[2]), file("same.pips", bytes("SECOND")));
        Files.writeString(Path.of(args[4]), "committed");
    }

    @Test
    void processCrashLeavesAnOrphanThatLaterUploadsRefuseToOverwrite() throws Exception {
        service.uploadModel(projectId, file("same.pips", bytes("BASE")));
        long modelId = versions().getFirst().getModelId();
        Server server = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
        Process child = null;
        Path log = storage.resolve("crash.log");
        try {
            String remoteUrl = "jdbc:h2:tcp://localhost:" + server.getPort() + "/mem:"
                    + db.source.getURL().substring("jdbc:h2:mem:".length());
            child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    SoftwareIntegrationUploadConcurrencyTests.class.getName(), remoteUrl, storage.toString(),
                    Long.toString(projectId), storage.resolve("crash-ready").toString(), storage.resolve("crash-done").toString(), "crash")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertThat(child.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).as("Child output: %s", Files.readString(log)).isEqualTo(23);
            Path orphan = normalizer.resolve("models/" + modelId + "/2/same.pips");
            assertThat(Files.readString(orphan)).isEqualTo("SECOND");
            assertPayloads("BASE");
            assertThatThrownBy(() -> service.uploadModel(projectId, file("same.pips", bytes("REPLACEMENT"))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(409));
            assertThat(Files.readString(orphan)).isEqualTo("SECOND");
            assertPayloads("BASE");
        } finally {
            if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
            server.stop();
        }
    }

    @Test
    void projectLockLivesUntilOuterCommitAndValidationSeesCommittedRows() throws Exception {
        AtomicLong enqueued = new AtomicLong();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            long id = call.getArgument(0);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM software_integration_model_version WHERE id = ?", Integer.class, id)).isEqualTo(1);
            // Queue writes in afterCommit must actually persist (no reuse of the completed connection).
            new SoftwareIntegrationValidationJobStore(db.jdbc).enqueue(id);
            enqueued.incrementAndGet(); return null;
        }).when(dispatcher).enqueue(anyLong());
        CountDownLatch returned = new CountDownLatch(1), commit = new CountDownLatch(1), started = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> db.transaction().executeWithoutResult(status -> {
                service.uploadModel(projectId, file("same.pips", bytes("FIRST")));
                returned.countDown(); await(commit);
            }));
            assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(enqueued.get()).isZero();
            var second = executor.submit(() -> {
                started.countDown(); return service(db, properties, dispatcher, db.versions)
                        .uploadModel(projectId, file("same.pips", bytes("SECOND")));
            });
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { commit.countDown(); }
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        } finally { commit.countDown(); }
        assertPayloads("FIRST", "SECOND");
        assertThat(enqueued.get()).isEqualTo(2);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM software_integration_validation_job", Integer.class)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"same.pips", "same.zip"})
    void existingOrphanDirectoryIsRejectedWithoutOverwritingOrDeletingAnything(String name) throws Exception {
        service.uploadModel(projectId, file(name, payload(name, "BASE")));
        long modelId = versions().getFirst().getModelId();
        Path orphan = normalizer.resolve("models/" + modelId + "/2");
        Files.createDirectory(orphan);
        Path original = orphan.resolve(name.endsWith(".zip") ? "main.pips" : name);
        Files.writeString(original, "ORPHAN");
        Files.writeString(orphan.resolve("keep.inc"), "KEEP");
        Throwable rejection = catchThrowable(() -> service.uploadModel(projectId, file(name, payload(name, "REPLACEMENT"))));
        assertAll(
                () -> assertThat(rejection).isInstanceOf(BusinessException.class)
                        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(409)),
                () -> { assertThat(original).exists(); assertThat(Files.readString(original)).isEqualTo("ORPHAN"); },
                () -> { assertThat(orphan.resolve("keep.inc")).exists(); assertThat(Files.readString(orphan.resolve("keep.inc"))).isEqualTo("KEEP"); },
                () -> assertPayloads("BASE"),
                () -> verify(dispatcher, org.mockito.Mockito.times(1)).enqueue(anyLong()));
    }

    @Test
    void versionGapsUseMaximumInsteadOfCountAndPreserveCommittedFiles() throws Exception {
        service.uploadModel(projectId, file("same.pips", bytes("BASE")));
        var version = versions().getFirst();
        Path firstRoot = normalizer.resolve("models/" + version.getModelId() + "/1");
        Path thirdRoot = normalizer.resolve("models/" + version.getModelId() + "/3");
        Files.move(firstRoot, thirdRoot);
        version.setVersionNo(3); version.setStorageKey("models/" + version.getModelId() + "/3/same.pips");
        db.versions.updateById(version);
        service.uploadModel(projectId, file("same.pips", bytes("NEW")));
        assertThat(versions()).extracting(SoftwareIntegrationModelVersionEntity::getVersionNo).containsExactly(3, 4);
        assertThat(Files.readString(thirdRoot.resolve("same.pips"))).isEqualTo("BASE");
        assertThat(Files.readString(normalizer.resolve(versions().getLast().getStorageKey()))).isEqualTo("NEW");
    }

    @Test
    void insertFailureRollsBackNewModelAndOwnedDirectory() throws Exception {
        var failingVersions = spy(db.versions);
        doThrow(new IllegalStateException("insert failed")).when(failingVersions).insert(any(SoftwareIntegrationModelVersionEntity.class));
        var failing = service(db, properties, dispatcher, failingVersions);
        assertThatThrownBy(() -> failing.uploadModel(projectId, file("same.pips", bytes("FAILED"))))
                .isInstanceOf(IllegalStateException.class).hasMessage("insert failed");
        assertThat(db.models.selectCount(new LambdaQueryWrapper<>())).isZero();
        assertThat(versions()).isEmpty();
        assertNoModelFiles();
        verify(dispatcher, never()).enqueue(anyLong());
        service.uploadModel(projectId, file("same.pips", bytes("RETRY")));
        assertPayloads("RETRY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"same.pips", "same.zip"})
    void outerRollbackRemovesOnlyNewVersionAndNeverDispatchesIt(String name) throws Exception {
        service.uploadModel(projectId, file(name, payload(name, "BASE")));
        long modelId = versions().getFirst().getModelId();
        db.transaction().executeWithoutResult(status -> {
            service.uploadModel(projectId, file(name, payload(name, "ROLLED_BACK")), null);
            verify(dispatcher, org.mockito.Mockito.times(1)).enqueue(anyLong());
            status.setRollbackOnly();
        });
        assertThat(Files.exists(normalizer.resolve("models/" + modelId + "/2"))).isFalse();
        assertPayloads("BASE");
        service.uploadModel(projectId, file(name, payload(name, "RETRY")));
        assertPayloads("BASE", "RETRY");
    }

    @Test
    void partialCopyFailureRemovesOwnedDirectoryAndKeepsExistingVersion() throws Exception {
        service.uploadModel(projectId, file("same.pips", bytes("BASE")));
        var broken = new MockMultipartFile("file", "same.pips", "application/octet-stream", bytes("broken")) {
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    int reads;
                    @Override public int read() throws IOException {
                        if (++reads > 2) throw new IOException("broken stream");
                        return 'x';
                    }
                };
            }
        };
        assertThatThrownBy(() -> service.uploadModel(projectId, broken)).isInstanceOf(BusinessException.class);
        assertPayloads("BASE");
        assertThat(Files.exists(normalizer.resolve("models/" + versions().getFirst().getModelId() + "/2"))).isFalse();
        service.uploadModel(projectId, file("same.pips", bytes("RETRY")));
        assertPayloads("BASE", "RETRY");
    }

    @Test
    void extractorFailureCleansOnlyTheNewlyClaimedDirectory() throws Exception {
        service.uploadModel(projectId, file("same.zip", payload("same.zip", "BASE")));
        properties.setMaxArchiveEntryBytes(2);
        assertThatThrownBy(() -> service.uploadModel(projectId, file("same.zip", payload("same.zip", "TOO_LARGE"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ZIP_ENTRY_TOO_LARGE");
        assertPayloads("BASE");
        assertThat(Files.exists(normalizer.resolve("models/" + versions().getFirst().getModelId() + "/2"))).isFalse();
    }

    @Test
    void validationFailureAfterCommitDoesNotDeleteCommittedVersion() throws Exception {
        doThrow(new IllegalStateException("queue unavailable")).when(dispatcher).enqueue(anyLong());
        assertThatThrownBy(() -> service.uploadModel(projectId, file("same.pips", bytes("COMMITTED"))))
                .isInstanceOf(IllegalStateException.class).hasMessage("queue unavailable");
        assertPayloads("COMMITTED");
    }

    @Test
    void templateWorkflowBindsRealUploadOnceAndEnqueuesOnlyAfterCommit() throws Exception {
        var mapper = new ObjectMapper();
        var creationStore = new TemplateCreationJobStore(db.jdbc, mapper);
        creationStore.run(null);
        UUID creationId = UUID.randomUUID();
        byte[] content = bytes("CREATED");
        var record = templateSuccess(mapper, creationId, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)), content.length);
        creationStore.claim(projectId, creationId, "Well", mapper.createObjectNode()); creationStore.recover(creationId, record);
        var worker = mock(WorkerTemplateCreationClient.class);
        when(worker.download(creationId, record)).thenReturn(content);
        var workflow = new TemplateCreationWorkflow(creationStore, worker, service);
        doAnswer(invocation -> {
            long versionId = invocation.getArgument(0);
            assertThat(db.versions.selectById(versionId)).isNotNull();
            assertThat(creationStore.find(creationId).versionId()).isEqualTo(versionId);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return true;
        }).when(dispatcher).enqueue(anyLong());
        long version = workflow.register(projectId, creationId);
        assertThat(workflow.register(projectId, creationId)).isEqualTo(version);
        assertPayloads("CREATED"); verify(dispatcher).enqueue(version);
        verify(worker).download(creationId, record);
    }

    @Test
    void templateRegistrationMismatchRollsBackRealUploadAndOwnedFiles() {
        var mapper = new ObjectMapper();
        var creationStore = new TemplateCreationJobStore(db.jdbc, mapper);
        creationStore.run(null);
        UUID creationId = UUID.randomUUID();
        var record = templateSuccess(mapper, creationId, "0".repeat(64), 5);
        creationStore.claim(projectId, creationId, "Well", mapper.createObjectNode()); creationStore.recover(creationId, record);
        assertThatThrownBy(() -> creationStore.register(creationId, projectId, job -> {
            var uploaded = service.uploadModel(projectId, file("created.pips", bytes("WRONG")));
            return uploaded.models().getFirst().versions().getFirst().id();
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("provenance mismatch");
        assertThat(versions()).isEmpty(); assertThat(creationStore.find(creationId).versionId()).isNull();
        verify(dispatcher, never()).enqueue(anyLong());
        try { assertNoModelFiles(); } catch (IOException exception) { throw new AssertionError(exception); }
    }

    private tools.jackson.databind.node.ObjectNode templateSuccess(ObjectMapper mapper, UUID id, String hash, int size) {
        var record = mapper.createObjectNode().put("requestId", id.toString()).put("status", "SUCCEEDED");
        record.putObject("model").put("sha256", hash).put("sizeBytes", size);
        var result = record.putObject("result");
        result.put("schemaVersion", "pipesim-template-profile-preflight/1").put("template", "Simple vertical")
                .put("well", "Well").put("nativeState", "Completed")
                .put("geometryOrigin", "official-template-inherited")
                .put("fluidOrigin", "explicit-scalar-inputs-with-installed-SDK-default-correlations")
                .put("calculationVerified", true).put("platformVerified", false);
        result.putObject("inputs"); result.putArray("modelDiagnostics");
        result.putArray("profile").addObject().put("depth", 0).put("pressure", 250).put("temperature", 100);
        return record;
    }

    private List<SoftwareIntegrationModelVersionEntity> versions() {
        return db.versions.selectList(new LambdaQueryWrapper<SoftwareIntegrationModelVersionEntity>()
                .orderByAsc(SoftwareIntegrationModelVersionEntity::getVersionNo));
    }

    private void assertPayloads(String... expected) throws Exception {
        var versions = versions();
        assertThat(versions).hasSize(expected.length);
        assertThat(versions).extracting(SoftwareIntegrationModelVersionEntity::getStorageKey).doesNotHaveDuplicates();
        for (int i = 0; i < expected.length; i++) {
            var version = versions.get(i);
            byte[] actual = Files.readAllBytes(normalizer.resolve(version.getStorageKey()));
            assertThat(new String(actual, StandardCharsets.UTF_8)).isEqualTo(expected[i]);
            assertThat(version.getVersionNo()).isEqualTo(i + 1);
            assertThat(version.getSizeBytes()).isEqualTo(actual.length);
            assertThat(version.getSha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)));
        }
    }

    private void assertNoModelFiles() throws IOException {
        try (var paths = Files.walk(storage)) {
            assertThat(paths.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    private static SoftwareIntegrationServiceImpl service(Database db, SoftwareIntegrationProperties properties,
            SoftwareIntegrationValidationDispatcher dispatcher, SoftwareIntegrationModelVersionMapper versions) {
        return new SoftwareIntegrationServiceImpl(db.projects, db.models, versions, properties, dispatcher,
                new SoftwareIntegrationStorageKeyNormalizer(properties), db.jdbc);
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    private static MockMultipartFile blockingFile(String name, byte[] content, int opening,
            CountDownLatch entered, CountDownLatch release) {
        return new MockMultipartFile("file", name, "application/octet-stream", content) {
            int streams;
            @Override public InputStream getInputStream() throws IOException {
                if (++streams == opening) { entered.countDown(); await(release); }
                return super.getInputStream();
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(exception);
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static byte[] payload(String name, String value) {
        if (!name.endsWith(".zip")) return bytes(value);
        try {
            var result = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(result)) {
                zip.putNextEntry(new ZipEntry("main.pips")); zip.write(bytes(value)); zip.closeEntry();
                zip.putNextEntry(new ZipEntry("keep.inc")); zip.write(bytes("INCLUDE")); zip.closeEntry();
            }
            return result.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    private static final class Database {
        final JdbcDataSource source = new JdbcDataSource();
        final JdbcTemplate jdbc;
        final SoftwareIntegrationProjectMapper projects;
        final SoftwareIntegrationModelMapper models;
        final SoftwareIntegrationModelVersionMapper versions;
        Database(String url) throws Exception {
            source.setURL(url); source.setUser("sa"); source.setPassword("");
            jdbc = new JdbcTemplate(source);
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(SoftwareIntegrationProjectMapper.class);
            configuration.addMapper(SoftwareIntegrationModelMapper.class);
            configuration.addMapper(SoftwareIntegrationModelVersionMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source); factory.setConfiguration(configuration);
            var session = new SqlSessionTemplate(factory.getObject());
            projects = session.getMapper(SoftwareIntegrationProjectMapper.class);
            models = session.getMapper(SoftwareIntegrationModelMapper.class);
            versions = session.getMapper(SoftwareIntegrationModelVersionMapper.class);
        }
        TransactionTemplate transaction() { return new TransactionTemplate(new DataSourceTransactionManager(source)); }
    }
}
