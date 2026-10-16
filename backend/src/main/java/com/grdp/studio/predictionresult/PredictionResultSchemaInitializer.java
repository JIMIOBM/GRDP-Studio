package com.grdp.studio.predictionresult;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 幂等创建井组动态预测结果留存表；项目基础表尚未初始化时不抢先建表。 */
@Component
public class PredictionResultSchemaInitializer {
    private final JdbcTemplate jdbc;

    public PredictionResultSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void initialize() {
        Integer storages = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='project_storage'
                """, Integer.class);
        if (storages == null || storages == 0) return;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS project_prediction_result (
                  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '留存记录ID',
                  project_id BIGINT NOT NULL COMMENT '项目ID（原系统）',
                  gas_reservoir_id BIGINT NOT NULL COMMENT '气藏ID',
                  storage_id BIGINT NULL COMMENT '储气库ID（可为空）',
                  prediction_id BIGINT NOT NULL COMMENT '原平台井组动态预测任务编号',
                  prediction_name VARCHAR(255) NOT NULL COMMENT '预测任务名称',
                  mode VARCHAR(32) NOT NULL COMMENT '功能模式：单目标/多目标',
                  well_names JSON NOT NULL COMMENT '参与预测的井名（JSON数组）',
                  start_date DATE NOT NULL COMMENT '预测期起始日期',
                  end_date DATE NOT NULL COMMENT '预测期结束日期',
                  point_count INT NOT NULL COMMENT '预测点数量',
                  points_json JSON NOT NULL COMMENT '逐日结果：气产量/累产气量/地层压力/采出程度',
                  request_json JSON NULL COMMENT '最近一次 Calc 提交参数快照',
                  calculated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近一次保存时间',
                  PRIMARY KEY(id),
                  UNIQUE KEY uk_prediction_result_task(project_id, gas_reservoir_id, prediction_id),
                  KEY idx_prediction_result_scope(project_id, gas_reservoir_id, storage_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='井组动态预测结果留存'
                """);
    }
}
