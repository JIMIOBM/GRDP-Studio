-- Align the existing productivity/PVT tables with the three approved design manuals.
-- Target: MySQL 8.0.21+. Run once after 002/003 migrations.
USE `database`;

-- Backfill legacy nullable snapshots from the PVT record before adding NOT NULL constraints.
UPDATE project_well_productivity_test_input input
JOIN project_well_productivity_test test_record ON test_record.id = input.test_id
LEFT JOIN project_well_pvt_gas_input gas_input ON gas_input.pvt_id = test_record.pvt_id
SET input.gas_type = COALESCE(NULLIF(TRIM(input.gas_type), ''), gas_input.gas_type),
    input.specific_gravity = COALESCE(input.specific_gravity, gas_input.specific_gravity),
    input.hydrogen_sulfide = COALESCE(input.hydrogen_sulfide, gas_input.hydrogen_sulfide, 0),
    input.carbon_dioxide = COALESCE(input.carbon_dioxide, gas_input.carbon_dioxide, 0),
    input.nitrogen = COALESCE(input.nitrogen, gas_input.nitrogen, 0);

UPDATE project_well_pvt
SET source_type = 'manual'
WHERE source_type IS NULL OR TRIM(source_type) = '';

-- PVT main table.
ALTER TABLE project_well_pvt
    DROP FOREIGN KEY fk_pvt_well,
    MODIFY source_type VARCHAR(32) NOT NULL DEFAULT 'manual',
    MODIFY last_calculated_kind VARCHAR(16) NULL,
    ADD KEY idx_pvt_well_status (well_id, status),
    ADD CONSTRAINT fk_project_well_pvt_well
        FOREIGN KEY (well_id) REFERENCES project_well_heads (id)
        ON UPDATE CASCADE ON DELETE CASCADE;

-- Productivity test main table.
ALTER TABLE project_well_productivity_test
    DROP FOREIGN KEY fk_productivity_test_project,
    DROP FOREIGN KEY fk_productivity_test_reservoir,
    DROP FOREIGN KEY fk_productivity_test_well,
    DROP FOREIGN KEY fk_productivity_test_pvt,
    DROP INDEX uk_productivity_test_no,
    DROP INDEX idx_productivity_test_scope,
    DROP INDEX fk_productivity_test_reservoir,
    DROP INDEX fk_productivity_test_pvt,
    MODIFY operation_type VARCHAR(16) NOT NULL,
    MODIFY test_method VARCHAR(32) NOT NULL,
    MODIFY well_type VARCHAR(32) NULL,
    MODIFY status VARCHAR(32) NOT NULL DEFAULT 'draft',
    ADD UNIQUE KEY uk_prod_test_identity (well_id, operation_type, test_method, test_no),
    ADD KEY idx_prod_test_scope (project_id, project_gas_reservoir_id, well_id),
    ADD KEY idx_prod_test_pvt (pvt_id),
    ADD KEY idx_prod_test_menu (well_id, operation_type, test_method, test_date),
    ADD CONSTRAINT fk_prod_test_project FOREIGN KEY (project_id)
        REFERENCES project_summaries (id) ON UPDATE CASCADE ON DELETE CASCADE,
    ADD CONSTRAINT fk_prod_test_reservoir FOREIGN KEY (project_gas_reservoir_id)
        REFERENCES project_gas_reservoir (id) ON UPDATE CASCADE ON DELETE CASCADE,
    ADD CONSTRAINT fk_prod_test_well FOREIGN KEY (well_id)
        REFERENCES project_well_heads (id) ON UPDATE CASCADE ON DELETE CASCADE,
    ADD CONSTRAINT fk_prod_test_pvt FOREIGN KEY (pvt_id)
        REFERENCES project_well_pvt (id) ON UPDATE CASCADE ON DELETE CASCADE,
    ADD CONSTRAINT chk_prod_operation_type CHECK (operation_type IN ('injection', 'production')),
    ADD CONSTRAINT chk_prod_test_method CHECK (
        test_method IN ('back-pressure', 'isochronal', 'modified-isochronal', 'one-point')
    ),
    ADD CONSTRAINT chk_prod_test_no CHECK (test_no > 0);

-- Productivity input and imported points.
ALTER TABLE project_well_productivity_test_input
    DROP FOREIGN KEY fk_productivity_input_test,
    DROP INDEX uk_productivity_input_test,
    MODIFY gas_type VARCHAR(32) NOT NULL,
    MODIFY specific_gravity DOUBLE NOT NULL,
    MODIFY hydrogen_sulfide DOUBLE NOT NULL DEFAULT 0,
    MODIFY carbon_dioxide DOUBLE NOT NULL DEFAULT 0,
    MODIFY nitrogen DOUBLE NOT NULL DEFAULT 0,
    ADD UNIQUE KEY uk_prod_test_input (test_id),
    ADD CONSTRAINT fk_prod_test_input_test FOREIGN KEY (test_id)
        REFERENCES project_well_productivity_test (id) ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE project_well_productivity_test_input_item
    DROP FOREIGN KEY fk_productivity_input_item,
    DROP INDEX uk_productivity_input_point,
    ADD UNIQUE KEY uk_prod_input_point (input_id, test_point_number),
    ADD CONSTRAINT fk_prod_input_item_input FOREIGN KEY (input_id)
        REFERENCES project_well_productivity_test_input (id) ON UPDATE CASCADE ON DELETE CASCADE,
    ADD CONSTRAINT chk_prod_input_point_no CHECK (test_point_number > 0);

-- Binomial output and chart points.
ALTER TABLE project_well_productivity_binomial_output
    DROP FOREIGN KEY fk_binomial_output_test,
    DROP INDEX uk_binomial_output_method,
    MODIFY pressure_method VARCHAR(32) NOT NULL,
    MODIFY reliability_level INT NULL,
    ADD UNIQUE KEY uk_prod_binomial_method (test_id, pressure_method),
    ADD CONSTRAINT fk_prod_binomial_test FOREIGN KEY (test_id)
        REFERENCES project_well_productivity_test (id) ON UPDATE CASCADE ON DELETE CASCADE,
    ADD CONSTRAINT chk_prod_pressure_method CHECK (
        pressure_method IN ('pseudo-pressure', 'pressure-squared', 'pressure')
    );

ALTER TABLE project_well_productivity_binomial_output_item
    DROP FOREIGN KEY fk_binomial_output_item,
    DROP INDEX uk_binomial_output_point,
    MODIFY curve_type VARCHAR(40) NOT NULL,
    MODIFY data_label VARCHAR(255) NULL,
    ADD UNIQUE KEY uk_prod_output_curve_point (output_id, curve_type, point_number),
    ADD CONSTRAINT fk_prod_output_item_output FOREIGN KEY (output_id)
        REFERENCES project_well_productivity_binomial_output (id) ON UPDATE CASCADE ON DELETE CASCADE,
    ADD CONSTRAINT chk_prod_output_point_no CHECK (point_number > 0),
    ADD CONSTRAINT chk_prod_curve_type CHECK (
        curve_type IN ('regularized', 'stable', 'regression', 'shifted-regression')
    );

ALTER TABLE project_well_productivity_binomial_ipr_item
    DROP FOREIGN KEY fk_binomial_ipr_item,
    DROP INDEX uk_binomial_ipr_point,
    MODIFY data_label VARCHAR(255) NULL,
    ADD UNIQUE KEY uk_prod_ipr_curve_point (output_id, curve_number, point_number),
    ADD CONSTRAINT fk_prod_ipr_item_output FOREIGN KEY (output_id)
        REFERENCES project_well_productivity_binomial_output (id) ON UPDATE CASCADE ON DELETE CASCADE,
    ADD CONSTRAINT chk_prod_ipr_curve_no CHECK (curve_number > 0),
    ADD CONSTRAINT chk_prod_ipr_point_no CHECK (point_number > 0);
