-- 仅用于升级旧版地面损耗表，人工执行一次；全新数据库使用reservoir_surface_loss.sql。
-- 不删除已有数据，不在应用启动时执行。
USE `database`;

ALTER TABLE project_reservoir_surface_loss
  ADD COLUMN input_condensate_loss_volume DOUBLE NULL COMMENT '直接输入的凝液溶解携带损耗气量，10^4m3' AFTER input_loss_volume,
  MODIFY COLUMN condensate_volume DOUBLE NULL COMMENT '公式方式：分离器出口条件下凝析油体积，10^4m3',
  MODIFY COLUMN gas_oil_ratio DOUBLE NULL COMMENT '公式方式：分离器出口条件下凝析油溶解气油比，m3/m3';

-- 旧直接模式的最终结果保留为新的直接输入值；保留旧输入和更新时间以便追溯。
UPDATE project_reservoir_surface_loss
SET input_condensate_loss_volume=condensate_loss_volume, updated_at=updated_at
WHERE calculation_mode=0 AND input_condensate_loss_volume IS NULL;
