-- Extend the existing productivity output table so one saved test can contain
-- either binomial (A/B) or exponential (C/n) calculation results.
SET @result_type_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'project_well_productivity_binomial_output'
      AND COLUMN_NAME = 'result_type'
);
SET @result_type_ddl = IF(
    @result_type_exists = 0,
    'ALTER TABLE project_well_productivity_binomial_output ADD COLUMN result_type ENUM(''binomial'',''exponential'') NOT NULL DEFAULT ''binomial'' AFTER pressure_method',
    'SELECT 1'
);
PREPARE result_type_statement FROM @result_type_ddl;
EXECUTE result_type_statement;
DEALLOCATE PREPARE result_type_statement;

SET @coefficient_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'project_well_productivity_binomial_output'
      AND COLUMN_NAME = 'productivity_coefficient'
);
SET @coefficient_ddl = IF(
    @coefficient_exists = 0,
    'ALTER TABLE project_well_productivity_binomial_output ADD COLUMN productivity_coefficient DOUBLE NULL AFTER non_darcy_seepage_coefficient',
    'SELECT 1'
);
PREPARE coefficient_statement FROM @coefficient_ddl;
EXECUTE coefficient_statement;
DEALLOCATE PREPARE coefficient_statement;

SET @exponent_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'project_well_productivity_binomial_output'
      AND COLUMN_NAME = 'productivity_exponent'
);
SET @exponent_ddl = IF(
    @exponent_exists = 0,
    'ALTER TABLE project_well_productivity_binomial_output ADD COLUMN productivity_exponent DOUBLE NULL AFTER productivity_coefficient',
    'SELECT 1'
);
PREPARE exponent_statement FROM @exponent_ddl;
EXECUTE exponent_statement;
DEALLOCATE PREPARE exponent_statement;

ALTER TABLE project_well_productivity_binomial_output
    MODIFY COLUMN darcy_seepage_coefficient DOUBLE NULL,
    MODIFY COLUMN non_darcy_seepage_coefficient DOUBLE NULL;
