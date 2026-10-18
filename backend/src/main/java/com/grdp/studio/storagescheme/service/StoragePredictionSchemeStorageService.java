package com.grdp.studio.storagescheme.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.storagescheme.dto.StoragePredictionSchemeSaveRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 库级多周期预测方案存取，对应数据表 storage_prediction_scheme。 */
@Service
public class StoragePredictionSchemeStorageService {
    private static final String TABLE = "`storage_prediction_scheme`";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public StoragePredictionSchemeStorageService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> save(StoragePredictionSchemeSaveRequest req) {
        validateScope(req);
        String finalTitle = title(req.title(), req.projectId(), req.gasReservoirId(), req.storageId());
        KeyHolder key = new GeneratedKeyHolder();
        String actor = who(req.updatedBy(), req.createdBy());
        String sql = """
                INSERT INTO %s
                (project_id, gas_reservoir_id, storage_id, title, constraint_record_id,
                 original_prediction_id, well_names_json, calc_input_json, boundaries_json,
                 charts_json, forecast_start_time, revision, created_by, updated_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.formatted(TABLE);
        jdbc.update(c -> {
            PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            p.setLong(i++, req.projectId());
            p.setLong(i++, req.gasReservoirId());
            p.setLong(i++, req.storageId());
            p.setString(i++, finalTitle);
            nullableLong(p, i++, req.constraintRecordId());
            p.setLong(i++, req.originalPredictionId());
            p.setString(i++, write(req.wellNamesJson()));
            p.setString(i++, write(req.calcInputJson()));
            nullableString(p, i++, req.boundariesJson() != null ? write(req.boundariesJson()) : null);
            p.setString(i++, write(req.chartsJson()));
            nullableString(p, i++, req.forecastStartTime());
            p.setInt(i++, 1);
            p.setString(i++, actor);
            p.setString(i++, actor);
            return p;
        }, key);
        return detail(key.getKey().longValue());
    }

    @Transactional
    public Map<String, Object> update(long id, StoragePredictionSchemeSaveRequest req) {
        validateScope(req);
        Map<String, Object> existing = detail(id);
        checkSameScope(existing, req, "预测方案");
        String finalTitle = title(req.title(), req.projectId(), req.gasReservoirId(), req.storageId());
        String actor = who(req.updatedBy(), req.createdBy());
        int changed = jdbc.update(
                ("UPDATE %s SET title=?, constraint_record_id=?, original_prediction_id=?," +
                        " well_names_json=?, calc_input_json=?, boundaries_json=?, charts_json=?," +
                        " forecast_start_time=?, revision=revision+1, updated_by=?" +
                        " WHERE id=? AND deleted_at IS NULL").formatted(TABLE),
                finalTitle, req.constraintRecordId(), req.originalPredictionId(),
                write(req.wellNamesJson()), write(req.calcInputJson()),
                req.boundariesJson() != null ? write(req.boundariesJson()) : null,
                write(req.chartsJson()), req.forecastStartTime(),
                actor, id
        );
        if (changed != 1) throw new BusinessException(404, "数据库中未找到该预测方案记录，更新已取消");
        return detail(id);
    }

    public List<Map<String, Object>> list(long projectId, long gasReservoirId, long storageId) {
        return jdbc.queryForList(
                ("SELECT id, project_id AS projectId, gas_reservoir_id AS gasReservoirId," +
                        "storage_id AS storageId, title, constraint_record_id AS constraintRecordId," +
                        "original_prediction_id AS originalPredictionId," +
                        "well_names_json AS wellNamesJson, calc_input_json AS calcInputJson," +
                        "boundaries_json AS boundariesJson, charts_json AS chartsJson," +
                        "forecast_start_time AS forecastStartTime, revision," +
                        "created_by AS createdBy, updated_by AS updatedBy," +
                        "created_at AS createdAt, updated_at AS updatedAt" +
                        " FROM %s WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?" +
                        " AND deleted_at IS NULL ORDER BY updated_at DESC").formatted(TABLE),
                projectId, gasReservoirId, storageId);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                ("SELECT id, project_id AS projectId, gas_reservoir_id AS gasReservoirId," +
                        "storage_id AS storageId, title, constraint_record_id AS constraintRecordId," +
                        "original_prediction_id AS originalPredictionId," +
                        "well_names_json AS wellNamesJson, calc_input_json AS calcInputJson," +
                        "boundaries_json AS boundariesJson, charts_json AS chartsJson," +
                        "forecast_start_time AS forecastStartTime, revision," +
                        "created_by AS createdBy, updated_by AS updatedBy," +
                        "created_at AS createdAt, updated_at AS updatedAt" +
                        " FROM %s WHERE id=? AND deleted_at IS NULL").formatted(TABLE),
                id);
        if (rows.size() != 1) throw new BusinessException(404, "未找到该预测方案记录");
        return rows.getFirst();
    }

    @Transactional
    public void delete(long id, long projectId, long gasReservoirId, long storageId) {
        int changed = jdbc.update(
                ("UPDATE %s SET deleted_at=? WHERE id=? AND project_id=? AND gas_reservoir_id=?" +
                        " AND storage_id=? AND deleted_at IS NULL").formatted(TABLE),
                Timestamp.from(Instant.now()),
                id, projectId, gasReservoirId, storageId);
        if (changed != 1) throw new BusinessException(404, "未找到该预测方案记录");
    }

    private static void validateScope(StoragePredictionSchemeSaveRequest r) {
        if (r.projectId() == null || r.projectId() <= 0 ||
                r.gasReservoirId() == null || r.gasReservoirId() <= 0 ||
                r.storageId() == null || r.storageId() <= 0) {
            throw new BusinessException(400, "项目、气藏和库ID不能为空");
        }
        if (r.originalPredictionId() == null || r.originalPredictionId() <= 0) {
            throw new BusinessException(422, "缺少原平台预测任务ID");
        }
        if (r.wellNamesJson() == null || r.calcInputJson() == null || r.chartsJson() == null) {
            throw new BusinessException(422, "缺少井名单、计算参数或图表数据");
        }
    }

    private static void checkSameScope(Map<String, Object> existing, StoragePredictionSchemeSaveRequest r, String kind) {
        if (((Number) existing.get("projectId")).longValue() != r.projectId() ||
                ((Number) existing.get("gasReservoirId")).longValue() != r.gasReservoirId() ||
                ((Number) existing.get("storageId")).longValue() != r.storageId()) {
            throw new BusinessException(404, "该" + kind + "不属于当前项目、气藏或库，不允许覆盖");
        }
    }

    private String title(String requested, long projectId, long gasReservoirId, long storageId) {
        if (requested != null && !requested.trim().isEmpty()) return requested.trim();
        Long count = jdbc.queryForObject(
                ("SELECT COUNT(*) FROM %s WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?" +
                        " AND deleted_at IS NULL").formatted(TABLE),
                Long.class, projectId, gasReservoirId, storageId);
        return "预测方案" + String.valueOf((count == null ? 0 : count) + 1);
    }

    private static String who(String primary, String fallback) {
        String v = primary != null ? primary.trim() : "";
        if (v.isEmpty()) v = fallback != null ? fallback.trim() : "";
        return v.isEmpty() ? "system" : v;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(422, "JSON 序列化失败：" + e.getMessage());
        }
    }

    private static void nullableString(PreparedStatement p, int index, String v) throws java.sql.SQLException {
        if (v != null) p.setString(index, v); else p.setNull(index, java.sql.Types.VARCHAR);
    }

    private static void nullableLong(PreparedStatement p, int index, Long v) throws java.sql.SQLException {
        if (v != null) p.setLong(index, v); else p.setNull(index, java.sql.Types.BIGINT);
    }
}