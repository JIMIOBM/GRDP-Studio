DROP TABLE IF EXISTS t_project;

CREATE TABLE t_project (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_project_name_deleted UNIQUE (name, deleted)
);
