-- 产能系数保存 / MySQL 8.0+，在应用实际连接的业务数据库显式执行。
-- 仅补建缺失表，不删除、重命名或修改已有业务数据；已有同名表时须先核对结构。
-- 依赖 project_summaries、project_gas_reservoir、project_well_heads、project_well_pvt 的 BIGINT 主键。
CREATE TABLE IF NOT EXISTS project_well_productivity_coefficient (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    gas_reservoir_id BIGINT NOT NULL,
    well_id BIGINT NOT NULL,
    record_no INT NOT NULL,
    record_name VARCHAR(100) NOT NULL,
    method_type VARCHAR(20) NOT NULL,
    operation_type VARCHAR(20) NOT NULL,
    pressure_method VARCHAR(30) NOT NULL,
    pvt_id BIGINT NULL,
    parameters_json LONGTEXT NOT NULL,
    pvt_snapshot_json LONGTEXT NOT NULL,
    result_value DOUBLE NOT NULL,
    result_type VARCHAR(30) NOT NULL,
    calculation_version VARCHAR(30) NOT NULL,
    units VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_coefficient_well_method_no (project_id,gas_reservoir_id,well_id,method_type,record_no),
    CONSTRAINT fk_coefficient_project FOREIGN KEY (project_id) REFERENCES project_summaries(id) ON DELETE RESTRICT,
    CONSTRAINT fk_coefficient_reservoir FOREIGN KEY (gas_reservoir_id) REFERENCES project_gas_reservoir(id) ON DELETE RESTRICT,
    CONSTRAINT fk_coefficient_well FOREIGN KEY (well_id) REFERENCES project_well_heads(id) ON DELETE RESTRICT,
    CONSTRAINT fk_coefficient_pvt FOREIGN KEY (pvt_id) REFERENCES project_well_pvt(id) ON DELETE SET NULL,
    CONSTRAINT chk_coefficient_no CHECK (record_no > 0),
    CONSTRAINT chk_coefficient_name CHECK (CHAR_LENGTH(TRIM(record_name)) > 0),
    CONSTRAINT chk_coefficient_method CHECK (method_type IN ('二项式','指数式')),
    CONSTRAINT chk_coefficient_operation CHECK (operation_type IN ('production','injection')),
    CONSTRAINT chk_coefficient_pressure_method CHECK (pressure_method IN ('拟压力','压力平方法','压力法')),
    CONSTRAINT chk_coefficient_result CHECK (result_value >= 0),
    CONSTRAINT chk_coefficient_result_type CHECK (
        (method_type='二项式' AND operation_type='injection' AND result_type='injection-limit')
        OR ((method_type='指数式' OR operation_type='production') AND result_type='open-flow')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
