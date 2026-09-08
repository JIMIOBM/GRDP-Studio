-- 储气库地质损耗：计算记录直接关联项目和储气库，不设置额外“库1”层级。
USE `database`;

CREATE TABLE IF NOT EXISTS project_reservoir_microscopic_loss (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '微观损耗记录ID',
  project_id BIGINT NOT NULL COMMENT '项目ID',
  gas_reservoir_id BIGINT NOT NULL COMMENT '储气库ID',
  record_no INT NOT NULL COMMENT '储气库内记录编号',
  record_name VARCHAR(100) NOT NULL COMMENT '显示名称',
  pore_volume DOUBLE NOT NULL COMMENT '气水过渡带孔隙体积，10^4m3',
  previous_residual_saturation DOUBLE NOT NULL COMMENT '上周期残余气饱和度，%',
  current_residual_saturation DOUBLE NOT NULL COMMENT '本周期残余气饱和度，%',
  lower_limit_pressure DOUBLE NOT NULL COMMENT '下限压力，MPa',
  formation_temperature DOUBLE NOT NULL COMMENT '地层温度，C',
  gas_type TINYINT NOT NULL COMMENT '0干气1湿气2凝析气',
  specific_gravity DOUBLE NOT NULL,
  h2s_mole_fraction DOUBLE NOT NULL COMMENT '%',
  co2_mole_fraction DOUBLE NOT NULL COMMENT '%',
  n2_mole_fraction DOUBLE NOT NULL COMMENT '%',
  modification_method TINYINT NOT NULL,
  deviation_factor_method TINYINT NOT NULL,
  viscosity_method TINYINT NOT NULL,
  imported_file_name VARCHAR(255) NULL,
  volume_factor_toolbox_id BIGINT NOT NULL COMMENT 'GasPVT_VolumeFactor工具箱ID',
  volume_factor DOUBLE NOT NULL COMMENT '下限压力天然气体积系数',
  microscopic_loss_volume DOUBLE NOT NULL COMMENT '微观损耗气量，10^4m3',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_microscopic_scope_no (project_id, gas_reservoir_id, record_no),
  KEY idx_microscopic_scope (project_id, gas_reservoir_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='储气库微观地质损耗记录';

CREATE TABLE IF NOT EXISTS project_reservoir_escape_loss (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '逸散性损耗记录ID',
  project_id BIGINT NOT NULL COMMENT '项目ID',
  gas_reservoir_id BIGINT NOT NULL COMMENT '储气库ID',
  record_no INT NOT NULL COMMENT '储气库内记录编号',
  record_name VARCHAR(100) NOT NULL COMMENT '显示名称',
  previous_cushion_gas_volume DOUBLE NOT NULL COMMENT '上一周期垫气量，10^4m3',
  movable_cushion_gas_volume DOUBLE NOT NULL COMMENT '本周期可动垫气量，10^4m3',
  unused_inventory_volume DOUBLE NOT NULL COMMENT '本周期未动用库存量，10^4m3',
  injection_volume DOUBLE NOT NULL COMMENT '本周期注气量，10^4m3',
  predicted_change_rate DOUBLE NOT NULL COMMENT '预测垫气变化率，%',
  actual_change_rate DOUBLE NOT NULL COMMENT '实际垫气变化率，%',
  escape_loss_volume DOUBLE NOT NULL COMMENT '逸散性损耗气量，10^4m3',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_escape_scope_no (project_id, gas_reservoir_id, record_no),
  KEY idx_escape_scope (project_id, gas_reservoir_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='储气库逸散性地质损耗记录';
