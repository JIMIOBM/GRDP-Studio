-- 动态产能不稳定流表。请在 project_well_dynamic_productivity 主表已经存在后执行。
USE `database`;

ALTER TABLE project_well_dynamic_productivity
  ADD COLUMN next_unstable_no INT NOT NULL DEFAULT 1 COMMENT '下一次不稳定流编号，只增不回退',
  ADD COLUMN default_porosity DOUBLE NOT NULL DEFAULT 0.1 COMMENT '不稳定流默认孔隙度',
  ADD COLUMN default_total_compressibility DOUBLE NOT NULL DEFAULT 0.01 COMMENT '不稳定流默认总压缩系数(MPa^-1)',
  ADD COLUMN default_flow_time DOUBLE NOT NULL DEFAULT 1 COMMENT '不稳定流默认流动时间(d)';

CREATE TABLE IF NOT EXISTS project_well_dynamic_unstable_calculation (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '不稳定流某次计算ID',
  dynamic_productivity_id BIGINT NOT NULL COMMENT '所属动态产能主记录ID',
  unstable_no INT NOT NULL COMMENT '不稳定流内部编号，只增不复用',
  unstable_name VARCHAR(100) NOT NULL COMMENT '显示名称，可重命名',
  pvt_id BIGINT NULL COMMENT '本次计算选择的PVT记录',
  pvt_name_snapshot VARCHAR(100) NULL COMMENT 'PVT名称快照',
  parameter_source ENUM('default','pvt') NOT NULL COMMENT '参数来源',
  algorithm_code VARCHAR(64) NOT NULL DEFAULT 'dynamic_unstable_flow_v1',
  algorithm_name VARCHAR(100) NOT NULL DEFAULT '动态产能不稳定流',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_dynamic_unstable_no(dynamic_productivity_id,unstable_no),
  UNIQUE KEY uk_dynamic_unstable_name(dynamic_productivity_id,unstable_name),
  KEY idx_dynamic_unstable_pvt(pvt_id),
  CONSTRAINT fk_dynamic_unstable_master FOREIGN KEY(dynamic_productivity_id) REFERENCES project_well_dynamic_productivity(id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_dynamic_unstable_pvt FOREIGN KEY(pvt_id) REFERENCES project_well_pvt(id) ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态产能不稳定流计算记录';

CREATE TABLE IF NOT EXISTS project_well_dynamic_unstable_operation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  unstable_calculation_id BIGINT NOT NULL,
  operation_type ENUM('production','injection') NOT NULL COMMENT '采气或注气',
  calculated_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id), UNIQUE KEY uk_dynamic_unstable_operation(unstable_calculation_id,operation_type),
  CONSTRAINT fk_dynamic_unstable_operation FOREIGN KEY(unstable_calculation_id) REFERENCES project_well_dynamic_unstable_calculation(id) ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不稳定流注采方向';

CREATE TABLE IF NOT EXISTS project_well_dynamic_unstable_input (
  id BIGINT NOT NULL AUTO_INCREMENT, operation_id BIGINT NOT NULL,
  gas_type VARCHAR(64) NOT NULL, specific_gravity DOUBLE NOT NULL,
  hydrogen_sulfide DOUBLE NOT NULL, carbon_dioxide DOUBLE NOT NULL, nitrogen DOUBLE NOT NULL,
  modification_method VARCHAR(64) NOT NULL, deviation_factor_method VARCHAR(64) NOT NULL, viscosity_method VARCHAR(64) NOT NULL,
  permeability DOUBLE NOT NULL, formation_thickness DOUBLE NOT NULL, skin_factor DOUBLE NOT NULL,
  porosity DOUBLE NOT NULL, total_compressibility DOUBLE NOT NULL COMMENT 'MPa^-1', flow_time DOUBLE NOT NULL COMMENT 'd',
  drainage_radius DOUBLE NOT NULL, wellbore_radius DOUBLE NOT NULL, horizontal_section_length DOUBLE NULL,
  original_formation_pressure DOUBLE NOT NULL COMMENT 'MPa', formation_temperature DOUBLE NOT NULL COMMENT 'C',
  initial_gas_viscosity DOUBLE NOT NULL COMMENT 'mPa.s', initial_gas_deviation_factor DOUBLE NOT NULL,
  standard_gas_density DOUBLE NOT NULL COMMENT 'kg/m3', non_darcy_coefficient_beta DOUBLE NOT NULL COMMENT 'm^-1',
  diffusivity DOUBLE NULL COMMENT 'm2/s，水平井不使用时为空',
  transient_function_type ENUM('FT','FH') NOT NULL, transient_function_value DOUBLE NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id), UNIQUE KEY uk_dynamic_unstable_input(operation_id),
  CONSTRAINT fk_dynamic_unstable_input FOREIGN KEY(operation_id) REFERENCES project_well_dynamic_unstable_operation(id) ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不稳定流输入及派生物性快照';

CREATE TABLE IF NOT EXISTS project_well_dynamic_unstable_output (
  id BIGINT NOT NULL AUTO_INCREMENT, operation_id BIGINT NOT NULL,
  pressure_method ENUM('pressure','pressure_squared','pseudo_pressure') NOT NULL,
  darcy_seepage_coefficient DOUBLE NOT NULL, non_darcy_seepage_coefficient DOUBLE NOT NULL,
  open_flow_capacity DOUBLE NULL COMMENT '10^4m3/d', r_squared DOUBLE NULL, calculated_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id), UNIQUE KEY uk_dynamic_unstable_output(operation_id,pressure_method),
  CONSTRAINT fk_dynamic_unstable_output FOREIGN KEY(operation_id) REFERENCES project_well_dynamic_unstable_operation(id) ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不稳定流三种压力处理结果';

CREATE TABLE IF NOT EXISTS project_well_dynamic_unstable_ipr (
  id BIGINT NOT NULL AUTO_INCREMENT, output_id BIGINT NOT NULL, ipr_json JSON NOT NULL COMMENT '十条IPR曲线及点',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id), UNIQUE KEY uk_dynamic_unstable_ipr(output_id),
  CONSTRAINT fk_dynamic_unstable_ipr FOREIGN KEY(output_id) REFERENCES project_well_dynamic_unstable_output(id) ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不稳定流IPR曲线JSON';
