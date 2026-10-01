package com.grdp.studio.storagenetwork;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 幂等创建库级地面管网拓扑表；项目基础表尚未初始化时不抢先建表。 */
@Component
public class StorageNetworkSchemaInitializer {
    private final JdbcTemplate jdbc;

    public StorageNetworkSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void initialize() {
        Integer storages = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='project_storage'
                """, Integer.class);
        if (storages == null || storages == 0) return;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS project_storage_network_topology (
                  storage_id BIGINT NOT NULL COMMENT '所属储气库，关联project_storage.id',
                  revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
                  graph_json JSON NOT NULL COMMENT '节点、管段及画布布局快照',
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY(storage_id),
                  CONSTRAINT fk_storage_network_topology_storage FOREIGN KEY(storage_id)
                    REFERENCES project_storage(id) ON UPDATE CASCADE ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='储气库地面管网拓扑'
                """);
    }
}
