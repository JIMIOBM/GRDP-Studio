-- 储气库级地面管网拓扑。服务启动时会幂等创建同一张表，此文件用于部署审计和手工初始化。
CREATE TABLE IF NOT EXISTS project_storage_network_topology (
  storage_id BIGINT NOT NULL COMMENT '所属储气库，关联project_storage.id',
  revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  graph_json JSON NOT NULL COMMENT '节点、管段及画布布局快照',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(storage_id),
  CONSTRAINT fk_storage_network_topology_storage FOREIGN KEY(storage_id)
    REFERENCES project_storage(id) ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='储气库地面管网拓扑';
