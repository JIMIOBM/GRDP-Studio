-- 储气库井筒损耗：一个记录支持直接输入、公式计算两种方式。
-- 记录直接关联项目和储气库；公式法中的多个放空井段容积保存在明细表。
USE `database`;

CREATE TABLE IF NOT EXISTS project_reservoir_wellbore_loss (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '井筒损耗记录ID',
  project_id BIGINT NOT NULL COMMENT '项目ID',
  gas_reservoir_id BIGINT NOT NULL COMMENT '储气库ID',
  record_no INT NOT NULL COMMENT '储气库内记录编号',
  record_name VARCHAR(100) NOT NULL COMMENT '左侧目录显示名称',
  calculation_mode TINYINT NOT NULL COMMENT '计算方式：0直接输入，1公式计算',

  input_loss_volume DOUBLE NULL COMMENT '直接输入的井筒损耗气量，10^4m3',
  average_temperature_k DOUBLE NULL COMMENT '放空井段天然气平均温度，K',
  pressure_before DOUBLE NULL COMMENT '放空前井筒平均压力，MPa',
  pressure_after DOUBLE NULL COMMENT '放空后井筒平均压力，MPa',
  total_segment_volume DOUBLE NULL COMMENT '公式计算时全部放空井段容积之和，m3',

  gas_type TINYINT NULL COMMENT '天然气类型：0干气，1湿气，2凝析气',
  specific_gravity DOUBLE NULL COMMENT '天然气相对密度，无量纲',
  h2s_mole_fraction DOUBLE NULL COMMENT 'H2S摩尔百分含量，%',
  co2_mole_fraction DOUBLE NULL COMMENT 'CO2摩尔百分含量，%',
  n2_mole_fraction DOUBLE NULL COMMENT 'N2摩尔百分含量，%',
  modification_method TINYINT NULL COMMENT '非烃气体修正方法',
  deviation_factor_method TINYINT NULL COMMENT '天然气偏差系数计算方法',
  viscosity_method TINYINT NULL COMMENT '天然气黏度计算方法',
  imported_file_name VARCHAR(255) NULL COMMENT '导入的PVT基础数据文件名',

  deviation_factor_toolbox_id BIGINT NULL COMMENT 'GasPVT_DeviationFactor工具箱ID',
  deviation_factor_before DOUBLE NULL COMMENT '放空前压力对应的天然气偏差系数',
  deviation_factor_after DOUBLE NULL COMMENT '放空后压力对应的天然气偏差系数',
  wellbore_loss_volume DOUBLE NOT NULL COMMENT '最终井筒损耗气量，10^4m3',

  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_wellbore_loss_scope_no (project_id, gas_reservoir_id, record_no),
  KEY idx_wellbore_loss_scope (project_id, gas_reservoir_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='储气库井筒损耗记录';

CREATE TABLE IF NOT EXISTS project_reservoir_wellbore_loss_segment (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '放空井段明细ID',
  wellbore_loss_id BIGINT NOT NULL COMMENT '井筒损耗记录ID',
  segment_no INT NOT NULL COMMENT '井段顺序号，从1开始',
  segment_volume DOUBLE NOT NULL COMMENT '放空井段容积Vi，m3',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_wellbore_loss_segment_no (wellbore_loss_id, segment_no),
  CONSTRAINT fk_wellbore_loss_segment_record
    FOREIGN KEY (wellbore_loss_id) REFERENCES project_reservoir_wellbore_loss (id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='储气库井筒损耗放空井段容积明细';
