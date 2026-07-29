CREATE DATABASE IF NOT EXISTS grdp_studio
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE grdp_studio;

CREATE TABLE IF NOT EXISTS t_project (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    name VARCHAR(100) NOT NULL COMMENT '项目名称',
    description VARCHAR(500) NULL COMMENT '项目描述',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 否，1 是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_name_deleted (name, deleted),
    KEY idx_project_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO t_project (name, description)
SELECT '示例项目', 'GRDP Studio 后端初始化数据'
WHERE NOT EXISTS (
    SELECT 1 FROM t_project WHERE name = '示例项目' AND deleted = 0
);
