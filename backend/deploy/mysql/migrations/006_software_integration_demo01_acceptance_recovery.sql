SET @grdp_add_acceptance_uncertain_at = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE software_integration_run ADD COLUMN acceptance_uncertain_at DATETIME(3) NULL AFTER generation_id',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'software_integration_run'
    AND column_name = 'acceptance_uncertain_at'
);
PREPARE grdp_acceptance_recovery_column FROM @grdp_add_acceptance_uncertain_at;
EXECUTE grdp_acceptance_recovery_column;
DEALLOCATE PREPARE grdp_acceptance_recovery_column;

SET @grdp_add_acceptance_recovery_deadline_at = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE software_integration_run ADD COLUMN acceptance_recovery_deadline_at DATETIME(3) NULL AFTER acceptance_uncertain_at',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'software_integration_run'
    AND column_name = 'acceptance_recovery_deadline_at'
);
PREPARE grdp_acceptance_recovery_column FROM @grdp_add_acceptance_recovery_deadline_at;
EXECUTE grdp_acceptance_recovery_column;
DEALLOCATE PREPARE grdp_acceptance_recovery_column;
