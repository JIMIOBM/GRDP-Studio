-- Software integration schema is also created by SoftwareIntegrationSchemaInitializer for existing development volumes.
-- Keep this migration for controlled production deployment.
CREATE TABLE IF NOT EXISTS software_integration_project (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, description VARCHAR(500), created_by VARCHAR(100) NOT NULL,
  created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, deleted_at DATETIME(3),
  UNIQUE KEY uk_software_integration_project_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS software_integration_model (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL, name VARCHAR(255) NOT NULL, simulator_type VARCHAR(50) NOT NULL,
  created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, deleted_at DATETIME(3),
  UNIQUE KEY uk_software_integration_model_name (project_id, name), KEY idx_software_integration_model_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS software_integration_model_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, model_id BIGINT NOT NULL, version_no INT NOT NULL, original_name VARCHAR(255) NOT NULL,
  storage_key VARCHAR(1024) NOT NULL, sha256 CHAR(64) NOT NULL, size_bytes BIGINT NOT NULL, status VARCHAR(50) NOT NULL,
  validation_message VARCHAR(1000), studies_json TEXT, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_software_integration_model_version (model_id, version_no), KEY idx_software_integration_model_version_model (model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
