package com.grdp.studio.storagepressuregradient;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 幂等创建储气库压力梯度测点表。 */
@Component
public class StoragePressureGradientSchemaInitializer {
    private final JdbcTemplate jdbc;

    public StoragePressureGradientSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void initialize() {
        Integer storageTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='project_storage'", Integer.class);
        if (storageTable == null || storageTable == 0) return;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS storage_pressure_gradient_config (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  project_id BIGINT NOT NULL,
                  gas_reservoir_id BIGINT NOT NULL,
                  storage_id BIGINT NOT NULL,
                  reference_coordinate_m DOUBLE NOT NULL DEFAULT 2000,
                  coordinate_mode VARCHAR(16) NOT NULL DEFAULT 'depth',
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_storage_pressure_gradient_config (storage_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='储气库压力梯度折算参数'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS storage_pressure_gradient_point (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  project_id BIGINT NOT NULL,
                  gas_reservoir_id BIGINT NOT NULL,
                  storage_id BIGINT NOT NULL,
                  well_id BIGINT NOT NULL,
                  row_key VARCHAR(255) NOT NULL,
                  source_key VARCHAR(255) NULL,
                  point_date DATE NULL,
                  measured_pressure_mpa DOUBLE NULL,
                  measured_coordinate_m DOUBLE NULL,
                  gradient_mpa_per_m DOUBLE NULL,
                  is_manual TINYINT(1) NOT NULL DEFAULT 0,
                  is_deleted TINYINT(1) NOT NULL DEFAULT 0,
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_storage_pressure_gradient_row (storage_id, row_key),
                  KEY idx_storage_pressure_gradient_scope (project_id, gas_reservoir_id, storage_id, point_date),
                  KEY idx_storage_pressure_gradient_well (well_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='储气库地层压力梯度测点与补录参数'
                """);
    }
}
