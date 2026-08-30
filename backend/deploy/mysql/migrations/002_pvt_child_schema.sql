-- GRDP single-well multi-PVT child tables.
-- Prerequisite: project_well_pvt already exists in the selected database.

CREATE TABLE IF NOT EXISTS project_well_pvt_gas_input (
    id BIGINT NOT NULL AUTO_INCREMENT,
    pvt_id BIGINT NOT NULL COMMENT '所属PVT主记录ID',
    gas_type VARCHAR(32) NOT NULL COMMENT '干气/湿气/凝析气',
    specific_gravity DOUBLE NOT NULL COMMENT '天然气相对密度',
    hydrogen_sulfide DOUBLE NOT NULL DEFAULT 0 COMMENT 'H2S摩尔百分含量',
    carbon_dioxide DOUBLE NOT NULL DEFAULT 0 COMMENT 'CO2摩尔百分含量',
    nitrogen DOUBLE NOT NULL DEFAULT 0 COMMENT 'N2摩尔百分含量',
    condensate_oil_density DOUBLE NULL COMMENT '凝析油标准状况密度',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pvt_gas_input (pvt_id),
    CONSTRAINT fk_pvt_gas_input_pvt
        FOREIGN KEY (pvt_id) REFERENCES project_well_pvt (id)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci COMMENT='单井PVT天然气基础输入';

CREATE TABLE IF NOT EXISTS project_well_pvt_water_input (
    id BIGINT NOT NULL AUTO_INCREMENT,
    pvt_id BIGINT NOT NULL COMMENT '所属PVT主记录ID',
    formation_pressure DOUBLE NOT NULL COMMENT '地层压力',
    formation_temperature DOUBLE NOT NULL COMMENT '地层温度',
    salinity DOUBLE NOT NULL COMMENT '地层水矿化度',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pvt_water_input (pvt_id),
    CONSTRAINT fk_pvt_water_input_pvt
        FOREIGN KEY (pvt_id) REFERENCES project_well_pvt (id)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci COMMENT='单井PVT地层水基础输入';

CREATE TABLE IF NOT EXISTS project_well_pvt_rock_input (
    id BIGINT NOT NULL AUTO_INCREMENT,
    pvt_id BIGINT NOT NULL COMMENT '所属PVT主记录ID',
    porosity DOUBLE NOT NULL COMMENT '岩石孔隙度百分数',
    rock_type VARCHAR(32) NULL COMMENT '岩石类型',
    calculation_method VARCHAR(64) NULL COMMENT '计算方法',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pvt_rock_input (pvt_id),
    CONSTRAINT fk_pvt_rock_input_pvt
        FOREIGN KEY (pvt_id) REFERENCES project_well_pvt (id)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci COMMENT='单井PVT岩石基础输入';

CREATE TABLE IF NOT EXISTS project_well_pvt_settings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    pvt_id BIGINT NOT NULL COMMENT '所属PVT主记录ID',
    property_kind VARCHAR(16) NOT NULL COMMENT 'gas/water/rock',
    settings_json JSON NOT NULL COMMENT '计算方法和界面设置',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pvt_settings_kind (pvt_id, property_kind),
    CONSTRAINT fk_pvt_settings_pvt
        FOREIGN KEY (pvt_id) REFERENCES project_well_pvt (id)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci COMMENT='单井PVT算法设置';

CREATE TABLE IF NOT EXISTS project_well_pvt_gas_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    pvt_id BIGINT NOT NULL COMMENT '所属PVT主记录ID',
    point_no INT NOT NULL COMMENT '曲线点序号',
    pressure DOUBLE NOT NULL COMMENT '压力MPa',
    temperature DOUBLE NOT NULL COMMENT '温度摄氏度',
    deviation_factor DOUBLE NULL COMMENT '天然气偏差系数',
    pseudo_pressure DOUBLE NULL COMMENT '气体拟压力',
    volume_factor DOUBLE NULL COMMENT '天然气体积系数',
    density DOUBLE NULL COMMENT '天然气密度',
    compressibility DOUBLE NULL COMMENT '天然气压缩系数',
    viscosity DOUBLE NULL COMMENT '天然气粘度',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pvt_gas_result_point (pvt_id, point_no),
    KEY idx_pvt_gas_result_pressure (pvt_id, pressure),
    CONSTRAINT fk_pvt_gas_result_pvt
        FOREIGN KEY (pvt_id) REFERENCES project_well_pvt (id)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci COMMENT='单井天然气PVT结果点';

CREATE TABLE IF NOT EXISTS project_well_pvt_water_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    pvt_id BIGINT NOT NULL COMMENT '所属PVT主记录ID',
    point_no INT NOT NULL COMMENT '曲线点序号',
    pressure DOUBLE NOT NULL COMMENT '压力MPa',
    temperature DOUBLE NOT NULL COMMENT '温度摄氏度',
    salinity DOUBLE NOT NULL COMMENT '矿化度mg/L',
    gas_solubility DOUBLE NULL COMMENT '天然气在水中的溶解度',
    volume_factor DOUBLE NULL COMMENT '地层水体积系数',
    density DOUBLE NULL COMMENT '地层水密度',
    isothermal_compressibility DOUBLE NULL COMMENT '等温压缩系数',
    viscosity DOUBLE NULL COMMENT '地层水粘度',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pvt_water_result_point (pvt_id, point_no),
    KEY idx_pvt_water_result_pressure (pvt_id, pressure),
    CONSTRAINT fk_pvt_water_result_pvt
        FOREIGN KEY (pvt_id) REFERENCES project_well_pvt (id)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci COMMENT='单井地层水PVT结果点';

CREATE TABLE IF NOT EXISTS project_well_pvt_rock_result (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '岩石结果点主键',
    pvt_id BIGINT NOT NULL COMMENT '所属PVT主记录ID',
    curve_type VARCHAR(32) NOT NULL COMMENT '曲线类型：cemented/carbonate',
    point_no INT NOT NULL COMMENT '曲线数据点序号',
    porosity DECIMAL(12,6) NOT NULL COMMENT '岩石孔隙度（%）',
    compressibility_factor DECIMAL(20,12) NOT NULL COMMENT '岩石压缩系数（MPa⁻¹）',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rock_result_point (pvt_id, curve_type, point_no),
    KEY idx_rock_result_pvt (pvt_id),
    CONSTRAINT fk_rock_result_pvt
        FOREIGN KEY (pvt_id) REFERENCES project_well_pvt (id)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='单井PVT岩石性质计算结果点表';
