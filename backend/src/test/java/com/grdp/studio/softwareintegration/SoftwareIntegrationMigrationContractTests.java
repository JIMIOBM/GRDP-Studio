package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.SoftwareIntegrationSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SoftwareIntegrationMigrationContractTests {
    @Test
    void migrationContainsRunEventArtifactAndDatabaseActiveSlotGuard() throws Exception {
        Path backend = Path.of("").toAbsolutePath().normalize();
        if (!backend.getFileName().toString().equalsIgnoreCase("backend")) backend = backend.resolve("backend");
        String sql = Files.readString(backend.resolve("deploy/mysql/migrations/005_software_integration_demo01_run.sql"));
        assertThat(sql).contains("software_integration_run", "software_integration_run_event", "software_integration_artifact");
        assertThat(sql).contains("active_slot TINYINT GENERATED ALWAYS AS", "uk_software_integration_run_active_slot");
        assertThat(sql).contains("parameters_json JSON NOT NULL", "result_json LONGTEXT", "error_json JSON",
                "cleanup_json JSON", "error_category VARCHAR(64)");
        assertThat(sql).contains("acceptance_uncertain_at DATETIME(3)", "acceptance_recovery_deadline_at DATETIME(3)");
        assertThat(sql).contains("'CLAIMED'", "'PREPARING'", "'RUNNING_NODAL'", "'RUNNING_PROFILE'",
                "'COLLECTING'", "'CANCEL_REQUESTED'");
        assertThat(sql.toUpperCase()).doesNotContain(" BLOB", "PARAMETERS_JSON TEXT", "RESULT_JSON JSON");
    }

    @Test
    void additiveAcceptanceRecoveryMigrationIsRepeatableAndNeverDropsData() throws Exception {
        Path backend = Path.of("").toAbsolutePath().normalize();
        if (!backend.getFileName().toString().equalsIgnoreCase("backend")) backend = backend.resolve("backend");
        String sql = Files.readString(backend.resolve(
                "deploy/mysql/migrations/006_software_integration_demo01_acceptance_recovery.sql"));
        assertThat(sql).contains("information_schema.columns", "table_schema = DATABASE()",
                "ADD COLUMN acceptance_uncertain_at DATETIME(3) NULL",
                "ADD COLUMN acceptance_recovery_deadline_at DATETIME(3) NULL",
                "PREPARE grdp_acceptance_recovery_column", "EXECUTE grdp_acceptance_recovery_column");
        assertThat(sql.toUpperCase()).doesNotContain("DROP ", "CREATE TABLE");
    }

    @Test
    void additiveResultPrecisionMigrationConvertsOnlyResultJsonToLongText() throws Exception {
        Path backend = Path.of("").toAbsolutePath().normalize();
        if (!backend.getFileName().toString().equalsIgnoreCase("backend")) backend = backend.resolve("backend");
        String sql = Files.readString(backend.resolve(
                "deploy/mysql/migrations/007_software_integration_demo01_result_precision.sql"));
        assertThat(sql).contains("information_schema.columns", "table_schema = DATABASE()",
                "column_name = 'result_json'", "LOWER(MAX(data_type)) = 'longtext'",
                "MODIFY COLUMN result_json LONGTEXT NULL",
                "PREPARE grdp_result_json_precision_column", "EXECUTE grdp_result_json_precision_column");
        assertThat(sql.toUpperCase()).doesNotContain("DROP ", "CREATE TABLE", "PARAMETERS_JSON LONGTEXT",
                "ERROR_JSON LONGTEXT", "CLEANUP_JSON LONGTEXT");
    }

    @Test
    void networkMigrationRepeatablyExtendsTheGeneratedActiveSlot() throws Exception {
        Path backend = Path.of("").toAbsolutePath().normalize();
        if (!backend.getFileName().toString().equalsIgnoreCase("backend")) backend = backend.resolve("backend");
        String sql = Files.readString(backend.resolve(
                "deploy/mysql/migrations/008_software_integration_pipesim_network.sql"));
        assertThat(sql).contains("information_schema.columns", "table_schema = DATABASE()",
                "generation_expression", "RUNNING_NETWORK",
                 "MODIFY COLUMN active_slot TINYINT GENERATED ALWAYS AS",
                 "PREPARE grdp_network_active_slot_column", "EXECUTE grdp_network_active_slot_column",
                 "ADD COLUMN model_kind VARCHAR(50) NULL",
                 "PREPARE grdp_model_version_kind_column", "EXECUTE grdp_model_version_kind_column",
                 "UPDATE software_integration_model_version", "WHEN 'PIPESIM_NETWORK' THEN 'network'",
                 "WHEN 'PIPESIM_WELL' THEN 'legacy_well'");
        assertThat(sql.toUpperCase()).doesNotContain("DROP ", "CREATE TABLE");
    }

    @Test
    void eclipseMigrationMakesOnlyTheStudyOptionalAndExtendsTheActiveSlot() throws Exception {
        Path backend = Path.of("").toAbsolutePath().normalize();
        if (!backend.getFileName().toString().equalsIgnoreCase("backend")) backend = backend.resolve("backend");
        String sql = Files.readString(backend.resolve(
                "deploy/mysql/migrations/009_software_integration_eclipse_contract.sql"));
        assertThat(sql).contains("column_name = 'study_name'", "MAX(IS_NULLABLE) = 'YES'",
                "MODIFY COLUMN study_name VARCHAR(255) NULL", "RUNNING_ECLIPSE",
                "PREPARE grdp_eclipse_study_column", "PREPARE grdp_eclipse_active_slot_column");
        assertThat(sql.toUpperCase()).doesNotContain("DROP ", "CREATE TABLE", "RESULT_JSON", " BLOB", "DELETE ", "UPDATE ");
    }

    @Test
    void schemaInitializerRepeatablyUpgradesAnExistingRunTable() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:acceptance-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE software_integration_run (id BIGINT AUTO_INCREMENT PRIMARY KEY, result_json JSON)");
        jdbcTemplate.execute("CREATE TABLE software_integration_model (id BIGINT PRIMARY KEY, simulator_type VARCHAR(50))");
        jdbcTemplate.execute("CREATE TABLE software_integration_model_version (id BIGINT PRIMARY KEY, model_id BIGINT, status VARCHAR(50))");
        jdbcTemplate.update("INSERT INTO software_integration_model (id, simulator_type) VALUES (1, 'PIPESIM_WELL'), (2, 'PIPESIM_NETWORK')");
        jdbcTemplate.update("INSERT INTO software_integration_model_version (id, model_id, status) VALUES (1, 1, 'READY'), (2, 2, 'READY')");
        SoftwareIntegrationSchemaInitializer initializer = new SoftwareIntegrationSchemaInitializer(jdbcTemplate);

        initializer.run(null);
        initializer.run(null);

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE LOWER(table_name) = 'software_integration_run'
                  AND LOWER(column_name) IN ('acceptance_uncertain_at', 'acceptance_recovery_deadline_at')
                """, Integer.class);
        assertThat(count).isEqualTo(2);
        String resultType = jdbcTemplate.queryForObject("""
                SELECT DATA_TYPE FROM information_schema.columns
                WHERE LOWER(table_name) = 'software_integration_run' AND LOWER(column_name) = 'result_json'
                """, String.class);
        assertThat(resultType).containsIgnoringCase("LARGE OBJECT");
        Integer modelKindColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE LOWER(table_name) = 'software_integration_model_version'
                  AND LOWER(column_name) = 'model_kind'
                """, Integer.class);
        assertThat(modelKindColumns).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT model_kind FROM software_integration_model_version WHERE id = 1", String.class))
                .isEqualTo("legacy_well");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT model_kind FROM software_integration_model_version WHERE id = 2", String.class))
                .isEqualTo("network");
        String precise = "{\"flow\":110.84152977856141}";
        jdbcTemplate.update("INSERT INTO software_integration_run (result_json) VALUES (?)", precise);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT result_json FROM software_integration_run ORDER BY id DESC LIMIT 1", String.class)).isEqualTo(precise);
    }

    @Test
    void schemaInitializerCreatesEclipseActiveSlotAndNullableStudyForFreshH2Schema() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:network-active-slot-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        SoftwareIntegrationSchemaInitializer initializer = new SoftwareIntegrationSchemaInitializer(jdbcTemplate);

        initializer.run(null);
        initializer.run(null);

        String expression = jdbcTemplate.queryForObject("""
                SELECT generation_expression FROM information_schema.columns
                WHERE LOWER(table_name) = 'software_integration_run' AND LOWER(column_name) = 'active_slot'
                """, String.class);
        assertThat(expression).contains("RUNNING_NETWORK", "RUNNING_ECLIPSE");
        String nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE LOWER(table_name) = 'software_integration_run' AND LOWER(column_name) = 'study_name'
                """, String.class);
        assertThat(nullable).isEqualToIgnoringCase("YES");
    }

    @Test
    void schemaInitializerRepeatablyUpgradesOldH2EclipseRunContractWithoutLosingRowsOrIndex() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:eclipse-old-schema-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE software_integration_model (id BIGINT PRIMARY KEY, simulator_type VARCHAR(50))");
        jdbcTemplate.execute("CREATE TABLE software_integration_model_version (id BIGINT PRIMARY KEY, model_id BIGINT, status VARCHAR(50))");
        jdbcTemplate.execute("""
                CREATE TABLE software_integration_run (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  study_name VARCHAR(255) NOT NULL,
                  status VARCHAR(40) NOT NULL,
                  result_json JSON,
                  active_slot TINYINT GENERATED ALWAYS AS (
                    CASE WHEN REGEXP_LIKE(status, '^(CLAIMED|PREPARING|RUNNING_NODAL|RUNNING_PROFILE|RUNNING_NETWORK|COLLECTING|CANCEL_REQUESTED)$')
                    THEN 1 ELSE NULL END),
                  CONSTRAINT uk_software_integration_run_active_slot UNIQUE (active_slot)
                )
                """);
        jdbcTemplate.update("INSERT INTO software_integration_run (study_name, status, result_json) VALUES ('Study 1', 'SUCCEEDED', '{\"kept\":true}')");
        SoftwareIntegrationSchemaInitializer initializer = new SoftwareIntegrationSchemaInitializer(jdbcTemplate);

        initializer.run(null);
        initializer.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM software_integration_run", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT result_json FROM software_integration_run WHERE id = 1", String.class))
                .contains("kept", "true");
        String nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE LOWER(table_name) = 'software_integration_run' AND LOWER(column_name) = 'study_name'
                """, String.class);
        assertThat(nullable).isEqualToIgnoringCase("YES");
        String expression = jdbcTemplate.queryForObject("""
                SELECT generation_expression FROM information_schema.columns
                WHERE LOWER(table_name) = 'software_integration_run' AND LOWER(column_name) = 'active_slot'
                """, String.class);
        assertThat(expression).contains("RUNNING_NETWORK", "RUNNING_ECLIPSE");
        Integer indexCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.indexes
                WHERE LOWER(table_name) = 'software_integration_run'
                  AND LOWER(index_name) = 'uk_software_integration_run_active_slot'
                """, Integer.class);
        assertThat(indexCount).isEqualTo(1);
        jdbcTemplate.update("INSERT INTO software_integration_run (study_name, status) VALUES (NULL, 'RUNNING_ECLIPSE')");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active_slot FROM software_integration_run WHERE status = 'RUNNING_ECLIPSE'", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO software_integration_run (study_name, status) VALUES (NULL, 'RUNNING_ECLIPSE')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
