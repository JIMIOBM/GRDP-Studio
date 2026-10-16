package com.grdp.studio.predictionresult.service;

import tools.jackson.databind.ObjectMapper;
import com.grdp.studio.predictionresult.dto.PredictionResultDtos.ResultSummary;
import com.grdp.studio.predictionresult.dto.PredictionResultDtos.SaveRequest;
import com.grdp.studio.predictionresult.dto.PredictionResultDtos.SavedSummary;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 井组动态预测结果留存的存储服务。
 * 每次预测（Calc 成功并确认新曲线）后由前端调用一次；同一任务重算按覆盖处理，
 * 只保留该任务最近一次的结果。历史任务不做批量迁移。
 */
@Service
public class PredictionResultStorageService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PredictionResultStorageService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private void requireScope(Long projectId, Long gasReservoirId, Long storageId) {
        // 储气库可选；提供时与损耗记录一致地核对归属，避免跨项目写入。
        if (storageId != null && storageId > 0) {
            StorageCatalogService.requireScope(jdbc, projectId, gasReservoirId, storageId);
            return;
        }
        if (projectId == null || projectId <= 0 || gasReservoirId == null || gasReservoirId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少项目或气藏范围");
        }
    }

    @Transactional
    public SavedSummary save(SaveRequest request) {
        requireScope(request.projectId(), request.gasReservoirId(), request.storageId());
        String wellNamesJson = toJson(request.wellNames(), "井名列表");
        String pointsJson = toJson(request.points(), "预测结果");
        String requestJson = request.request() == null || request.request().isEmpty()
                ? null : toJson(request.request(), "计算参数");
        // 任务编号在同一项目+气藏内唯一；重算覆盖为最近一次结果，与页面"同一任务重算"语义一致。
        jdbc.update("""
                INSERT INTO project_prediction_result
                  (project_id, gas_reservoir_id, storage_id, prediction_id, prediction_name, mode,
                   well_names, start_date, end_date, point_count, points_json, request_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE prediction_name=VALUES(prediction_name), mode=VALUES(mode),
                  well_names=VALUES(well_names), start_date=VALUES(start_date), end_date=VALUES(end_date),
                  point_count=VALUES(point_count), points_json=VALUES(points_json),
                  request_json=VALUES(request_json), calculated_at=CURRENT_TIMESTAMP(3)
                """, request.projectId(), request.gasReservoirId(), request.storageId(),
                request.predictionId(), request.predictionName(), request.mode(),
                wellNamesJson, request.startDate(), request.endDate(), request.points().size(),
                pointsJson, requestJson);
        try {
            return jdbc.queryForObject("""
                    SELECT id, prediction_id, prediction_name, point_count, calculated_at
                    FROM project_prediction_result
                    WHERE project_id=? AND gas_reservoir_id=? AND prediction_id=?
                    """, (rs, row) -> new SavedSummary(rs.getLong(1), rs.getLong(2), rs.getString(3),
                    rs.getInt(4), rs.getTimestamp(5).toLocalDateTime()),
                    request.projectId(), request.gasReservoirId(), request.predictionId());
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "结果留存失败：记录未写入");
        }
    }

    /**
     * 删除留存记录（幂等：记录不存在时按已删除处理，返回 0）。
     * 页面的"删除预测记录"会先删除原平台任务、再调用本接口清理留存，两步不一致时不阻断清理。
     */
    @Transactional
    public int delete(long projectId, long gasReservoirId, Long storageId, long predictionId) {
        requireScope(projectId, gasReservoirId, storageId);
        if (predictionId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "predictionId 必须为正整数");
        }
        if (storageId != null && storageId > 0) {
            return jdbc.update("""
                    DELETE FROM project_prediction_result
                    WHERE project_id=? AND gas_reservoir_id=? AND storage_id=? AND prediction_id=?
                    """, projectId, gasReservoirId, storageId, predictionId);
        }
        return jdbc.update("""
                DELETE FROM project_prediction_result
                WHERE project_id=? AND gas_reservoir_id=? AND prediction_id=?
                """, projectId, gasReservoirId, predictionId);
    }

    /** 已留存记录摘要，按最近保存时间倒序；供后续查询/导出使用。 */
    public List<ResultSummary> list(long projectId, long gasReservoirId, Long storageId) {
        requireScope(projectId, gasReservoirId, storageId);
        if (storageId != null && storageId > 0) {
            return jdbc.query("""
                    SELECT id, prediction_id, prediction_name, mode, point_count,
                           start_date, end_date, calculated_at
                    FROM project_prediction_result
                    WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?
                    ORDER BY calculated_at DESC, id DESC
                    """, (rs, row) -> summary(rs), projectId, gasReservoirId, storageId);
        }
        return jdbc.query("""
                SELECT id, prediction_id, prediction_name, mode, point_count,
                       start_date, end_date, calculated_at
                FROM project_prediction_result
                WHERE project_id=? AND gas_reservoir_id=?
                ORDER BY calculated_at DESC, id DESC
                """, (rs, row) -> summary(rs), projectId, gasReservoirId);
    }

    private ResultSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ResultSummary(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getDate(6).toLocalDate(), rs.getDate(7).toLocalDate(),
                rs.getTimestamp(8).toLocalDateTime());
    }

    private String toJson(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, label + "序列化失败", error);
        }
    }
}
