SET @grdp_upgrade_result_json_precision = (
  SELECT IF(COUNT(*) = 0 OR LOWER(MAX(data_type)) = 'longtext',
    'SELECT 1',
    'ALTER TABLE software_integration_run MODIFY COLUMN result_json LONGTEXT NULL')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'software_integration_run'
    AND column_name = 'result_json'
);
PREPARE grdp_result_json_precision_column FROM @grdp_upgrade_result_json_precision;
EXECUTE grdp_result_json_precision_column;
DEALLOCATE PREPARE grdp_result_json_precision_column;
