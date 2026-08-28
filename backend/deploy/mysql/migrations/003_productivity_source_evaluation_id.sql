-- 原平台 ProductivityEvaluation 主键；每种压力形式分别保存，不属于本地试井主表。
-- 使用 information_schema 判断，使脚本在已经升级过的数据库中重复执行也不会报错。
SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'project_well_productivity_binomial_output'
      AND COLUMN_NAME = 'source_evaluation_id'
);
SET @column_sql = IF(
    @column_exists = 0,
    'ALTER TABLE project_well_productivity_binomial_output ADD COLUMN source_evaluation_id BIGINT NULL COMMENT ''原平台 ProductivityEvaluation.id'' AFTER pressure_method',
    'SELECT 1'
);
PREPARE column_statement FROM @column_sql;
EXECUTE column_statement;
DEALLOCATE PREPARE column_statement;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'project_well_productivity_binomial_output'
      AND INDEX_NAME = 'idx_prod_binomial_source_evaluation'
);
SET @index_sql = IF(
    @index_exists = 0,
    'CREATE INDEX idx_prod_binomial_source_evaluation ON project_well_productivity_binomial_output (source_evaluation_id)',
    'SELECT 1'
);
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;
