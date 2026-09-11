package com.grdp.studio.softwareintegration.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Creates only software-integration tables; existing business schemas are untouched. */
@Component
public class SoftwareIntegrationSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    public SoftwareIntegrationSchemaInitializer(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }
    @Override public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS software_integration_project (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, description VARCHAR(500), created_by VARCHAR(100) NOT NULL,
                  created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, deleted_at DATETIME(3),
                  UNIQUE KEY uk_software_integration_project_name (name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS software_integration_model (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL, name VARCHAR(255) NOT NULL, simulator_type VARCHAR(50) NOT NULL,
                  created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, deleted_at DATETIME(3),
                  UNIQUE KEY uk_software_integration_model_name (project_id, name), KEY idx_software_integration_model_project (project_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS software_integration_model_version (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, model_id BIGINT NOT NULL, version_no INT NOT NULL, original_name VARCHAR(255) NOT NULL,
                  storage_key VARCHAR(1024) NOT NULL, sha256 CHAR(64) NOT NULL, size_bytes BIGINT NOT NULL, status VARCHAR(50) NOT NULL,
                  model_kind VARCHAR(50), validation_message VARCHAR(1000), studies_json TEXT, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
                  UNIQUE KEY uk_software_integration_model_version (model_id, version_no), KEY idx_software_integration_model_version_model (model_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        ensureModelVersionKindColumn();
        backfillModelVersionKinds();
        String database = jdbcTemplate.execute((ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductName());
        boolean h2 = database != null && database.toLowerCase().contains("h2");
        String jsonType = h2 ? "CLOB" : "JSON";
        String resultType = h2 ? "CLOB" : "LONGTEXT";
        String generated = h2
                ? "GENERATED ALWAYS AS (CASE WHEN " + h2ActivePredicate() + " THEN 1 ELSE NULL END)"
                : "GENERATED ALWAYS AS (CASE WHEN status IN ('CLAIMED','PREPARING','RUNNING_NODAL','RUNNING_PROFILE','RUNNING_NETWORK','RUNNING_ECLIPSE','COLLECTING','CANCEL_REQUESTED') THEN 1 ELSE NULL END) STORED";
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS software_integration_run (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL, model_id BIGINT NOT NULL, model_version_id BIGINT NOT NULL,
                  study_name VARCHAR(255), run_type VARCHAR(20) NOT NULL, parameters_json %s NOT NULL, status VARCHAR(40) NOT NULL,
                  status_version INT NOT NULL DEFAULT 0, timeout_seconds INT NOT NULL, dispatcher_id VARCHAR(64), worker_id VARCHAR(128),
                  generation_id VARCHAR(128), acceptance_uncertain_at DATETIME(3), acceptance_recovery_deadline_at DATETIME(3),
                  last_worker_sequence BIGINT NOT NULL DEFAULT 0, cancellation_reason VARCHAR(20),
                  created_at DATETIME(3) NOT NULL, queued_at DATETIME(3), claimed_at DATETIME(3), started_at DATETIME(3), deadline_at DATETIME(3),
                  finished_at DATETIME(3), elapsed_millis BIGINT, result_contract VARCHAR(64), result_json %s, error_category VARCHAR(64), error_code VARCHAR(100),
                  error_json %s, cleanup_json %s, artifact_manifest_key VARCHAR(1024), created_by VARCHAR(100) NOT NULL,
                  updated_by VARCHAR(100) NOT NULL, updated_at DATETIME(3) NOT NULL, active_slot TINYINT %s,
                  UNIQUE KEY uk_software_integration_run_active_slot (active_slot), KEY idx_software_integration_run_queue (status, id),
                  KEY idx_software_integration_run_version (model_version_id, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(jsonType, resultType, jsonType, jsonType, generated));
        ensureRunColumn("acceptance_uncertain_at",
                "ALTER TABLE software_integration_run ADD COLUMN acceptance_uncertain_at DATETIME(3) NULL");
        ensureRunColumn("acceptance_recovery_deadline_at",
                "ALTER TABLE software_integration_run ADD COLUMN acceptance_recovery_deadline_at DATETIME(3) NULL");
        ensureResultJsonTextType(h2);
        ensureEclipseRunContract(h2);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS software_integration_run_event (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id BIGINT NOT NULL, event_sequence BIGINT NOT NULL, worker_sequence BIGINT,
                  event_type VARCHAR(64) NOT NULL, status VARCHAR(40), message VARCHAR(1000), error_json %s,
                  occurred_at DATETIME(3) NOT NULL, created_at DATETIME(3) NOT NULL,
                  UNIQUE KEY uk_software_integration_run_event_sequence (run_id, event_sequence),
                  UNIQUE KEY uk_software_integration_run_worker_sequence (run_id, worker_sequence),
                  KEY idx_software_integration_run_event_run (run_id, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(jsonType));
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS software_integration_artifact (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id BIGINT NOT NULL, artifact_name VARCHAR(512) NOT NULL,
                  artifact_type VARCHAR(64) NOT NULL, content_type VARCHAR(255), storage_key VARCHAR(1024) NOT NULL,
                  size_bytes BIGINT NOT NULL, sha256 CHAR(64) NOT NULL, created_at DATETIME(3) NOT NULL, expires_at DATETIME(3),
                  UNIQUE KEY uk_software_integration_artifact_name (run_id, artifact_name),
                  KEY idx_software_integration_artifact_run (run_id, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void ensureRunColumn(String columnName, String ddl) {
        if (runColumnExists(columnName)) return;
        try {
            jdbcTemplate.execute(ddl);
        } catch (DataAccessException exception) {
            if (!runColumnExists(columnName)) throw exception;
        }
    }

    private void ensureModelVersionKindColumn() {
        boolean exists = Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection ->
                columnType(connection, "software_integration_model_version", "model_kind") != null));
        if (exists) return;
        try {
            jdbcTemplate.execute("ALTER TABLE software_integration_model_version ADD COLUMN model_kind VARCHAR(50) NULL");
        } catch (DataAccessException exception) {
            boolean nowExists = Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection ->
                    columnType(connection, "software_integration_model_version", "model_kind") != null));
            if (!nowExists) throw exception;
        }
    }

    private void backfillModelVersionKinds() {
        jdbcTemplate.update("""
                UPDATE software_integration_model_version
                SET model_kind = 'network'
                WHERE model_kind IS NULL AND status = 'READY'
                  AND model_id IN (
                    SELECT id FROM software_integration_model WHERE simulator_type = 'PIPESIM_NETWORK'
                  )
                """);
        jdbcTemplate.update("""
                UPDATE software_integration_model_version
                SET model_kind = 'legacy_well'
                WHERE model_kind IS NULL AND status = 'READY'
                  AND model_id IN (
                    SELECT id FROM software_integration_model WHERE simulator_type = 'PIPESIM_WELL'
                  )
                """);
    }

    private boolean runColumnExists(String columnName) {
        return runColumnType(columnName) != null;
    }

    private void ensureResultJsonTextType(boolean h2) {
        String type = runColumnType("result_json");
        if (type == null || isResultTextType(type, h2)) return;
        String ddl = "ALTER TABLE software_integration_run MODIFY COLUMN result_json "
                + (h2 ? "CLOB" : "LONGTEXT") + " NULL";
        try {
            jdbcTemplate.execute(ddl);
        } catch (DataAccessException exception) {
            String currentType = runColumnType("result_json");
            if (currentType == null || !isResultTextType(currentType, h2)) throw exception;
        }
    }

    private void ensureEclipseRunContract(boolean h2) {
        ensureEclipseStudyNullable(h2);
        if (!runColumnExists("active_slot")) return;
        String expression = runColumnGenerationExpression();
        if (expression == null || !expression.toUpperCase().contains("RUNNING_ECLIPSE")) {
            String statuses = "('CLAIMED','PREPARING','RUNNING_NODAL','RUNNING_PROFILE','RUNNING_NETWORK','RUNNING_ECLIPSE','COLLECTING','CANCEL_REQUESTED')";
            String ddl = h2
                    ? "ALTER TABLE software_integration_run ALTER COLUMN active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN "
                        + h2ActivePredicate() + " THEN 1 ELSE NULL END)"
                    : "ALTER TABLE software_integration_run MODIFY COLUMN active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status IN "
                        + statuses + " THEN 1 ELSE NULL END) STORED";
            try {
                jdbcTemplate.execute(ddl);
            } catch (DataAccessException exception) {
                String current = runColumnGenerationExpression();
                if (current == null || !current.toUpperCase().contains("RUNNING_ECLIPSE")) throw exception;
            }
        }
        ensureActiveSlotUniqueIndex();
    }

    private void ensureEclipseStudyNullable(boolean h2) {
        String type = runColumnType("study_name");
        if (type == null || studyNameNullable(h2)) return;
        try {
            jdbcTemplate.execute(h2
                    ? "ALTER TABLE software_integration_run ALTER COLUMN study_name DROP NOT NULL"
                    : "ALTER TABLE software_integration_run MODIFY COLUMN study_name VARCHAR(255) NULL");
        } catch (DataAccessException exception) {
            if (!studyNameNullable(h2)) throw exception;
        }
    }

    private void ensureActiveSlotUniqueIndex() {
        if (activeSlotUniqueIndexExists()) return;
        try {
            jdbcTemplate.execute("CREATE UNIQUE INDEX uk_software_integration_run_active_slot "
                    + "ON software_integration_run (active_slot)");
        } catch (DataAccessException exception) {
            if (!activeSlotUniqueIndexExists()) throw exception;
        }
    }

    private boolean activeSlotUniqueIndexExists() {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            String catalog = connection.getCatalog();
            for (String tablePattern : new String[]{"software_integration_run", "SOFTWARE_INTEGRATION_RUN"}) {
                try (ResultSet indexes = connection.getMetaData().getIndexInfo(catalog, null, tablePattern, true, false)) {
                    while (indexes.next()) {
                        if ("uk_software_integration_run_active_slot".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }));
    }

    private boolean studyNameNullable(boolean h2) {
        return jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            String schema = h2 ? connection.getSchema() : connection.getCatalog();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT is_nullable FROM information_schema.columns
                    WHERE LOWER(table_schema) = LOWER(?) AND LOWER(table_name) = 'software_integration_run'
                      AND LOWER(column_name) = 'study_name'
                    """)) {
                statement.setString(1, schema);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() && "YES".equalsIgnoreCase(result.getString(1));
                }
            }
        });
    }

    private String runColumnType(String columnName) {
        return jdbcTemplate.execute((ConnectionCallback<String>) connection ->
                columnType(connection, "software_integration_run", columnName));
    }

    private String runColumnGenerationExpression() {
        return jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            boolean h2 = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("h2");
            String schema = h2 ? connection.getSchema() : connection.getCatalog();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT generation_expression FROM information_schema.columns
                    WHERE LOWER(table_schema) = LOWER(?) AND LOWER(table_name) = 'software_integration_run'
                      AND LOWER(column_name) = 'active_slot'
                    """)) {
                statement.setString(1, schema);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getString(1) : null;
                }
            }
        });
    }

    private static boolean isResultTextType(String type, boolean h2) {
        String normalized = type.toUpperCase();
        return h2 ? normalized.contains("CLOB") || normalized.contains("CHARACTER LARGE OBJECT")
                : normalized.equals("LONGTEXT");
    }

    private static String h2ActivePredicate() {
        return "REGEXP_LIKE(status, '^(CLAIMED|PREPARING|RUNNING_NODAL|RUNNING_PROFILE|RUNNING_NETWORK|RUNNING_ECLIPSE|COLLECTING|CANCEL_REQUESTED)$')";
    }

    private static String columnType(Connection connection, String tableName, String columnName) throws SQLException {
        String catalog = connection.getCatalog();
        for (String tablePattern : new String[]{tableName, tableName.toUpperCase()}) {
            try (ResultSet columns = connection.getMetaData().getColumns(catalog, null, tablePattern, null)) {
                while (columns.next()) {
                    if (columnName.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                        return columns.getString("TYPE_NAME");
                    }
                }
            }
        }
        return null;
    }
}
