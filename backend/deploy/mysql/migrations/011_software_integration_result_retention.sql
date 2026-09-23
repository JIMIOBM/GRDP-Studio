SET @grdp_result_retention_column = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE software_integration_run ADD COLUMN result_expires_at DATETIME(3) NULL AFTER result_json',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'software_integration_run'
    AND column_name = 'result_expires_at'
);
PREPARE grdp_result_retention_column FROM @grdp_result_retention_column;
EXECUTE grdp_result_retention_column;
DEALLOCATE PREPARE grdp_result_retention_column;
