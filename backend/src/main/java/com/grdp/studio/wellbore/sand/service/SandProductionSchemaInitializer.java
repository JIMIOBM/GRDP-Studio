package com.grdp.studio.wellbore.sand.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 幂等创建临界出砂产量预测表。 */
@Component
public class SandProductionSchemaInitializer {
    private final JdbcTemplate jdbc;

    public SandProductionSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void initialize() {
        Integer wells = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='project_well_heads'", Integer.class);
        if (wells == null || wells == 0) return;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS project_well_sand_production (
                  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '出砂预测ID',
                  well_id BIGINT NOT NULL COMMENT '所属单井，关联project_well_heads.id',
                  calculation_no INT NOT NULL COMMENT '井内只增不复用编号',
                  calculation_name VARCHAR(100) NOT NULL COMMENT '方案显示名称',
                  proppant_type VARCHAR(20) NOT NULL COMMENT 'quartz-sand/composite-sand',
                  proppant_type_label VARCHAR(50) NOT NULL COMMENT '支撑剂类型显示名',
                  proppant_mass_t DOUBLE NOT NULL COMMENT '支撑剂质量(t)',
                  proppant_density_g_cm3 DOUBLE NOT NULL COMMENT '堆积密度(g/cm3)',
                  fracture_half_length_m DOUBLE NOT NULL COMMENT '平均裂缝半长(m)',
                  closure_pressure_mpa DOUBLE NOT NULL COMMENT '闭合压力(MPa)',
                  critical_velocity_m_s DOUBLE NOT NULL COMMENT '临界出砂流速(m/s)',
                  velocity_overridden TINYINT(1) NOT NULL DEFAULT 0 COMMENT '临界流速是否手动覆盖',
                  proppant_volume_m3 DOUBLE NOT NULL COMMENT '支撑剂体积(m3)',
                  fracture_area_m2 DOUBLE NOT NULL COMMENT '裂缝张开面总面积(m2)',
                  critical_rate_1e4_m3d DOUBLE NOT NULL COMMENT '临界出砂日产气量(10^4m3/d)',
                  actual_rate_1e4_m3d DOUBLE NULL COMMENT '实际日产气量(10^4m3/d)',
                  ratio_percent DOUBLE NULL COMMENT '实际/临界比值(%)',
                  risk_level VARCHAR(20) NOT NULL COMMENT '易出砂/临界区间/安全/未判断',
                  level_key VARCHAR(16) NOT NULL COMMENT 'danger/warn/ok/none',
                  algorithm_code VARCHAR(64) NOT NULL DEFAULT 'critical_sand_production_v1',
                  input_json JSON NOT NULL COMMENT '原始输入快照', result_json JSON NOT NULL COMMENT '完整结果快照',
                  remark VARCHAR(500) NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY(id), UNIQUE KEY uk_sand_production_no(well_id,calculation_no),
                  KEY idx_sand_well_created(well_id,created_at),
                  CONSTRAINT fk_sand_well FOREIGN KEY(well_id) REFERENCES project_well_heads(id)
                    ON UPDATE CASCADE ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单井临界出砂产量预测结果'
                """);
    }
}
