package com.grdp.studio.theoreticalproductivity.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 幂等初始化理论计算不稳定流表。
 *
 * <p>项目当前没有启用 Flyway/Liquibase，因此在后端启动时补齐新增字段和表；
 * 已存在的稳定流表及其中数据不会被修改或删除。</p>
 */
@Component
public class TheoreticalUnstableSchemaInitializer {
    private final JdbcTemplate jdbc;

    public TheoreticalUnstableSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        // 单元测试使用的精简 H2 库没有理论计算主表，此时无需创建业务子表。
        Integer masterCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='project_well_theoretical_productivity'
                """, Integer.class);
        if (masterCount == null || masterCount == 0) return;

        addMasterColumnIfMissing("next_unstable_no", "INT NOT NULL DEFAULT 1 COMMENT '下一次不稳定流编号，只增不回退'");
        addMasterColumnIfMissing("default_porosity", "DOUBLE NOT NULL DEFAULT 0.1 COMMENT '不稳定流默认孔隙度'");
        addMasterColumnIfMissing("default_total_compressibility", "DOUBLE NOT NULL DEFAULT 0.01 COMMENT '不稳定流默认总压缩系数(MPa^-1)'");
        addMasterColumnIfMissing("default_flow_time", "DOUBLE NOT NULL DEFAULT 1 COMMENT '不稳定流默认流动时间(d)'");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS project_well_theoretical_unstable_calculation (
                  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '不稳定流某次计算ID',
                  theoretical_productivity_id BIGINT NOT NULL COMMENT '所属动态产能主记录ID',
                  unstable_no INT NOT NULL COMMENT '不稳定流内部编号，只增不复用',
                  unstable_name VARCHAR(100) NOT NULL COMMENT '显示名称，可重命名',
                  pvt_id BIGINT NULL COMMENT '本次计算选择的PVT记录',
                  pvt_name_snapshot VARCHAR(100) NULL COMMENT 'PVT名称快照',
                  parameter_source ENUM('default','pvt') NOT NULL COMMENT '参数来源',
                  algorithm_code VARCHAR(64) NOT NULL DEFAULT 'theoretical_unstable_flow_v1',
                  algorithm_name VARCHAR(100) NOT NULL DEFAULT '理论计算不稳定流',
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY(id),
                  UNIQUE KEY uk_theoretical_unstable_no(theoretical_productivity_id,unstable_no),
                  UNIQUE KEY uk_theoretical_unstable_name(theoretical_productivity_id,unstable_name),
                  KEY idx_theoretical_unstable_pvt(pvt_id),
                  CONSTRAINT fk_theoretical_unstable_master FOREIGN KEY(theoretical_productivity_id)
                    REFERENCES project_well_theoretical_productivity(id) ON UPDATE CASCADE ON DELETE CASCADE,
                  CONSTRAINT fk_theoretical_unstable_pvt FOREIGN KEY(pvt_id)
                    REFERENCES project_well_pvt(id) ON UPDATE CASCADE ON DELETE SET NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                  COMMENT='理论计算不稳定流计算记录'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS project_well_theoretical_unstable_operation (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  unstable_calculation_id BIGINT NOT NULL,
                  operation_type ENUM('production','injection') NOT NULL COMMENT '采气或注气',
                  calculated_at DATETIME(3) NOT NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY(id),
                  UNIQUE KEY uk_theoretical_unstable_operation(unstable_calculation_id,operation_type),
                  CONSTRAINT fk_theoretical_unstable_operation FOREIGN KEY(unstable_calculation_id)
                    REFERENCES project_well_theoretical_unstable_calculation(id) ON UPDATE CASCADE ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                  COMMENT='不稳定流注采方向'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS project_well_theoretical_unstable_input (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  operation_id BIGINT NOT NULL,
                  gas_type VARCHAR(64) NOT NULL, specific_gravity DOUBLE NOT NULL,
                  hydrogen_sulfide DOUBLE NOT NULL, carbon_dioxide DOUBLE NOT NULL, nitrogen DOUBLE NOT NULL,
                  modification_method VARCHAR(64) NOT NULL, deviation_factor_method VARCHAR(64) NOT NULL,
                  viscosity_method VARCHAR(64) NOT NULL, permeability DOUBLE NOT NULL,
                  formation_thickness DOUBLE NOT NULL, skin_factor DOUBLE NOT NULL,
                  porosity DOUBLE NOT NULL, total_compressibility DOUBLE NOT NULL COMMENT 'MPa^-1',
                  flow_time DOUBLE NOT NULL COMMENT 'd', drainage_radius DOUBLE NOT NULL,
                  wellbore_radius DOUBLE NOT NULL, horizontal_section_length DOUBLE NULL,
                  original_formation_pressure DOUBLE NOT NULL COMMENT 'MPa', formation_temperature DOUBLE NOT NULL COMMENT 'C',
                  initial_gas_viscosity DOUBLE NOT NULL COMMENT 'mPa.s',
                  initial_gas_deviation_factor DOUBLE NOT NULL,
                  standard_gas_density DOUBLE NOT NULL COMMENT 'kg/m3',
                  non_darcy_coefficient_beta DOUBLE NOT NULL COMMENT 'm^-1',
                  diffusivity DOUBLE NULL COMMENT 'm2/s，水平井不使用时为空',
                  transient_function_type ENUM('FT','FH') NOT NULL,
                  transient_function_value DOUBLE NOT NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY(id), UNIQUE KEY uk_theoretical_unstable_input(operation_id),
                  CONSTRAINT fk_theoretical_unstable_input FOREIGN KEY(operation_id)
                    REFERENCES project_well_theoretical_unstable_operation(id) ON UPDATE CASCADE ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                  COMMENT='不稳定流输入及派生物性快照'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS project_well_theoretical_unstable_output (
                  id BIGINT NOT NULL AUTO_INCREMENT, operation_id BIGINT NOT NULL,
                  pressure_method ENUM('pressure','pressure_squared','pseudo_pressure') NOT NULL,
                  darcy_seepage_coefficient DOUBLE NOT NULL,
                  non_darcy_seepage_coefficient DOUBLE NOT NULL,
                  open_flow_capacity DOUBLE NULL COMMENT '10^4m3/d', r_squared DOUBLE NULL,
                  calculated_at DATETIME(3) NOT NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY(id), UNIQUE KEY uk_theoretical_unstable_output(operation_id,pressure_method),
                  CONSTRAINT fk_theoretical_unstable_output FOREIGN KEY(operation_id)
                    REFERENCES project_well_theoretical_unstable_operation(id) ON UPDATE CASCADE ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                  COMMENT='不稳定流三种压力处理结果'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS project_well_theoretical_unstable_ipr (
                  id BIGINT NOT NULL AUTO_INCREMENT, output_id BIGINT NOT NULL,
                  ipr_json JSON NOT NULL COMMENT '十条IPR曲线及点',
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY(id), UNIQUE KEY uk_theoretical_unstable_ipr(output_id),
                  CONSTRAINT fk_theoretical_unstable_ipr FOREIGN KEY(output_id)
                    REFERENCES project_well_theoretical_unstable_output(id) ON UPDATE CASCADE ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                  COMMENT='不稳定流IPR曲线JSON'
                """);
    }

    private void addMasterColumnIfMissing(String column, String definition) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='project_well_theoretical_productivity'
                  AND COLUMN_NAME=?
                """, Integer.class, column);
        if (count != null && count == 0) {
            // 列名和定义均来自本类常量调用，不包含外部输入。
            jdbc.execute("ALTER TABLE project_well_theoretical_productivity ADD COLUMN " + column + " " + definition);
        }
    }
}
