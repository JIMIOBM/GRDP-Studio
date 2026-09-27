SET @grdp_upgrade_network_active_slot = (
  SELECT IF(
    COUNT(*) = 0 OR UPPER(COALESCE(MAX(generation_expression), '')) LIKE '%RUNNING_NETWORK%',
    'SELECT 1',
    'ALTER TABLE software_integration_run MODIFY COLUMN active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status IN (''CLAIMED'',''PREPARING'',''RUNNING_NODAL'',''RUNNING_PROFILE'',''RUNNING_NETWORK'',''COLLECTING'',''CANCEL_REQUESTED'') THEN 1 ELSE NULL END) STORED')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'software_integration_run'
    AND column_name = 'active_slot'
);
PREPARE grdp_network_active_slot_column FROM @grdp_upgrade_network_active_slot;
EXECUTE grdp_network_active_slot_column;
DEALLOCATE PREPARE grdp_network_active_slot_column;

SET @grdp_add_model_version_kind = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE software_integration_model_version ADD COLUMN model_kind VARCHAR(50) NULL AFTER status',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'software_integration_model_version'
    AND column_name = 'model_kind'
);
PREPARE grdp_model_version_kind_column FROM @grdp_add_model_version_kind;
EXECUTE grdp_model_version_kind_column;
DEALLOCATE PREPARE grdp_model_version_kind_column;

UPDATE software_integration_model_version v
JOIN software_integration_model m ON m.id = v.model_id
SET v.model_kind = CASE m.simulator_type
  WHEN 'PIPESIM_NETWORK' THEN 'network'
  WHEN 'PIPESIM_WELL' THEN 'legacy_well'
  ELSE v.model_kind
END
WHERE v.model_kind IS NULL
  AND v.status = 'READY'
  AND m.simulator_type IN ('PIPESIM_NETWORK', 'PIPESIM_WELL');
