-- 主控因素分析（库存评估 → 主控因素分析）时序数据表 + 结果快照列。
--
-- 与 建表脚本/02_最终脚本/建表_主控因素分析_时序数据.sql 是**同一份 DDL**：
-- 前者供仓库迁移流程按序执行，后者供人工建表/交付，两者必须保持一致。
--
-- 背景：本功能要画"理论地层压力 vs 实际地层压力"随时间的双线折线图。
-- 折线图需要"每个时间点的累产气量"，这份数据由页面**导入**进入本模块自己的表，
-- 运行时不读原平台/团队其他模块的任何表。
--
-- 单位：与 ToolboxInput.cumulativeGasProduction（10^8m3）以及本模块
-- project_reservoir_main_factor.theoretical_pressure/actual_pressure（MPa）一致，
-- 即**界面口径 10^8m3 / MPa**，导入文件也是这两个单位——全程零换算。
--
-- 一个储气库同一天只能有一个点（storage_id + date 唯一）；
-- 导入语义是"整体替换"，因此同一天重复不会产生第二条。
--
-- 时间为 created_at / updated_at（datetime(3) + ON UPDATE），
-- 与损耗评价、库容设计、主控因素分析结果表等库级表口径一致。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + information_schema 条件式 ALTER，可重复执行。

CREATE TABLE IF NOT EXISTS project_reservoir_main_factor_point (
  id                          BIGINT NOT NULL AUTO_INCREMENT COMMENT '主控因素分析时序点ID',
  project_id                  BIGINT NOT NULL COMMENT '原系统项目关联ID',
  gas_reservoir_id            BIGINT NOT NULL COMMENT '原系统项目范围关联ID 非储气库ID',
  storage_id                  BIGINT NOT NULL COMMENT '独立储气库ID',

  date                        DATE NOT NULL COMMENT '测压日期',
  cumulative_gas_production   DOUBLE NOT NULL COMMENT '累产气量，10^8m3',
  measured_pressure           DOUBLE NOT NULL COMMENT '实测地层压力，MPa',

  created_at                  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at                  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (id),
  UNIQUE KEY uk_main_factor_point (storage_id, date),
  KEY idx_main_factor_point_scope (storage_id, project_id, gas_reservoir_id),
  CONSTRAINT fk_main_factor_point_storage FOREIGN KEY (storage_id, project_id, gas_reservoir_id)
    REFERENCES project_storage (id, project_id, gas_reservoir_id)
    ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='储气库主控因素分析时序数据（导入）';

-- 结果表加一列：把整条地层压力序列作为可恢复快照存下来，
-- 保存过之后重开页面秒出，不必再调 20 次原平台工具箱。
-- 差异与百分比偏差**不入库**（它们由两侧压力推导），读取时重算。
SET @grdp_main_factor_series_column = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE project_reservoir_main_factor ADD COLUMN series_json LONGTEXT NULL AFTER actual_pressure',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'project_reservoir_main_factor'
    AND column_name = 'series_json'
);
PREPARE grdp_main_factor_series_column FROM @grdp_main_factor_series_column;
EXECUTE grdp_main_factor_series_column;
DEALLOCATE PREPARE grdp_main_factor_series_column;

-- 结果卡改成只画折线图、不再显示数值读数之后，014 建的两个标量压力列就没有读者了：
-- 序列快照 series_json 是唯一的结果来源。留着它们只会让人以为还有人在写/读。
-- MySQL 8 不支持 DROP COLUMN IF EXISTS，所以按列分别条件式删除（幂等）。
SET @grdp_main_factor_drop_theoretical = (
  SELECT IF(
    COUNT(*) > 0,
    'ALTER TABLE project_reservoir_main_factor DROP COLUMN theoretical_pressure',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'project_reservoir_main_factor'
    AND column_name = 'theoretical_pressure'
);
PREPARE grdp_main_factor_drop_theoretical FROM @grdp_main_factor_drop_theoretical;
EXECUTE grdp_main_factor_drop_theoretical;
DEALLOCATE PREPARE grdp_main_factor_drop_theoretical;

SET @grdp_main_factor_drop_actual = (
  SELECT IF(
    COUNT(*) > 0,
    'ALTER TABLE project_reservoir_main_factor DROP COLUMN actual_pressure',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'project_reservoir_main_factor'
    AND column_name = 'actual_pressure'
);
PREPARE grdp_main_factor_drop_actual FROM @grdp_main_factor_drop_actual;
EXECUTE grdp_main_factor_drop_actual;
DEALLOCATE PREPARE grdp_main_factor_drop_actual;
