-- PVT 主记录是产能试井六表的外键父表。旧库未执行 PVT 模块迁移时先补齐主表。
CREATE TABLE IF NOT EXISTS project_well_pvt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    well_id BIGINT NOT NULL,
    pvt_no INT NOT NULL,
    pvt_name VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'draft',
    source_type VARCHAR(32) NOT NULL DEFAULT 'manual',
    last_calculated_kind VARCHAR(16) NULL,
    remark VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_well_pvt_no (well_id, pvt_no),
    KEY idx_pvt_well_status (well_id, status),
    CONSTRAINT fk_project_well_pvt_well FOREIGN KEY (well_id) REFERENCES project_well_heads (id)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS project_well_productivity_test (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    project_gas_reservoir_id BIGINT NOT NULL,
    well_id BIGINT NOT NULL,
    well_name VARCHAR(255) NOT NULL,
    pvt_id BIGINT NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    test_method VARCHAR(32) NOT NULL,
    test_no INT NOT NULL,
    test_name VARCHAR(100) NOT NULL,
    test_date DATE NOT NULL,
    well_type VARCHAR(32) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'draft',
    remark VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prod_test_identity (well_id, operation_type, test_method, test_no),
    KEY idx_prod_test_scope (project_id, project_gas_reservoir_id, well_id),
    KEY idx_prod_test_pvt (pvt_id),
    KEY idx_prod_test_menu (well_id, operation_type, test_method, test_date),
    CONSTRAINT fk_prod_test_project FOREIGN KEY (project_id) REFERENCES project_summaries (id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_prod_test_reservoir FOREIGN KEY (project_gas_reservoir_id) REFERENCES project_gas_reservoir (id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_prod_test_well FOREIGN KEY (well_id) REFERENCES project_well_heads (id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_prod_test_pvt FOREIGN KEY (pvt_id) REFERENCES project_well_pvt (id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_prod_operation_type CHECK (operation_type IN ('injection', 'production')),
    CONSTRAINT chk_prod_test_method CHECK (test_method IN ('back-pressure', 'isochronal', 'modified-isochronal', 'one-point')),
    CONSTRAINT chk_prod_test_no CHECK (test_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS project_well_productivity_test_input (
    id BIGINT NOT NULL AUTO_INCREMENT,
    test_id BIGINT NOT NULL,
    maximum_formation_pressure DOUBLE NOT NULL,
    formation_temperature DOUBLE NOT NULL,
    one_point_alpha DOUBLE NULL,
    gas_type VARCHAR(32) NOT NULL,
    specific_gravity DOUBLE NOT NULL,
    hydrogen_sulfide DOUBLE NOT NULL DEFAULT 0,
    carbon_dioxide DOUBLE NOT NULL DEFAULT 0,
    nitrogen DOUBLE NOT NULL DEFAULT 0,
    condensate_oil_density DOUBLE NULL,
    modification_method VARCHAR(64) NULL,
    deviation_factor_method VARCHAR(64) NULL,
    viscosity_method VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prod_test_input (test_id),
    CONSTRAINT fk_prod_test_input_test FOREIGN KEY (test_id)
        REFERENCES project_well_productivity_test (id) ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS project_well_productivity_test_input_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    input_id BIGINT NOT NULL,
    test_point_number INT NOT NULL,
    test_daily_gas_production DOUBLE NOT NULL,
    reservoir_pressure DOUBLE NOT NULL,
    test_flow_pressure DOUBLE NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prod_input_point (input_id, test_point_number),
    CONSTRAINT fk_prod_input_item_input FOREIGN KEY (input_id)
        REFERENCES project_well_productivity_test_input (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_prod_input_point_no CHECK (test_point_number > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS project_well_productivity_binomial_output (
    id BIGINT NOT NULL AUTO_INCREMENT,
    test_id BIGINT NOT NULL,
    pressure_method VARCHAR(32) NOT NULL,
    darcy_seepage_coefficient DOUBLE NOT NULL,
    non_darcy_seepage_coefficient DOUBLE NOT NULL,
    open_flow_capacity DOUBLE NOT NULL,
    gradient DOUBLE NULL,
    intercept DOUBLE NULL,
    r_squared DOUBLE NULL,
    reliability_level INT NULL,
    reliability_description VARCHAR(255) NULL,
    calculated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prod_binomial_method (test_id, pressure_method),
    CONSTRAINT fk_prod_binomial_test FOREIGN KEY (test_id)
        REFERENCES project_well_productivity_test (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_prod_pressure_method CHECK (pressure_method IN ('pseudo-pressure', 'pressure-squared', 'pressure'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS project_well_productivity_binomial_output_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    output_id BIGINT NOT NULL,
    curve_type VARCHAR(40) NOT NULL,
    point_number INT NOT NULL,
    x_value DOUBLE NOT NULL,
    y_value DOUBLE NOT NULL,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    data_label VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prod_output_curve_point (output_id, curve_type, point_number),
    CONSTRAINT fk_prod_output_item_output FOREIGN KEY (output_id)
        REFERENCES project_well_productivity_binomial_output (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_prod_output_point_no CHECK (point_number > 0),
    CONSTRAINT chk_prod_curve_type CHECK (curve_type IN ('regularized', 'stable', 'regression', 'shifted-regression'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS project_well_productivity_binomial_ipr_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    output_id BIGINT NOT NULL,
    curve_number INT NOT NULL,
    point_number INT NOT NULL,
    gas_production DOUBLE NOT NULL,
    bottom_hole_flowing_pressure DOUBLE NOT NULL,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    data_label VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prod_ipr_curve_point (output_id, curve_number, point_number),
    CONSTRAINT fk_prod_ipr_item_output FOREIGN KEY (output_id)
        REFERENCES project_well_productivity_binomial_output (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_prod_ipr_curve_no CHECK (curve_number > 0),
    CONSTRAINT chk_prod_ipr_point_no CHECK (point_number > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
