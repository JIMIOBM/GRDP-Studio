package com.grdp.studio.solutiongeneration;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Creates the multi-period prediction persistence tables when the backend starts. */
@Component
public class SolutionGenerationSchemaInitializer {
    private final JdbcTemplate jdbc;

    public SolutionGenerationSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS multi_period_constraint_records (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  project_id BIGINT NOT NULL,
                  gas_reservoir_id BIGINT NOT NULL,
                  well_name VARCHAR(255) NOT NULL,
                  record_name VARCHAR(255) NOT NULL,
                  wellbore_structure INT NOT NULL,
                  tubing_inner_diameter_mm DECIMAL(24,10) NOT NULL,
                  erosion_limit DECIMAL(24,10) NULL,
                  sand_production_limit DECIMAL(24,10) NOT NULL,
                  liquid_loading_limit DECIMAL(24,10) NOT NULL,
                  wellhead_export_pressure DECIMAL(24,10) NOT NULL,
                  lower_bound DECIMAL(24,10) NOT NULL,
                  upper_bound DECIMAL(24,10) NOT NULL,
                  complete TINYINT(1) NOT NULL,
                  feasible TINYINT(1) NOT NULL,
                  usable_for_plan TINYINT(1) NOT NULL,
                  input_schema_version INT NOT NULL DEFAULT 1,
                  inputs_json LONGTEXT NOT NULL,
                  selected_constraints_json LONGTEXT NOT NULL,
                  constraint_results_json LONGTEXT NOT NULL,
                  boundary_conditions_json LONGTEXT NOT NULL,
                  calculation_meta_json LONGTEXT NOT NULL,
                  algorithm_requests_json LONGTEXT NULL,
                  calculated_at DATETIME(3) NOT NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY (id),
                  KEY idx_multi_period_constraint_scope
                    (project_id, gas_reservoir_id, well_name, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                COMMENT='多周期预测约束条件快照'
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS multi_period_solution_records (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  project_id BIGINT NOT NULL,
                  gas_reservoir_id BIGINT NOT NULL,
                  well_name VARCHAR(255) NOT NULL,
                  record_name VARCHAR(255) NOT NULL,
                  target_production DECIMAL(24,10) NULL,
                  export_pressure DECIMAL(24,10) NULL,
                  constraint_record_id BIGINT NULL,
                  source_request_json LONGTEXT NULL,
                  chart_data_json LONGTEXT NULL,
                  boundary_report_json LONGTEXT NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY (id),
                  KEY idx_multi_period_solution_scope
                    (project_id, gas_reservoir_id, well_name, created_at, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                COMMENT='多周期预测单井方案记录'
                """);
        ensureSolutionListIndex();
    }

    private void ensureSolutionListIndex() {
        Integer indexCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics " +
                        "WHERE table_schema=DATABASE() AND table_name=? AND index_name=?",
                Integer.class,
                "multi_period_solution_records",
                "idx_multi_period_solution_scope");
        if (indexCount == null || indexCount == 0) {
            jdbc.execute("CREATE INDEX idx_multi_period_solution_scope " +
                    "ON multi_period_solution_records " +
                    "(project_id, gas_reservoir_id, well_name, created_at, id)");
        }
    }
}