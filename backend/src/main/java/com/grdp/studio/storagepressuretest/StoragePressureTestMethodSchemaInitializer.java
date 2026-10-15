package com.grdp.studio.storagepressuretest;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Creates the pressure-test measurement table without sharing the pressure-gradient table. */
@Component
public class StoragePressureTestMethodSchemaInitializer {
    private final JdbcTemplate jdbc;

    public StoragePressureTestMethodSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void initialize() {
        Integer storageTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='project_storage'", Integer.class);
        if (storageTable == null || storageTable == 0) return;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS storage_pressure_test_method_point (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  project_id BIGINT NOT NULL,
                  gas_reservoir_id BIGINT NOT NULL,
                  storage_id BIGINT NOT NULL,
                  well_id BIGINT NOT NULL,
                  row_key VARCHAR(255) NOT NULL,
                  source_key VARCHAR(255) NULL,
                  point_date DATE NULL,
                  measured_pressure_mpa DOUBLE NULL,
                  is_manual TINYINT(1) NOT NULL DEFAULT 0,
                  is_deleted TINYINT(1) NOT NULL DEFAULT 0,
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_storage_pressure_test_method_row (storage_id, row_key),
                  KEY idx_storage_pressure_test_method_scope (project_id, gas_reservoir_id, storage_id, point_date),
                  KEY idx_storage_pressure_test_method_well (well_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='储气库地层压力测试法测点'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS storage_pressure_test_method_migration (
                  project_id BIGINT NOT NULL,
                  gas_reservoir_id BIGINT NOT NULL,
                  storage_id BIGINT NOT NULL,
                  migration_key VARCHAR(64) NOT NULL,
                  migrated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  PRIMARY KEY (project_id, gas_reservoir_id, storage_id, migration_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='储气库测试法历史数据迁移记录'
                """);
    }
}
