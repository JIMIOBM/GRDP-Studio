-- 单井管束能力 / MySQL 8.0+，在已有业务数据库执行。
-- 本模块唯一建表文件；只创建最终使用的八张表，不创建业务井或示例气样。
-- 井身份引用数据管理 project_well_heads(id)，每井唯一拓扑、组分PVT、温度模型及可编辑输入。
-- 所有压力为绝压；API字段后缀标明单位，求解器内部使用SI。标准状态随边界输入保存。
-- boundary.cases[{id,operatingAt,nodes}] 保存全部工况；activeCaseId只记录当前编辑行。
-- 工况时间 YYYY/MM/DD HH:mm，自由输入，保存时校验，合法值存yyyy-MM-ddTHH:mm，允许任意分钟。
-- nodes按nodeId存supplyRate10k/withdrawalRate10k/pressureMpa/temperatureC，不保存数据用途。
-- 拓扑是几何和设备参数唯一编辑来源；批量input_json/topology_json保存实际计算使用的不可变快照。
-- pipeline_batch_run.result_json记录全部工况的pipes/equipment/hydrate和计算状态，失败工况不补零。
-- constraints.waterState仅为available或unknown；水合物采用明确版本的经验模型，纯水且未加抑制剂。
-- 水合物按沿程真实采样点的同点压力计算平衡温度并比较实际温度，完整覆盖时保存最小温度裕度。
-- 温度settings_json按edgeId保存材料层、环境、PVT引用、λ；密度/Cp/黏度由统一物性服务计算。
-- 气体组成composition_json为[{code,moleFraction}]；Z/Cp检查点不作为后续计算前置条件。
-- 修改来源后须重算；算法版本不一致的历史批量结果不作为当前有效结果。

CREATE TABLE IF NOT EXISTS pipeline_model (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    well_id BIGINT NOT NULL COMMENT '引用数据管理的 project_well_heads.id',
    revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    topology_revision INT NOT NULL DEFAULT 0 COMMENT '输入快照对应的已保存拓扑版本，0表示尚未关联',
    schema_version INT NOT NULL DEFAULT 1,
    input_json JSON NOT NULL COMMENT '全部边界工况、计算参数与实际来源快照，含水合物水条件',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pipeline_model_well (well_id),
    CONSTRAINT fk_pipeline_model_well FOREIGN KEY (well_id) REFERENCES project_well_heads(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管束能力计算模型';

CREATE TABLE IF NOT EXISTS pipeline_topology (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    well_id BIGINT NOT NULL COMMENT '引用数据管理的 project_well_heads.id',
    revision INT NOT NULL DEFAULT 1,
    graph_json JSON NOT NULL COMMENT '完整编辑快照，含边界设置与节点位置，事务内与节点/管段同步',
    layout_json JSON NOT NULL COMMENT '画布平移、缩放；不参与水力计算',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pipeline_topology_well (well_id),
    CONSTRAINT fk_pipeline_topology_well FOREIGN KEY (well_id) REFERENCES project_well_heads(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管网拓扑模型';

CREATE TABLE IF NOT EXISTS pipeline_topology_node (
    topology_id BIGINT NOT NULL,
    node_key VARCHAR(64) NOT NULL,
    node_type VARCHAR(30) NOT NULL,
    name VARCHAR(100) NOT NULL,
    parameters_json JSON NOT NULL COMMENT '高程及设备参数',
    PRIMARY KEY(topology_id,node_key),
    CONSTRAINT fk_topology_node_model FOREIGN KEY(topology_id) REFERENCES pipeline_topology(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管网节点工程数据';

CREATE TABLE IF NOT EXISTS pipeline_topology_edge (
    topology_id BIGINT NOT NULL,
    edge_key VARCHAR(64) NOT NULL,
    source_key VARCHAR(64) NOT NULL,
    target_key VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    parameters_json JSON NOT NULL COMMENT '管段几何及传热参数，PVT由井级模型统一提供',
    PRIMARY KEY(topology_id,edge_key),
    CONSTRAINT fk_topology_edge_source FOREIGN KEY(topology_id,source_key) REFERENCES pipeline_topology_node(topology_id,node_key),
    CONSTRAINT fk_topology_edge_target FOREIGN KEY(topology_id,target_key) REFERENCES pipeline_topology_node(topology_id,node_key),
    CONSTRAINT chk_topology_edge CHECK(source_key <> target_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管网有向管段';

CREATE TABLE IF NOT EXISTS pipeline_temperature (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    well_id BIGINT NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    settings_json JSON NOT NULL COMMENT '温度设置及迭代参数；gasConductivityWmK为每段给定λ；innerDiameterMm仅为内壁页试算内径',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pipeline_temperature_well (well_id),
    CONSTRAINT fk_pipeline_temperature_well FOREIGN KEY (well_id) REFERENCES project_well_heads(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单井管道温度模型';

CREATE TABLE IF NOT EXISTS pipeline_pvt_model (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    well_id BIGINT NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    pvt_name VARCHAR(100) NOT NULL,
    method VARCHAR(8) NOT NULL,
    composition_json JSON NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pipeline_pvt_model_well (well_id),
    CONSTRAINT fk_pipeline_pvt_model_well FOREIGN KEY(well_id) REFERENCES project_well_heads(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单井组分PVT及物性方法';

CREATE TABLE IF NOT EXISTS pipeline_gas_model (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    well_id BIGINT NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    settings_json JSON NOT NULL COMMENT 'PVT引用、组成版本、PR/SRK/BWRS方法、Z/Cp单点检查结果',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pipeline_gas_model_well (well_id),
    CONSTRAINT fk_pipeline_gas_model_well FOREIGN KEY(well_id) REFERENCES project_well_heads(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单井共享气体物性模型';

CREATE TABLE IF NOT EXISTS pipeline_batch_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    well_id BIGINT NOT NULL,
    model_revision INT NOT NULL,
    topology_revision INT NOT NULL,
    algorithm_version VARCHAR(50) NOT NULL,
    input_json JSON NOT NULL,
    topology_json JSON NOT NULL,
    result_json JSON NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_pipeline_batch_well (well_id, id),
    CONSTRAINT fk_pipeline_batch_well FOREIGN KEY (well_id) REFERENCES project_well_heads(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全工况管网计算批次';

-- ==================== 回退整个管束能力模块（默认全部注释） ====================
-- 取消下面各行注释将删除本模块全部输入和计算结果，保留数据管理中的井及PVT数据。
-- 按依赖顺序执行，无需关闭外键检查。
-- DROP TABLE IF EXISTS pipeline_batch_run;
-- DROP TABLE IF EXISTS pipeline_gas_model;
-- DROP TABLE IF EXISTS pipeline_pvt_model;
-- DROP TABLE IF EXISTS pipeline_temperature;
-- DROP TABLE IF EXISTS pipeline_topology_edge;
-- DROP TABLE IF EXISTS pipeline_topology_node;
-- DROP TABLE IF EXISTS pipeline_topology;
-- DROP TABLE IF EXISTS pipeline_model;
