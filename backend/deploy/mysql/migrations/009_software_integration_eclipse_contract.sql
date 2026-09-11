SET @grdp_eclipse_study_nullable = (
  SELECT IF(
    COUNT(*) = 0 OR MAX(IS_NULLABLE) = 'YES',
    'SELECT 1',
    'ALTER TABLE software_integration_run MODIFY COLUMN study_name VARCHAR(255) NULL')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'software_integration_run'
    AND column_name = 'study_name'
);
PREPARE grdp_eclipse_study_column FROM @grdp_eclipse_study_nullable;
EXECUTE grdp_eclipse_study_column;
DEALLOCATE PREPARE grdp_eclipse_study_column;

SET @grdp_eclipse_active_slot = (
  SELECT IF(
    COUNT(*) = 0 OR UPPER(COALESCE(MAX(generation_expression), '')) LIKE '%RUNNING_ECLIPSE%',
    'SELECT 1',
    'ALTER TABLE software_integration_run MODIFY COLUMN active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status IN (''CLAIMED'',''PREPARING'',''RUNNING_NODAL'',''RUNNING_PROFILE'',''RUNNING_NETWORK'',''RUNNING_ECLIPSE'',''COLLECTING'',''CANCEL_REQUESTED'') THEN 1 ELSE NULL END) STORED')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'software_integration_run'
    AND column_name = 'active_slot'
);
PREPARE grdp_eclipse_active_slot_column FROM @grdp_eclipse_active_slot;
EXECUTE grdp_eclipse_active_slot_column;
DEALLOCATE PREPARE grdp_eclipse_active_slot_column;
