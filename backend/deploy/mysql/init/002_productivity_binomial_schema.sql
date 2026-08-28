-- PVT 主记录是产能试井六表的外键父表。旧库未执行 PVT 模块迁移时先补齐主表。
CREATE TABLE IF NOT EXISTS project_well_pvt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    well_id BIGINT NOT NULL,
    pvt_no INT NOT NULL,
    pvt_name VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'draft',
    source_type VARCHAR(32) NULL,
    last_calculated_kind VARCHAR(32) NULL,
    remark VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_well_pvt_no (well_id, pvt_no),
    CONSTRAINT fk_pvt_well FOREIGN KEY (well_id) REFERENCES project_well_heads (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS project_well_productivity_test (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    project_gas_reservoir_id BIGINT NOT NULL,
    well_id BIGINT NOT NULL,
    well_name VARCHAR(255) NOT NULL,
    pvt_id BIGINT NOT NULL,
    operation_type ENUM('injection','production') NOT NULL,
    test_method ENUM('back-pressure','isochronal','modified-isochronal','one-point') NOT NULL,
    test_no INT NOT NULL,
    test_name VARCHAR(100) NOT NULL,
    test_date DATE NOT NULL,
    well_type VARCHAR(64) NULL,
    status ENUM('draft','data-ready','calculated') NOT NULL DEFAULT 'draft',
    remark VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_productivity_test_no (well_id, operation_type, test_method, test_no),
    KEY idx_productivity_test_scope (project_id, project_gas_reservoir_id, well_name),
    CONSTRAINT fk_productivity_test_project FOREIGN KEY (project_id) REFERENCES project_summaries (id),
    CONSTRAINT fk_productivity_test_reservoir FOREIGN KEY (project_gas_reservoir_id) REFERENCES project_gas_reservoir (id),
    CONSTRAINT fk_productivity_test_well FOREIGN KEY (well_id) REFERENCES project_well_heads (id),
    CONSTRAINT fk_productivity_test_pvt FOREIGN KEY (pvt_id) REFERENCES project_well_pvt (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS project_well_productivity_test_input (
    id BIGINT NOT NULL AUTO_INCREMENT,
    test_id BIGINT NOT NULL,
    maximum_formation_pressure DOUBLE NOT NULL,
    formation_temperature DOUBLE NOT NULL,
    one_point_alpha DOUBLE NULL,
    gas_type VARCHAR(64) NULL,
    specific_gravity DOUBLE NULL,
    hydrogen_sulfide DOUBLE NULL,
    carbon_dioxide DOUBLE NULL,
    nitrogen DOUBLE NULL,
    condensate_oil_density DOUBLE NULL,
    modification_method VARCHAR(64) NULL,
    deviation_factor_method VARCHAR(64) NULL,
    viscosity_method VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_productivity_input_test (test_id),
    CONSTRAINT fk_productivity_input_test FOREIGN KEY (test_id)
        REFERENCES project_well_productivity_test (id) ON DELETE CASCADE
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
    UNIQUE KEY uk_productivity_input_point (input_id, test_point_number),
    CONSTRAINT fk_productivity_input_item FOREIGN KEY (input_id)
        REFERENCES project_well_productivity_test_input (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS project_well_productivity_binomial_output (
    id BIGINT NOT NULL AUTO_INCREMENT,
    test_id BIGINT NOT NULL,
    pressure_method ENUM('pseudo-pressure','pressure-squared','pressure') NOT NULL,
    result_type ENUM('binomial','exponential') NOT NULL DEFAULT 'binomial',
    darcy_seepage_coefficient DOUBLE NULL,
    non_darcy_seepage_coefficient DOUBLE NULL,
    productivity_coefficient DOUBLE NULL,
    productivity_exponent DOUBLE NULL,
    open_flow_capacity DOUBLE NOT NULL,
    gradient DOUBLE NULL,
    intercept DOUBLE NULL,
    r_squared DOUBLE NULL,
    reliability_level VARCHAR(32) NULL,
    reliability_description VARCHAR(255) NULL,
    calculated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_binomial_output_method (test_id, pressure_method),
    CONSTRAINT fk_binomial_output_test FOREIGN KEY (test_id)
        REFERENCES project_well_productivity_test (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS project_well_productivity_binomial_output_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    output_id BIGINT NOT NULL,
    curve_type ENUM('regularized','stable','regression','shifted-regression') NOT NULL,
    point_number INT NOT NULL,
    x_value DOUBLE NOT NULL,
    y_value DOUBLE NOT NULL,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    data_label VARCHAR(100) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_binomial_output_point (output_id, curve_type, point_number),
    CONSTRAINT fk_binomial_output_item FOREIGN KEY (output_id)
        REFERENCES project_well_productivity_binomial_output (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS project_well_productivity_binomial_ipr_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    output_id BIGINT NOT NULL,
    curve_number INT NOT NULL,
    point_number INT NOT NULL,
    gas_production DOUBLE NOT NULL,
    bottom_hole_flowing_pressure DOUBLE NOT NULL,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    data_label VARCHAR(100) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_binomial_ipr_point (output_id, curve_number, point_number),
    CONSTRAINT fk_binomial_ipr_item FOREIGN KEY (output_id)
        REFERENCES project_well_productivity_binomial_output (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
