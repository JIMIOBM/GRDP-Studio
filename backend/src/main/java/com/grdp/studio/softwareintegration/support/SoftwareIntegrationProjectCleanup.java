package com.grdp.studio.softwareintegration.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Permanently removes projects that have remained in the software-integration recycle bin for the retention period. */
@Component
public class SoftwareIntegrationProjectCleanup {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoftwareIntegrationProjectCleanup.class);
    private static final List<String> ACTIVE_STATUSES = List.of(
            "CLAIMED", "PREPARING", "RUNNING_NODAL", "RUNNING_PROFILE", "RUNNING_NETWORK",
            "RUNNING_ECLIPSE", "COLLECTING", "CANCEL_REQUESTED");

    private final JdbcTemplate jdbcTemplate;
    private final SoftwareIntegrationProperties properties;
    private final SoftwareIntegrationStorageKeyNormalizer normalizer;
    private final TransactionTemplate transactions;

    public SoftwareIntegrationProjectCleanup(JdbcTemplate jdbcTemplate,
                                             SoftwareIntegrationProperties properties,
                                             SoftwareIntegrationStorageKeyNormalizer normalizer,
                                             PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.normalizer = normalizer;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(
            fixedDelayString = "${grdp.software-integration.project-cleanup-delay:PT1H}",
            initialDelayString = "${grdp.software-integration.project-cleanup-initial-delay:PT1M}")
    public void cleanupExpiredProjects() {
        cleanupExpiredProjectCount();
    }

    /** Exposed for deterministic regression coverage; the scheduled entry point intentionally ignores the count. */
    public int cleanupExpiredProjectCount() {
        if (properties.getProjectRetentionDays() < 1) return 0;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getProjectRetentionDays());
        List<Long> projectIds = jdbcTemplate.queryForList(
                "SELECT id FROM software_integration_project WHERE deleted_at IS NOT NULL AND deleted_at <= ? ORDER BY id",
                Long.class, cutoff);
        int cleaned = 0;
        for (Long projectId : projectIds) {
            try {
                if (Boolean.TRUE.equals(cleanupOne(projectId, cutoff))) cleaned++;
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to clean expired software-integration project {}", projectId, exception);
            }
        }
        return cleaned;
    }

    private Boolean cleanupOne(long projectId, LocalDateTime cutoff) {
        return transactions.execute(status -> {
            List<Map<String, Object>> locked = jdbcTemplate.queryForList(
                    "SELECT id FROM software_integration_project WHERE id = ? AND deleted_at IS NOT NULL AND deleted_at <= ? FOR UPDATE",
                    projectId, cutoff);
            if (locked.isEmpty()) return false;
            Integer active = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM software_integration_run WHERE project_id = ? AND status IN (" + placeholders(ACTIVE_STATUSES.size()) + ")",
                    Integer.class, parameters(projectId, ACTIVE_STATUSES));
            if (active != null && active > 0) return false;

            List<ModelVersionPath> versions = jdbcTemplate.query("""
                    SELECT v.model_id, v.version_no, v.storage_key
                    FROM software_integration_model_version v
                    JOIN software_integration_model m ON m.id = v.model_id
                    WHERE m.project_id = ?
                    """, (resultSet, rowNum) -> new ModelVersionPath(
                    resultSet.getLong("model_id"), resultSet.getInt("version_no"), resultSet.getString("storage_key")), projectId);
            for (ModelVersionPath version : versions) {
                if (!deleteVersionDirectory(version)) return false;
            }

            List<Long> runIds = jdbcTemplate.queryForList(
                    "SELECT id FROM software_integration_run WHERE project_id = ?", Long.class, projectId);
            for (Long runId : runIds) {
                if (!deleteArtifactDirectory(runId)) return false;
            }

            jdbcTemplate.update("DELETE FROM software_integration_artifact WHERE run_id IN (SELECT id FROM software_integration_run WHERE project_id = ?)", projectId);
            jdbcTemplate.update("DELETE FROM software_integration_run_event WHERE run_id IN (SELECT id FROM software_integration_run WHERE project_id = ?)", projectId);
            jdbcTemplate.update("DELETE FROM software_integration_run WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM software_integration_validation_job WHERE version_id IN (SELECT v.id FROM software_integration_model_version v JOIN software_integration_model m ON m.id = v.model_id WHERE m.project_id = ?)", projectId);
            jdbcTemplate.update("DELETE FROM software_integration_model_version WHERE model_id IN (SELECT id FROM software_integration_model WHERE project_id = ?)", projectId);
            jdbcTemplate.update("DELETE FROM software_integration_model WHERE project_id = ?", projectId);
            return jdbcTemplate.update("DELETE FROM software_integration_project WHERE id = ? AND deleted_at IS NOT NULL", projectId) == 1;
        });
    }

    private boolean deleteVersionDirectory(ModelVersionPath version) {
        final String storageKey;
        try {
            storageKey = normalizer.normalizeStoredKey(version.storageKey());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        String prefix = "models/" + version.modelId() + "/" + version.versionNo() + "/";
        if (!storageKey.startsWith(prefix)) return false;
        return deleteTree(normalizer.resolve("models/" + version.modelId() + "/" + version.versionNo()));
    }

    private boolean deleteArtifactDirectory(long runId) {
        return deleteTree(normalizer.resolve("artifacts/" + runId));
    }

    private boolean deleteTree(Path root) {
        try {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true;
            if (Files.isSymbolicLink(root)) return false;
            Path realRoot = normalizer.root().toRealPath();
            Path realPath = root.toRealPath();
            if (!realPath.startsWith(realRoot) || realPath.equals(realRoot)) return false;
            try (var paths = Files.walk(realPath)) {
                List<Path> all = paths.toList();
                if (all.stream().anyMatch(path -> Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
                    return false;
                }
                all.stream().sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException exception) { throw new CleanupFailure(exception); }
                });
            }
            return !Files.exists(realPath, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | SecurityException | CleanupFailure exception) {
            return false;
        }
    }

    private static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }

    private static Object[] parameters(long projectId, List<String> statuses) {
        Object[] values = new Object[statuses.size() + 1];
        values[0] = projectId;
        for (int index = 0; index < statuses.size(); index++) values[index + 1] = statuses.get(index);
        return values;
    }

    private record ModelVersionPath(long modelId, int versionNo, String storageKey) {}
    private static final class CleanupFailure extends RuntimeException {
        private CleanupFailure(IOException cause) { super(cause); }
    }
}
