package com.grdp.studio.softwareintegration.preprocessing;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationCreateRunRequest;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationRunService;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationService;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class EclipsePreprocessingWorkflow implements ApplicationRunner {
    public record Build(UUID requestId, long projectId, Long modelId, Long versionId, Long runId,
                        String status, String message, String deckSha256, String templateId) { }
    private record Stored(UUID id, long projectId, String fingerprint, Long modelId, Long versionId, Long runId,
                          String state, String message, boolean calculate, String deckSha, byte[] archive, String templateId) { }
    private final EclipsePreprocessingCatalog catalog;
    private final EclipsePreprocessingValidator validator;
    private final EclipsePreprocessingSources sources;
    private final SoftwareIntegrationService projects;
    private final SoftwareIntegrationRunService runs;
    private final SoftwareIntegrationProperties properties;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public EclipsePreprocessingWorkflow(EclipsePreprocessingCatalog catalog, EclipsePreprocessingValidator validator,
            EclipsePreprocessingSources sources, SoftwareIntegrationService projects, SoftwareIntegrationRunService runs,
            SoftwareIntegrationProperties properties, JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.catalog = catalog; this.validator = validator; this.sources = sources; this.projects = projects; this.runs = runs;
        this.properties = properties; this.jdbc = jdbc; this.mapper = mapper; transactions = new TransactionTemplate(manager);
    }

    @Override public void run(ApplicationArguments arguments) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS software_integration_eclipse_preprocessing (
                  request_id VARCHAR(36) PRIMARY KEY, project_id BIGINT NOT NULL, fingerprint CHAR(64) NOT NULL,
                  source_id VARCHAR(36) NOT NULL, request_json LONGTEXT NOT NULL, archive_bytes LONGBLOB NOT NULL,
                  deck_sha256 CHAR(64) NOT NULL, model_id BIGINT, version_id BIGINT, run_id BIGINT,
                  state VARCHAR(32) NOT NULL, message VARCHAR(500), calculate BOOLEAN NOT NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    public Build build(long projectId, EclipsePreprocessingInput input) {
        projects.getProject(projectId);
        var validation = validator.validate(input);
        if (!validation.valid()) throw new BusinessException(400, validation.issues().getFirst().message());
        if (input.requestId() == null || input.requestId().equals(new UUID(0, 0))) throw new BusinessException(400, "生成请求缺少有效编号");
        var source = sources.require(input.sourceId(), projectId, input.templateId(), input.templateVersion());
        var template = catalog.require(input.templateId());
        byte[] deck = EclipsePreprocessingDeck.render(template, validation.normalized(), true);
        var fingerprintData = new TreeMap<String, Object>();
        fingerprintData.put("sourceId", input.sourceId()); fingerprintData.put("templateId", input.templateId());
        fingerprintData.put("version", input.templateVersion()); fingerprintData.put("modelName", input.modelName());
        fingerprintData.put("calculate", input.calculate()); fingerprintData.put("values", new TreeMap<>(validation.normalized()));
        fingerprintData.put("deckSha256", EclipsePreprocessingCatalog.sha256(deck));
        String fingerprint = EclipsePreprocessingCatalog.sha256(mapper.writeValueAsBytes(fingerprintData));
        return transactions.execute(transaction -> {
            lockProject(projectId);
            var existing = find(input.requestId(), true);
            if (existing != null) {
                requireProject(existing, projectId);
                if (!existing.fingerprint().equals(fingerprint)) throw new BusinessException(409, "同一生成请求编号不能用于不同数据，请创建新方案");
                return view(existing);
            }
            byte[] archive = archive(input, source, validation.normalized(), deck);
            String deckSha = EclipsePreprocessingCatalog.sha256(deck);
            jdbc.update("""
                    INSERT INTO software_integration_eclipse_preprocessing
                    (request_id,project_id,fingerprint,source_id,request_json,archive_bytes,deck_sha256,state,calculate)
                    VALUES (?,?,?,?,?,?,?,'REGISTERING',?)
                    """, input.requestId().toString(), projectId, fingerprint, source.id().toString(),
                    mapper.writeValueAsString(input), archive, deckSha, input.calculate());
            String filename = input.modelName() + "_PP.zip";
            // Only solver inputs belong in the executable package. The full source and
            // provenance archive remains immutable in this build's download record.
            byte[] executable = executableArchive(template.path("sourceName").asText(), deck);
            var detail = projects.uploadModel(projectId, new GeneratedFile(filename, executable), template.path("sourceName").asText());
            var model = detail.models().stream().filter(value -> (input.modelName() + "_PP").equals(value.name())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Registered preprocessing model missing"));
            var version = model.versions().stream().max(java.util.Comparator.comparingInt(value -> value.versionNo())).orElseThrow();
            jdbc.update("UPDATE software_integration_eclipse_preprocessing SET model_id=?,version_id=?,state='VALIDATING' WHERE request_id=?",
                    model.id(), version.id(), input.requestId().toString());
            return new Build(input.requestId(), projectId, model.id(), version.id(), null, "VALIDATING", "模型已生成，正在通过现有Worker校验", deckSha, input.templateId());
        });
    }

    public Build get(long projectId, UUID id) {
        projects.getProject(projectId);
        var stored = find(id, false); requireProject(stored, projectId);
        return view(stored);
    }

    public List<Build> list(long projectId) {
        projects.getProject(projectId);
        return jdbc.query("SELECT request_id FROM software_integration_eclipse_preprocessing WHERE project_id=? ORDER BY created_at DESC LIMIT 30",
                (rs, row) -> UUID.fromString(rs.getString(1)), projectId).stream().map(id -> get(projectId, id)).toList();
    }

    public byte[] download(long projectId, UUID id) {
        projects.getProject(projectId);
        var stored = find(id, false); requireProject(stored, projectId);
        return stored.archive();
    }

    public EclipsePreprocessingInput inputs(long projectId, UUID id) {
        projects.getProject(projectId);
        requireProject(find(id, false), projectId);
        String json = jdbc.queryForObject("SELECT request_json FROM software_integration_eclipse_preprocessing WHERE request_id=?", String.class, id.toString());
        return mapper.readValue(json, EclipsePreprocessingInput.class);
    }

    public Build calculate(long projectId, UUID id) {
        return transactions.execute(transaction -> {
            lockProject(projectId);
            var stored = find(id, true); requireProject(stored, projectId);
            if (stored.runId() != null) return view(stored);
            var detail = projects.getProject(projectId);
            var version = detail.models().stream().flatMap(model -> model.versions().stream())
                    .filter(value -> value.id().equals(stored.versionId())).findFirst().orElseThrow(() -> new BusinessException(404, "生成模型版本不存在"));
            if (!"READY".equals(version.status())) throw new BusinessException(409, "生成模型尚未通过校验：" + version.status());
            var request = new SoftwareIntegrationCreateRunRequest(); request.setStudy(null); request.setRunType("eclipse"); request.setParameters(null);
            var run = runs.create(version.id(), request);
            jdbc.update("UPDATE software_integration_eclipse_preprocessing SET run_id=?,state='QUEUED',message=NULL WHERE request_id=?", run.id(), id.toString());
            return new Build(id, projectId, stored.modelId(), stored.versionId(), run.id(), "QUEUED", "真实计算已进入现有队列", stored.deckSha(), stored.templateId());
        });
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void continueValidatedBuilds() {
        if (!properties.isDispatcherEnabled()) return;
        var waiting = jdbc.query("SELECT request_id,project_id FROM software_integration_eclipse_preprocessing WHERE version_id IS NOT NULL AND run_id IS NULL AND calculate=TRUE AND state='VALIDATING' ORDER BY created_at LIMIT 5",
                (rs, row) -> Map.entry(UUID.fromString(rs.getString(1)), rs.getLong(2)));
        for (var entry : waiting) {
            try {
                var current = get(entry.getValue(), entry.getKey());
                if (List.of("INVALID", "UNAVAILABLE").contains(current.status())) {
                    jdbc.update("UPDATE software_integration_eclipse_preprocessing SET state=?,calculate=FALSE WHERE request_id=? AND run_id IS NULL", current.status(), entry.getKey().toString());
                    continue;
                }
                calculate(entry.getValue(), entry.getKey());
            }
            catch (RuntimeException exception) {
                if (exception instanceof BusinessException business && business.getCode() == 404) {
                    jdbc.update("UPDATE software_integration_eclipse_preprocessing SET state='CANCELLED',calculate=FALSE,message=? WHERE request_id=? AND run_id IS NULL", "项目或生成版本已不可用，未再提交计算", entry.getKey().toString());
                    continue;
                }
                String message = exception.getMessage() == null ? "模型校验或原生计算能力暂不可用" : exception.getMessage();
                if (message.length() > 450) message = message.substring(0, 450);
                jdbc.update("UPDATE software_integration_eclipse_preprocessing SET message=? WHERE request_id=? AND run_id IS NULL", message, entry.getKey().toString());
            }
        }
    }

    private Build view(Stored stored) {
        String status = stored.state(), message = stored.message();
        if (stored.versionId() != null) {
            var version = jdbc.query("""
                    SELECT v.status,v.validation_message FROM software_integration_model_version v
                    JOIN software_integration_model m ON m.id=v.model_id
                    WHERE v.id=? AND m.project_id=? AND m.deleted_at IS NULL
                    """, (rs, row) -> Map.entry(rs.getString(1), rs.getString(2) == null ? "" : rs.getString(2)), stored.versionId(), stored.projectId());
            if (version.isEmpty()) return unavailable(stored, "生成模型已删除或版本已不可用；仍可下载来源档案或基于输入新建方案");
            if (stored.runId() == null) {
                status = version.getFirst().getKey();
                if (!version.getFirst().getValue().isBlank()) message = version.getFirst().getValue();
            }
        }
        if (stored.runId() != null) {
            try { status = runs.get(stored.runId()).status(); }
            catch (com.grdp.studio.softwareintegration.support.SoftwareIntegrationRunExceptionHandler.RunException exception) {
                if (exception.status() != org.springframework.http.HttpStatus.NOT_FOUND) throw exception;
                return unavailable(stored, "计算记录已不可用；仍可下载来源档案或基于输入新建方案");
            }
        }
        return new Build(stored.id(), stored.projectId(), stored.modelId(), stored.versionId(), stored.runId(), status, message, stored.deckSha(), stored.templateId());
    }

    private Build unavailable(Stored stored, String message) {
        return new Build(stored.id(), stored.projectId(), stored.modelId(), stored.versionId(), stored.runId(),
                "UNAVAILABLE", message, stored.deckSha(), stored.templateId());
    }

    private Stored find(UUID id, boolean lock) {
        var list = jdbc.query("SELECT * FROM software_integration_eclipse_preprocessing WHERE request_id=?" + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Stored(
                UUID.fromString(rs.getString("request_id")), rs.getLong("project_id"), rs.getString("fingerprint"),
                nullableLong(rs, "model_id"), nullableLong(rs, "version_id"), nullableLong(rs, "run_id"), rs.getString("state"), rs.getString("message"),
                rs.getBoolean("calculate"), rs.getString("deck_sha256"), rs.getBytes("archive_bytes"), mapper.readTree(rs.getString("request_json")).path("templateId").asText()), id.toString());
        return list.isEmpty() ? null : list.getFirst();
    }

    private static Long nullableLong(java.sql.ResultSet result, String name) throws java.sql.SQLException {
        long value = result.getLong(name); return result.wasNull() ? null : value;
    }

    private void lockProject(long projectId) {
        Boolean active = jdbc.query("SELECT deleted_at FROM software_integration_project WHERE id=? FOR UPDATE", result -> result.next() && result.getTimestamp(1) == null, projectId);
        if (!Boolean.TRUE.equals(active)) throw new BusinessException(404, "软件集成项目不存在");
    }

    private void requireProject(Stored stored, long projectId) {
        if (stored == null || stored.projectId() != projectId) throw new BusinessException(404, "当前项目没有此生成记录");
    }

    private byte[] archive(EclipsePreprocessingInput input, EclipsePreprocessingSources.Source source, Map<String, String> values, byte[] deck) {
        var template = catalog.require(input.templateId());
        var provenance = new TreeMap<String, Object>();
        provenance.put("schemaVersion", "eclipse-preprocessing-provenance/1"); provenance.put("templateId", input.templateId());
        provenance.put("templateVersion", input.templateVersion()); provenance.put("requestId", input.requestId()); provenance.put("source", source);
        provenance.put("officialSource", template.path("sourceLocation").asText()); provenance.put("officialSourceSha256", template.path("sourceSha256").asText());
        provenance.put("classification", "官方算例转换教学数据"); provenance.put("scope", template.path("scope").asText());
        provenance.put("confirmedDefaults", true); provenance.put("normalizedInputsSha256", EclipsePreprocessingCatalog.sha256(mapper.writeValueAsBytes(new TreeMap<>(values))));
        provenance.put("deckSha256", EclipsePreprocessingCatalog.sha256(deck)); provenance.put("createdAt", Instant.now().toString());
        provenance.put("outputOnlyChanges", template.path("outputPolicy").asText());
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes)) {
            entry(zip, template.path("sourceName").asText(), deck);
            entry(zip, "preprocessing-provenance.json", mapper.writeValueAsBytes(provenance));
            entry(zip, "preprocessing-inputs.json", mapper.writeValueAsBytes(input));
            for (var file : source.files()) entry(zip, "sources/" + file.storedName(), sources.read(source, file));
            zip.finish(); return bytes.toByteArray();
        } catch (IOException exception) { throw new BusinessException(500, "生成模型包失败"); }
    }

    private static byte[] executableArchive(String sourceName, byte[] deck) {
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes)) {
            entry(zip, sourceName, deck);
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException exception) { throw new BusinessException(500, "生成计算工程失败"); }
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        var entry = new ZipEntry(name); entry.setTime(0); zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }

    private record GeneratedFile(String filename, byte[] bytes) implements MultipartFile {
        public String getName() { return "file"; } public String getOriginalFilename() { return filename; }
        public String getContentType() { return "application/zip"; } public boolean isEmpty() { return bytes.length == 0; }
        public long getSize() { return bytes.length; } public byte[] getBytes() { return bytes.clone(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        public void transferTo(File target) throws IOException { Files.write(target.toPath(), bytes); }
    }
}
