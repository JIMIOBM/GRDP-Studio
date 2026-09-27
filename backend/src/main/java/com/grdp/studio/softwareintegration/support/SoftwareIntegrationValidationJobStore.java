package com.grdp.studio.softwareintegration.support;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists validation queue state independently from the model-version state.
 * The version row remains the public source of truth; this table supplies
 * durable leases and retry timing for the worker-facing validation operation.
 */
@Component
public class SoftwareIntegrationValidationJobStore {
    private final JdbcTemplate jdbcTemplate;

    public SoftwareIntegrationValidationJobStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Enqueues a new validation or explicitly requested revalidation. */
    public void enqueue(long versionId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextAttempt = now.minusSeconds(1);
        int updated = jdbcTemplate.update("""
                UPDATE software_integration_validation_job
                SET status = 'QUEUED', attempt_count = 0, next_attempt_at = ?,
                    lease_until = NULL, last_error = NULL, updated_at = ?
                WHERE version_id = ? AND status <> 'RUNNING'
                """, nextAttempt, now, versionId);
        if (updated > 0) return;
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM software_integration_validation_job WHERE version_id = ?",
                Integer.class, versionId);
        if (existing != null && existing > 0) return;
        try {
            jdbcTemplate.update("""
                    INSERT INTO software_integration_validation_job
                    (version_id, status, attempt_count, next_attempt_at, created_at, updated_at)
                    VALUES (?, 'QUEUED', 0, ?, ?, ?)
                    """, versionId, nextAttempt, now, now);
        } catch (DuplicateKeyException duplicate) {
            // Another request inserted the same version between the UPDATE and INSERT.
            jdbcTemplate.update("""
                    UPDATE software_integration_validation_job
                    SET status = 'QUEUED', attempt_count = 0, next_attempt_at = ?,
                        lease_until = NULL, last_error = NULL, updated_at = ?
                    WHERE version_id = ? AND status <> 'RUNNING'
                    """, nextAttempt, now, versionId);
        }
    }

    /** Backfills a queue row for versions created before the durable queue existed. */
    public void ensureQueued(long versionId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextAttempt = now.minusSeconds(1);
        try {
            jdbcTemplate.update("""
                    INSERT INTO software_integration_validation_job
                    (version_id, status, attempt_count, next_attempt_at, created_at, updated_at)
                    VALUES (?, 'QUEUED', 0, ?, ?, ?)
                    """, versionId, nextAttempt, now, now);
        } catch (DuplicateKeyException ignored) {
            // Existing state must retain its attempt count and retry timing.
        }
    }

    /** Requeues work whose process lease expired. */
    public int recoverExpiredLeases(LocalDateTime now) {
        return jdbcTemplate.update("""
                UPDATE software_integration_validation_job
                SET status = 'QUEUED', lease_until = NULL, next_attempt_at = ?,
                    last_error = COALESCE(last_error, '验证进程中断，任务已恢复'), updated_at = ?
                WHERE status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until <= ?
                """, now, now, now);
    }

    /** Claims due jobs with a short lease; each claim is guarded by status. */
    public List<Claim> claimDue(int limit, LocalDateTime now, Duration lease) {
        List<Long> ids = jdbcTemplate.query("""
                SELECT id FROM software_integration_validation_job
                WHERE status = 'QUEUED' AND next_attempt_at <= ?
                ORDER BY id ASC LIMIT ?
                """, (resultSet, rowNum) -> resultSet.getLong(1), now, limit);
        List<Claim> claims = new ArrayList<>();
        LocalDateTime leaseUntil = now.plus(lease);
        for (Long id : ids) {
            int updated = jdbcTemplate.update("""
                    UPDATE software_integration_validation_job
                    SET status = 'RUNNING', attempt_count = attempt_count + 1,
                        lease_until = ?, updated_at = ?
                    WHERE id = ? AND status = 'QUEUED' AND next_attempt_at <= ?
                    """, leaseUntil, now, id, now);
            if (updated == 1) {
                Integer attempt = jdbcTemplate.queryForObject(
                        "SELECT attempt_count FROM software_integration_validation_job WHERE id = ?",
                        Integer.class, id);
                Long versionId = jdbcTemplate.queryForObject(
                        "SELECT version_id FROM software_integration_validation_job WHERE id = ?",
                        Long.class, id);
                if (attempt != null && versionId != null) claims.add(new Claim(versionId, attempt));
            }
        }
        return claims;
    }

    public Outcome complete(long versionId, String versionStatus, String message,
                            int maxAttempts, Duration retryBackoff) {
        LocalDateTime now = LocalDateTime.now();
        if ("READY".equals(versionStatus) || "INVALID".equals(versionStatus)) {
            jdbcTemplate.update("""
                    UPDATE software_integration_validation_job
                    SET status = 'SUCCEEDED', lease_until = NULL, next_attempt_at = NULL,
                        last_error = NULL, updated_at = ? WHERE version_id = ?
                    """, now, versionId);
            return Outcome.SUCCEEDED;
        }
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM software_integration_validation_job WHERE version_id = ?",
                Integer.class, versionId);
        String safeMessage = message == null ? "验证失败" : message.substring(0, Math.min(message.length(), 1000));
        if (attempts != null && attempts < maxAttempts) {
            jdbcTemplate.update("""
                    UPDATE software_integration_validation_job
                    SET status = 'QUEUED', lease_until = NULL, next_attempt_at = ?,
                        last_error = ?, updated_at = ? WHERE version_id = ?
                    """, now.plus(retryBackoff), safeMessage, now, versionId);
            return Outcome.RETRY_SCHEDULED;
        } else {
            jdbcTemplate.update("""
                    UPDATE software_integration_validation_job
                    SET status = 'FAILED', lease_until = NULL, next_attempt_at = NULL,
                        last_error = ?, updated_at = ? WHERE version_id = ?
                    """, safeMessage, now, versionId);
            return Outcome.FAILED;
        }
    }

    public enum Outcome { SUCCEEDED, RETRY_SCHEDULED, FAILED }

    public record Claim(long versionId, int attempt) {}
}
