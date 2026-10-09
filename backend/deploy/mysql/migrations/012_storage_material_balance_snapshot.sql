CREATE TABLE IF NOT EXISTS storage_material_balance_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  gas_reservoir_id BIGINT NOT NULL,
  storage_id BIGINT NOT NULL,
  selected_well_ids_json LONGTEXT NOT NULL,
  result_json LONGTEXT NOT NULL,
  saved_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_storage_material_balance_scope (project_id, gas_reservoir_id, storage_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
