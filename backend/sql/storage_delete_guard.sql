-- 删除库前保留“此井曾承载库计算”的最小安全标记。
-- 仅记录井 ID，不保留被删除库的历史结果；不改写井档案或单井结果。
-- 在新平台业务数据库执行。可重复执行，不删除或覆盖已有数据。
CREATE TABLE IF NOT EXISTS project_well_water_invasion_carrier_guard (
    well_id BIGINT NOT NULL,
    PRIMARY KEY (well_id),
    CONSTRAINT fk_water_invasion_carrier_guard_well
        FOREIGN KEY (well_id) REFERENCES project_well_heads(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
