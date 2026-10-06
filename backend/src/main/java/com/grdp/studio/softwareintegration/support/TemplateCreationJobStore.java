package com.grdp.studio.softwareintegration.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/** Durable project binding and exactly-once registration; deliberately not a public dispatch API. */
@Component
public class TemplateCreationJobStore implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public TemplateCreationJobStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Override public void run(ApplicationArguments args) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS software_integration_template_creation (
                  request_id VARCHAR(36) PRIMARY KEY, project_id BIGINT NOT NULL,
                  fingerprint CHAR(64) NOT NULL, request_json TEXT NOT NULL,
                  state VARCHAR(24) NOT NULL, record_json LONGTEXT, version_id BIGINT,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    public record Job(UUID requestId, long projectId, String fingerprint, JsonNode request,
                      String state, JsonNode workerRecord, Long versionId) { }
    public record Claim(Job job, boolean newlyClaimed) { }

    /** Persist before dispatch; same ID never authorizes a second POST, including after restart. */
    public Claim claim(long projectId, UUID id, String well, JsonNode inputs) {
        return claim(projectId, id, well, inputs, Integer.MAX_VALUE);
    }

    public Claim claim(long projectId, UUID id, String well, JsonNode inputs, int maxProjectClaims) {
        if (id == null || id.equals(new UUID(0, 0)) || well == null
                || !well.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || inputs == null || !inputs.isObject()) {
            throw new IllegalArgumentException("Invalid creation request");
        }
        var fields = new TreeMap<String, JsonNode>();
        inputs.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
        var request = mapper.createObjectNode(); request.put("well", well); request.set("inputs", mapper.valueToTree(fields));
        String json = mapper.writeValueAsString(request);
        if (json.getBytes(StandardCharsets.UTF_8).length > 16 * 1024) {
            throw new IllegalArgumentException("Creation request exceeds scalar-input limit");
        }
        String fingerprint = hash(json);
        return transaction.execute(status -> {
            lockProject(projectId);
            Job existing = find(id);
            if (existing != null) {
                if (existing.projectId() != projectId || !existing.fingerprint().equals(fingerprint)) {
                    throw new IllegalStateException("Creation ID already belongs to another project or input");
                }
                return new Claim(existing, false);
            }
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM software_integration_template_creation WHERE project_id=?", Long.class, projectId);
            if (count != null && count >= maxProjectClaims) {
                throw new IllegalStateException("项目已达到建井记录上限，请联系管理员");
            }
            // Global PK prevents the same ID being concurrently bound in two different projects.
            jdbc.update("INSERT INTO software_integration_template_creation (request_id,project_id,fingerprint,request_json,state) VALUES (?,?,?,?,'CLAIMED')",
                    id.toString(), projectId, fingerprint, json);
            return new Claim(find(id), true);
        });
    }

    public Job find(UUID id) {
        List<Job> jobs = jdbc.query("SELECT * FROM software_integration_template_creation WHERE request_id = ?",
                (rs, row) -> new Job(UUID.fromString(rs.getString("request_id")), rs.getLong("project_id"),
                        rs.getString("fingerprint"), mapper.readTree(rs.getString("request_json")), rs.getString("state"),
                        rs.getString("record_json") == null ? null : mapper.readTree(rs.getString("record_json")),
                        rs.getObject("version_id") == null ? null : rs.getLong("version_id")), id.toString());
        return jobs.isEmpty() ? null : jobs.getFirst();
    }

    public void markUncertain(UUID id) {
        jdbc.update("UPDATE software_integration_template_creation SET state='UNCERTAIN', updated_at=CURRENT_TIMESTAMP WHERE request_id=? AND state='CLAIMED'",
                id.toString());
    }

    public void recover(UUID id, JsonNode record) {
        String state = record == null ? "" : record.path("status").asText();
        if (record == null || !id.toString().equalsIgnoreCase(record.path("requestId").asText())
                || !List.of("PREPARING", "SUCCEEDED", "FAILED", "INTERRUPTED", "CANCELLED", "TIMED_OUT").contains(state)) {
            throw new IllegalArgumentException("Invalid recovered creation record");
        }
        transaction.executeWithoutResult(status -> {
            Job known = find(id);
            if (known == null) throw new IllegalStateException("Creation was not claimed by this backend");
            lockProject(known.projectId());
            Job locked = lockJob(id);
            if (locked.versionId() != null || List.of("SUCCEEDED", "FAILED", "INTERRUPTED", "CANCELLED", "TIMED_OUT").contains(locked.state())) {
                if (!locked.state().equals(state) || !locked.workerRecord().equals(record)) {
                    throw new IllegalStateException("Terminal creation record cannot be replaced");
                }
                return;
            }
            jdbc.update("UPDATE software_integration_template_creation SET state=?,record_json=?,updated_at=CURRENT_TIMESTAMP WHERE request_id=?",
                    state, mapper.writeValueAsString(record), id.toString());
        });
    }

    /** Upload callback joins this JDBC transaction; failed binding rolls back the upload as well. */
    public long register(UUID id, long projectId, Function<Job, Long> upload) {
        return transaction.execute(status -> {
            lockProject(projectId);
            Job job = lockJob(id);
            if (job.projectId() != projectId) throw new IllegalStateException("Creation project mismatch");
            if (job.versionId() != null) return job.versionId();
            if (!"SUCCEEDED".equals(job.state())) throw new IllegalStateException("Creation has no successful model");
            String sha = job.workerRecord().path("model").path("sha256").asText("");
            if (!sha.matches("[a-fA-F0-9]{64}")) throw new IllegalStateException("Creation artifact hash missing");
            Long version = upload.apply(job);
            Long matches = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM software_integration_model_version v
                    JOIN software_integration_model m ON m.id=v.model_id
                    WHERE v.id=? AND m.project_id=? AND m.deleted_at IS NULL AND LOWER(v.sha256)=?
                    """, Long.class, version, projectId, sha.toLowerCase(java.util.Locale.ROOT));
            if (matches == null || matches != 1) throw new IllegalStateException("Registered version provenance mismatch");
            jdbc.update("UPDATE software_integration_template_creation SET version_id=?,updated_at=CURRENT_TIMESTAMP WHERE request_id=?",
                    version, id.toString());
            return version;
        });
    }

    private void lockProject(long projectId) {
        Boolean active = jdbc.query("SELECT deleted_at FROM software_integration_project WHERE id=? FOR UPDATE",
                rs -> rs.next() && rs.getTimestamp(1) == null, projectId);
        if (!Boolean.TRUE.equals(active)) throw new IllegalStateException("Active project is required");
    }

    private Job lockJob(UUID id) {
        jdbc.queryForList("SELECT request_id FROM software_integration_template_creation WHERE request_id=? FOR UPDATE", id.toString());
        Job job = find(id);
        if (job == null) throw new IllegalStateException("Creation not found");
        return job;
    }

    private static String hash(String json) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
