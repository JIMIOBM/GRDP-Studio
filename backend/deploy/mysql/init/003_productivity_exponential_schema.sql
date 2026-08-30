CREATE TABLE IF NOT EXISTS project_well_productivity_exponential_output (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '指数式结果编号',
    test_id BIGINT NOT NULL COMMENT '所属试井主表id',
    pressure_method VARCHAR(32) NOT NULL COMMENT 'pseudo-pressure拟压力/pressure-squared压力平方/pressure压力法',
    productivity_coefficient DOUBLE NOT NULL COMMENT '指数式产能系数C',
    productivity_exponent DOUBLE NOT NULL COMMENT '指数式产能指数n',
    open_flow_capacity DOUBLE NOT NULL COMMENT '无阻流量，10^4m3/d',
    r_squared DOUBLE NULL COMMENT '拟合优度R平方；一点法可为空',
    reliability_description VARCHAR(255) NULL COMMENT '可靠性说明；无结果时为空',
    calculated_at DATETIME(3) NOT NULL COMMENT '实际计算时间，由应用传入',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '数据库最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_prod_exp_method (test_id, pressure_method),
    CONSTRAINT fk_prod_exp_test FOREIGN KEY (test_id)
        REFERENCES project_well_productivity_test (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_prod_exp_pressure_method CHECK (
        pressure_method IN ('pseudo-pressure', 'pressure-squared', 'pressure')
    ),
    CONSTRAINT chk_prod_exp_coefficient CHECK (productivity_coefficient > 0),
    CONSTRAINT chk_prod_exp_exponent CHECK (productivity_exponent > 0),
    CONSTRAINT chk_prod_exp_aof CHECK (open_flow_capacity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='产能试井指数式结果';

CREATE TABLE IF NOT EXISTS project_well_productivity_exponential_output_item (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '指数式分析图点编号',
    output_id BIGINT NOT NULL COMMENT '所属指数式结果id',
    curve_type VARCHAR(32) NOT NULL COMMENT 'analysis分析测点/regression拟合曲线/transient辅助曲线',
    point_number INT NOT NULL COMMENT '曲线内点序号，从1开始',
    source_point_number INT NULL COMMENT '来源导入测点序号；拟合点为空',
    x_value DOUBLE NOT NULL COMMENT '原始横坐标流量，10^4m3/d；不是对数值',
    y_value DOUBLE NOT NULL COMMENT '原始纵坐标压力函数差；不是对数值',
    is_deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否排除，0否/1是',
    data_label VARCHAR(255) NULL COMMENT '点标签；没有标签时为空',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本行插入时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_prod_exp_curve_point (output_id, curve_type, point_number),
    CONSTRAINT fk_prod_exp_item_output FOREIGN KEY (output_id)
        REFERENCES project_well_productivity_exponential_output (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_prod_exp_curve_type CHECK (curve_type IN ('analysis', 'regression', 'transient')),
    CONSTRAINT chk_prod_exp_point_no CHECK (point_number > 0),
    CONSTRAINT chk_prod_exp_source_point CHECK (source_point_number IS NULL OR source_point_number > 0),
    CONSTRAINT chk_prod_exp_item_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='产能试井指数式分析图数据点';

CREATE TABLE IF NOT EXISTS project_well_productivity_exponential_ipr_item (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '指数式IPR点编号',
    output_id BIGINT NOT NULL COMMENT '所属指数式结果id',
    curve_number INT NOT NULL COMMENT '结果内IPR曲线编号，从1开始',
    formation_pressure DOUBLE NOT NULL COMMENT '该曲线对应的地层压力，MPa',
    point_number INT NOT NULL COMMENT '曲线内点序号，从1开始',
    gas_production DOUBLE NOT NULL COMMENT '横坐标流量，10^4m3/d；注采由主表区分',
    bottom_hole_flowing_pressure DOUBLE NOT NULL COMMENT '井底流压，MPa',
    is_deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否排除，0否/1是',
    data_label VARCHAR(255) NULL COMMENT '点标签；没有标签时为空',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本行插入时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_prod_exp_ipr_point (output_id, curve_number, point_number),
    CONSTRAINT fk_prod_exp_ipr_output FOREIGN KEY (output_id)
        REFERENCES project_well_productivity_exponential_output (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_prod_exp_ipr_curve CHECK (curve_number > 0),
    CONSTRAINT chk_prod_exp_ipr_point CHECK (point_number > 0),
    CONSTRAINT chk_prod_exp_ipr_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='产能试井指数式IPR曲线数据点';
