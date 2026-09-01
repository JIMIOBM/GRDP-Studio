-- 从曾将指数式结果混存于二项式表的开发库迁移到设计的指数式三表。
-- 执行顺序：先创建新表并复制数据，验证后再还原原六表结构。
CREATE TABLE IF NOT EXISTS project_well_productivity_exponential_output (
    id BIGINT NOT NULL AUTO_INCREMENT,
    test_id BIGINT NOT NULL,
    pressure_method VARCHAR(32) NOT NULL,
    productivity_coefficient DOUBLE NOT NULL,
    productivity_exponent DOUBLE NOT NULL,
    open_flow_capacity DOUBLE NOT NULL,
    r_squared DOUBLE NULL,
    reliability_description VARCHAR(255) NULL,
    calculated_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prod_exp_method (test_id, pressure_method),
    CONSTRAINT fk_prod_exp_test FOREIGN KEY (test_id)
        REFERENCES project_well_productivity_test (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_prod_exp_pressure_method CHECK (
        pressure_method IN ('pseudo-pressure', 'pressure-squared', 'pressure')
    ),
    CONSTRAINT chk_prod_exp_coefficient CHECK (productivity_coefficient > 0),
    CONSTRAINT chk_prod_exp_exponent CHECK (productivity_exponent > 0),
    CONSTRAINT chk_prod_exp_aof CHECK (open_flow_capacity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS project_well_productivity_exponential_output_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    output_id BIGINT NOT NULL,
    curve_type VARCHAR(32) NOT NULL,
    point_number INT NOT NULL,
    source_point_number INT NULL,
    x_value DOUBLE NOT NULL,
    y_value DOUBLE NOT NULL,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    data_label VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prod_exp_curve_point (output_id, curve_type, point_number),
    CONSTRAINT fk_prod_exp_item_output FOREIGN KEY (output_id)
        REFERENCES project_well_productivity_exponential_output (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_prod_exp_curve_type CHECK (curve_type IN ('analysis', 'regression', 'transient')),
    CONSTRAINT chk_prod_exp_point_no CHECK (point_number > 0),
    CONSTRAINT chk_prod_exp_source_point CHECK (source_point_number IS NULL OR source_point_number > 0),
    CONSTRAINT chk_prod_exp_item_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS project_well_productivity_exponential_ipr_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    output_id BIGINT NOT NULL,
    curve_number INT NOT NULL,
    formation_pressure DOUBLE NOT NULL,
    point_number INT NOT NULL,
    gas_production DOUBLE NOT NULL,
    bottom_hole_flowing_pressure DOUBLE NOT NULL,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    data_label VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prod_exp_ipr_point (output_id, curve_number, point_number),
    CONSTRAINT fk_prod_exp_ipr_output FOREIGN KEY (output_id)
        REFERENCES project_well_productivity_exponential_output (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_prod_exp_ipr_curve CHECK (curve_number > 0),
    CONSTRAINT chk_prod_exp_ipr_point CHECK (point_number > 0),
    CONSTRAINT chk_prod_exp_ipr_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 只有早期开发库把指数式结果混存在二项式表中；现代六表结构没有 result_type 等列。
-- 用信息架构保护旧数据搬迁，使本迁移可同时安全用于两种数据库。
DELIMITER //
DROP PROCEDURE IF EXISTS migrate_legacy_productivity_exponential//
CREATE PROCEDURE migrate_legacy_productivity_exponential()
BEGIN
IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'project_well_productivity_binomial_output'
      AND column_name = 'result_type'
) THEN
INSERT INTO project_well_productivity_exponential_output
    (test_id, pressure_method, productivity_coefficient, productivity_exponent,
     open_flow_capacity, r_squared, reliability_description, calculated_at, updated_at)
SELECT test_id, pressure_method, productivity_coefficient, productivity_exponent,
       open_flow_capacity, r_squared, reliability_description, calculated_at, updated_at
FROM project_well_productivity_binomial_output
WHERE result_type = 'exponential'
ON DUPLICATE KEY UPDATE
    productivity_coefficient = VALUES(productivity_coefficient),
    productivity_exponent = VALUES(productivity_exponent),
    open_flow_capacity = VALUES(open_flow_capacity),
    r_squared = VALUES(r_squared),
    reliability_description = VALUES(reliability_description),
    calculated_at = VALUES(calculated_at),
    updated_at = VALUES(updated_at);

INSERT INTO project_well_productivity_exponential_output_item
    (output_id, curve_type, point_number, source_point_number, x_value, y_value,
     is_deleted, data_label, created_at)
SELECT neo.id,
       CASE old_item.curve_type
           WHEN 'regularized' THEN 'analysis'
           WHEN 'shifted-regression' THEN 'transient'
           ELSE old_item.curve_type
       END,
       old_item.point_number,
       CASE WHEN old_item.curve_type = 'regularized' THEN old_item.point_number ELSE NULL END,
       old_item.x_value, old_item.y_value, old_item.is_deleted, old_item.data_label, old_item.created_at
FROM project_well_productivity_binomial_output old_output
JOIN project_well_productivity_binomial_output_item old_item ON old_item.output_id = old_output.id
JOIN project_well_productivity_exponential_output neo
  ON neo.test_id = old_output.test_id AND neo.pressure_method = old_output.pressure_method
WHERE old_output.result_type = 'exponential'
ON DUPLICATE KEY UPDATE
    source_point_number = VALUES(source_point_number),
    x_value = VALUES(x_value), y_value = VALUES(y_value),
    is_deleted = VALUES(is_deleted), data_label = VALUES(data_label);

INSERT INTO project_well_productivity_exponential_ipr_item
    (output_id, curve_number, formation_pressure, point_number, gas_production,
     bottom_hole_flowing_pressure, is_deleted, data_label, created_at)
SELECT neo.id, old_item.curve_number,
       COALESCE(CAST(NULLIF(SUBSTRING_INDEX(old_item.data_label, 'formationPressure:', -1), old_item.data_label) AS DOUBLE),
                input.maximum_formation_pressure),
       old_item.point_number, old_item.gas_production, old_item.bottom_hole_flowing_pressure,
       old_item.is_deleted, old_item.data_label, old_item.created_at
FROM project_well_productivity_binomial_output old_output
JOIN project_well_productivity_binomial_ipr_item old_item ON old_item.output_id = old_output.id
JOIN project_well_productivity_exponential_output neo
  ON neo.test_id = old_output.test_id AND neo.pressure_method = old_output.pressure_method
JOIN project_well_productivity_test_input input ON input.test_id = old_output.test_id
WHERE old_output.result_type = 'exponential'
ON DUPLICATE KEY UPDATE
    formation_pressure = VALUES(formation_pressure),
    gas_production = VALUES(gas_production),
    bottom_hole_flowing_pressure = VALUES(bottom_hole_flowing_pressure),
    is_deleted = VALUES(is_deleted), data_label = VALUES(data_label);

DELETE FROM project_well_productivity_binomial_output WHERE result_type = 'exponential';

ALTER TABLE project_well_productivity_binomial_output
    DROP INDEX idx_prod_binomial_source_evaluation,
    DROP COLUMN source_evaluation_id,
    DROP COLUMN result_type,
    DROP COLUMN productivity_coefficient,
    DROP COLUMN productivity_exponent,
    MODIFY COLUMN darcy_seepage_coefficient DOUBLE NOT NULL,
    MODIFY COLUMN non_darcy_seepage_coefficient DOUBLE NOT NULL;
END IF;
END//
CALL migrate_legacy_productivity_exponential()//
DROP PROCEDURE migrate_legacy_productivity_exponential//
DELIMITER ;
