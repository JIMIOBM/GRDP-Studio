-- 主控因素分析（库存评估 → 主控因素分析）保存表。
--
-- 与 建表脚本/02_最终脚本/建表_主控因素分析.sql 是**同一份 DDL**：
-- 前者供仓库迁移流程按序执行，后者供人工建表/交付，两者必须保持一致。
--
-- 一个储气库一条记录（storage_id 唯一 + 指向 project_storage 的复合外键），
-- 保存语义是"先 UPDATE，影响行数为 0 再 INSERT"，因此重复保存只更新同一行。
-- 本功能不做记录列表、不做重命名/删除，也不接左侧树——那是「微观损耗」那种
-- "同一口井多个比选方案"才需要的结构。
--
-- 时间为 created_at / updated_at（datetime(3) + ON UPDATE），
-- 与损耗评价、库容设计等库级表口径一致。
--
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行。
CREATE TABLE IF NOT EXISTS project_reservoir_main_factor (
  id                                BIGINT NOT NULL AUTO_INCREMENT COMMENT '主控因素分析ID',
  project_id                        BIGINT NOT NULL COMMENT '原系统项目关联ID',
  gas_reservoir_id                  BIGINT NOT NULL COMMENT '原系统项目范围关联ID 非储气库ID',
  storage_id                        BIGINT NOT NULL COMMENT '独立储气库ID',

  -- 原平台「物质平衡方程 → 计算地层压力」工具箱的入参（数据库口径）
  gas_reservoir_type                INT NULL COMMENT '气藏类型 0封闭气藏 1定容气藏 2页岩气藏',
  original_pressure                 DOUBLE NULL COMMENT '原始地层压力，Pa',
  formation_temperature             DOUBLE NULL COMMENT '地层温度，K',
  original_gas_in_place             DOUBLE NULL COMMENT '动态地质储量，10^8m3',
  cumulative_gas_production         DOUBLE NULL COMMENT '累产气量，10^8m3',
  rock_compression_coefficient      DOUBLE NULL COMMENT '岩石压缩系数，1/Pa',
  water_compression_coefficient     DOUBLE NULL COMMENT '地层水压缩系数，1/Pa',
  water_saturation                  DOUBLE NULL COMMENT '束缚水饱和度，小数',
  gas_type                          INT NULL COMMENT '天然气类型 0干气 1湿气 2凝析气',
  specific_gravity                  DOUBLE NULL COMMENT '天然气比重，dless',
  h2s_mole_fraction                 DOUBLE NULL COMMENT 'H2S摩尔百分含量，小数',
  co2_mole_fraction                 DOUBLE NULL COMMENT 'CO2摩尔百分含量，小数',
  n2_mole_fraction                  DOUBLE NULL COMMENT 'N2摩尔百分含量，小数',
  modification_method               INT NULL COMMENT '非烃气体修正方法 0 Wichert-Aziz 1 Carr-Kobayashi-Burrows',
  deviation_factor_method           INT NULL COMMENT '天然气偏差系数计算方法 0 DAK 1 DPR 2 Hall-Yarborough',
  viscosity_method                  INT NULL COMMENT '天然气黏度计算方法，界面不提供，固定 0',

  theoretical_pressure              DOUBLE NULL COMMENT '理论地层压力（原平台工具箱），MPa',
  actual_pressure                   DOUBLE NULL COMMENT '实际地层压力（库内实测静压），MPa',

  created_at                        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at                        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                      ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (id),
  UNIQUE KEY uk_storage_main_factor_storage (storage_id),
  KEY idx_storage_main_factor_scope (storage_id, project_id, gas_reservoir_id),
  CONSTRAINT fk_storage_main_factor_storage FOREIGN KEY (storage_id, project_id, gas_reservoir_id)
    REFERENCES project_storage (id, project_id, gas_reservoir_id)
    ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='储气库主控因素分析';
