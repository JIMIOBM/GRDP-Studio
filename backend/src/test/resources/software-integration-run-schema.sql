CREATE TABLE IF NOT EXISTS software_integration_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL,
  model_id BIGINT NOT NULL,
  model_version_id BIGINT NOT NULL,
  study_name VARCHAR(255) NOT NULL,
  run_type VARCHAR(20) NOT NULL,
  parameters_json CLOB NOT NULL,
  status VARCHAR(40) NOT NULL,
  status_version INT NOT NULL DEFAULT 0,
  timeout_seconds INT NOT NULL,
  dispatcher_id VARCHAR(64),
  worker_id VARCHAR(128),
  generation_id VARCHAR(128),
  acceptance_uncertain_at TIMESTAMP,
  acceptance_recovery_deadline_at TIMESTAMP,
  last_worker_sequence BIGINT NOT NULL DEFAULT 0,
  cancellation_reason VARCHAR(20),
  created_at TIMESTAMP NOT NULL,
  queued_at TIMESTAMP,
  claimed_at TIMESTAMP,
  started_at TIMESTAMP,
  deadline_at TIMESTAMP,
  finished_at TIMESTAMP,
  elapsed_millis BIGINT,
  result_contract VARCHAR(64),
  result_json CLOB,
  error_category VARCHAR(64),
  error_code VARCHAR(100),
  error_json CLOB,
  cleanup_json CLOB,
  artifact_manifest_key VARCHAR(1024),
  created_by VARCHAR(100) NOT NULL,
  updated_by VARCHAR(100) NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  active_slot TINYINT GENERATED ALWAYS AS (
    CASE WHEN status IN ('CLAIMED','PREPARING','RUNNING_NODAL','RUNNING_PROFILE','COLLECTING','CANCEL_REQUESTED') THEN 1 ELSE NULL END
  ),
  CONSTRAINT uk_software_integration_run_active_slot UNIQUE (active_slot)
);

CREATE TABLE IF NOT EXISTS software_integration_run_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  event_sequence BIGINT NOT NULL,
  worker_sequence BIGINT,
  event_type VARCHAR(64) NOT NULL,
  status VARCHAR(40),
  message VARCHAR(1000),
  error_json CLOB,
  occurred_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_software_integration_run_event_sequence UNIQUE (run_id, event_sequence),
  CONSTRAINT uk_software_integration_run_worker_sequence UNIQUE (run_id, worker_sequence)
);

CREATE TABLE IF NOT EXISTS software_integration_artifact (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  artifact_name VARCHAR(512) NOT NULL,
  artifact_type VARCHAR(64) NOT NULL,
  content_type VARCHAR(255),
  storage_key VARCHAR(1024) NOT NULL,
  size_bytes BIGINT NOT NULL,
  sha256 CHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP,
  CONSTRAINT uk_software_integration_artifact_name UNIQUE (run_id, artifact_name)
);
