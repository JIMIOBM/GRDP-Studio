-- Storage catalog required by the reservoir workspace and storage-level features.
-- This migration creates schema only; it does not add or alter business records.
CREATE TABLE IF NOT EXISTS project_storage (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  gas_reservoir_id BIGINT NOT NULL,
  storage_name VARCHAR(100) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_project_storage_scope (project_id, gas_reservoir_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='储气库目录';

CREATE TABLE IF NOT EXISTS project_storage_well (
  storage_id BIGINT NOT NULL,
  well_id BIGINT NOT NULL,
  PRIMARY KEY (storage_id, well_id),
  KEY idx_project_storage_well_well (well_id),
  CONSTRAINT fk_project_storage_well_storage
    FOREIGN KEY (storage_id) REFERENCES project_storage(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='储气库单井关联';
