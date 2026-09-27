SET @grdp_eclipse_inspection_column = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE software_integration_model_version ADD COLUMN inspection_json LONGTEXT NULL AFTER studies_json',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'software_integration_model_version'
    AND column_name = 'inspection_json'
);
PREPARE grdp_eclipse_inspection_column FROM @grdp_eclipse_inspection_column;
EXECUTE grdp_eclipse_inspection_column;
DEALLOCATE PREPARE grdp_eclipse_inspection_column;
