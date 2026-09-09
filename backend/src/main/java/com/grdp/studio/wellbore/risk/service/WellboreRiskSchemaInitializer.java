package com.grdp.studio.wellbore.risk.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 幂等创建井筒积液与水合物计算表。 */
@Component
public class WellboreRiskSchemaInitializer {
    private final JdbcTemplate jdbc;

    public WellboreRiskSchemaInitializer(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @PostConstruct
    public void initialize() {
        Integer wells=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='project_well_heads'",Integer.class);
        if (wells==null||wells==0) return;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS project_well_liquid_loading (
                  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '积液计算ID',
                  well_id BIGINT NOT NULL COMMENT '所属单井，关联project_well_heads.id',
                  calculation_no INT NOT NULL COMMENT '井内只增不复用编号',
                  calculation_name VARCHAR(100) NOT NULL COMMENT '方案显示名称',
                  q_gas_1e4_m3d DOUBLE NOT NULL COMMENT '标况日产气量(10^4m3/d)',
                  q_water_m3d DOUBLE NULL COMMENT '日产水量(m3/d)',
                  pressure_mpa DOUBLE NOT NULL COMMENT '计算压力(MPa，绝压)',
                  temperature_c DOUBLE NOT NULL COMMENT '计算温度(C)',
                  gas_specific_gravity DOUBLE NOT NULL COMMENT '天然气相对密度',
                  liquid_density_kg_m3 DOUBLE NOT NULL COMMENT '液体密度(kg/m3)',
                  surface_tension_mn_m DOUBLE NOT NULL COMMENT '气液界面张力(mN/m)',
                  tubing_id_mm DOUBLE NOT NULL COMMENT '油管内径(mm)',
                  gas_density_kg_m3 DOUBLE NOT NULL COMMENT '工况气体密度(kg/m3)',
                  z_factor DOUBLE NOT NULL COMMENT 'DAK压缩因子',
                  actual_velocity_m_s DOUBLE NOT NULL COMMENT '实际气速(m/s)',
                  critical_velocity_m_s DOUBLE NOT NULL COMMENT 'Turner+20%临界气速(m/s)',
                  critical_rate_1e4_m3d DOUBLE NOT NULL COMMENT 'Turner+20%临界产气量(10^4m3/d)',
                  velocity_ratio DOUBLE NOT NULL COMMENT '实际气速/Turner+20%临界气速',
                  critical_velocity_turner_m_s DOUBLE NOT NULL,
                  critical_velocity_turner20_m_s DOUBLE NOT NULL,
                  critical_velocity_limin_m_s DOUBLE NOT NULL,
                  ratio_turner DOUBLE NOT NULL, ratio_turner20 DOUBLE NOT NULL, ratio_limin DOUBLE NOT NULL,
                  liquid_loading_level VARCHAR(20) NOT NULL COMMENT '不积液/轻度积液/重度积液',
                  level_key VARCHAR(16) NOT NULL COMMENT 'ok/warn/danger',
                  algorithm_code VARCHAR(64) NOT NULL DEFAULT 'critical_liquid_carrying_v1',
                  input_json JSON NOT NULL COMMENT '原始输入快照', result_json JSON NOT NULL COMMENT '完整结果快照',
                  remark VARCHAR(500) NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY(id), UNIQUE KEY uk_liquid_loading_no(well_id,calculation_no),
                  KEY idx_liquid_loading_well_created(well_id,created_at),
                  CONSTRAINT fk_liquid_loading_well FOREIGN KEY(well_id) REFERENCES project_well_heads(id)
                    ON UPDATE CASCADE ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单井临界携液与积液判断结果'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS project_well_hydrate_prediction (
                  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '水合物单点预测ID',
                  well_id BIGINT NOT NULL COMMENT '所属单井', pvt_id BIGINT NULL COMMENT '可选PVT来源',
                  temperature_id BIGINT NULL COMMENT '可选温度模型来源', pressure_conversion_id BIGINT NULL COMMENT '可选压力折算来源',
                  calculation_no INT NOT NULL, calculation_name VARCHAR(100) NOT NULL,
                  pressure_mpa DOUBLE NOT NULL, actual_temperature_c DOUBLE NOT NULL, fugacity_scale DOUBLE NOT NULL DEFAULT 2,
                  raw_hydrate_temperature_c DOUBLE NOT NULL, hydrate_temperature_c DOUBLE NOT NULL,
                  temperature_margin_c DOUBLE NOT NULL, risk_level VARCHAR(20) NOT NULL, risk_description VARCHAR(100) NOT NULL,
                  correction_factor DOUBLE NOT NULL DEFAULT 0.85,
                  method_name VARCHAR(150) NOT NULL, algorithm_code VARCHAR(64) NOT NULL DEFAULT 'revised_du_guo_hydt2_v1',
                  input_json JSON NOT NULL, result_json JSON NOT NULL, remark VARCHAR(500) NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY(id), UNIQUE KEY uk_hydrate_prediction_no(well_id,calculation_no),
                  KEY idx_hydrate_well_created(well_id,created_at), KEY idx_hydrate_pvt(pvt_id),
                  KEY idx_hydrate_temperature(temperature_id), KEY idx_hydrate_pressure(pressure_conversion_id),
                  CONSTRAINT fk_hydrate_well FOREIGN KEY(well_id) REFERENCES project_well_heads(id) ON UPDATE CASCADE ON DELETE CASCADE,
                  CONSTRAINT fk_hydrate_pvt FOREIGN KEY(pvt_id) REFERENCES project_well_pvt(id) ON UPDATE CASCADE ON DELETE SET NULL,
                  CONSTRAINT fk_hydrate_temperature FOREIGN KEY(temperature_id) REFERENCES project_well_temperature(id) ON UPDATE CASCADE ON DELETE SET NULL,
                  CONSTRAINT fk_hydrate_pressure FOREIGN KEY(pressure_conversion_id) REFERENCES project_well_pressure_conversion(id) ON UPDATE CASCADE ON DELETE SET NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单井单点水合物预测结果'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS project_well_hydrate_component (
                  id BIGINT NOT NULL AUTO_INCREMENT, hydrate_prediction_id BIGINT NOT NULL,
                  component_no INT NOT NULL, component_name VARCHAR(16) NOT NULL,
                  input_value DOUBLE NOT NULL COMMENT '页面输入值，百分数或摩尔分数',
                  normalized_fraction DOUBLE NOT NULL COMMENT '全部已识别组分归一化摩尔分数',
                  model_fraction DOUBLE NULL COMMENT 'Du-Guo参与组分再次归一化后的摩尔分数',
                  model_participating TINYINT(1) NOT NULL COMMENT '是否参与hydT2计算',
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  PRIMARY KEY(id), UNIQUE KEY uk_hydrate_component(hydrate_prediction_id,component_name),
                  CONSTRAINT fk_hydrate_component_prediction FOREIGN KEY(hydrate_prediction_id)
                    REFERENCES project_well_hydrate_prediction(id) ON UPDATE CASCADE ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='水合物预测组分输入及归一化快照'
                """);
    }
}
