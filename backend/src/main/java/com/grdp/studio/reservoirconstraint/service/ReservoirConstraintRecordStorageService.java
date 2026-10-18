package com.grdp.studio.reservoirconstraint.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.reservoirconstraint.dto.ReservoirConstraintRecordSaveRequest;
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

/** 库级约束条件记录存取，对应数据表 storage_prediction_constraint。 */
@Service
public class ReservoirConstraintRecordStorageService {
    private static final String TABLE = "`storage_prediction_constraint`";

    private static final String INSERT_SQL = """
            INSERT INTO %s
            (project_id, gas_reservoir_id, storage_id, title, state,
             form_json, well_results_json, boundaries_json,
             revision, created_by, updated_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """.formatted(TABLE);
    
    private static final String UPDATE_SQL = """
            UPDATE %s
            SET state=?, form_json=?, well_results_json=?, boundaries_json=?,
                revision=revision+1, updated_by=?
            WHERE id=? AND project_id=? AND gas_reservoir_id=? AND storage_id=?
              AND deleted_at IS NULL
            """.formatted(TABLE);

    private static final String LIST_SQL = """
            SELECT id, project_id AS projectId, gas_reservoir_id AS gasReservoirId,
                   storage_id AS storageId, title, state, revision,
                   form_json AS formJson, well_results_json AS wellResultsJson,
                   boundaries_json AS boundariesJson, created_by AS createdBy,
                   updated_by AS updatedBy, created_at AS createdAt, updated_at AS updatedAt
            FROM %s
            WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?
              AND deleted_at IS NULL
            ORDER BY updated_at DESC
            """.formatted(TABLE);

    private static final String DETAIL_SQL = """
            SELECT id, project_id AS projectId, gas_reservoir_id AS gasReservoirId,
                   storage_id AS storageId, title, state, revision,
                   form_json AS formJson, well_results_json AS wellResultsJson,
                   boundaries_json AS boundariesJson, created_by AS createdBy,
                   updated_by AS updatedBy, created_at AS createdAt, updated_at AS updatedAt
            FROM %s
            WHERE id=? AND deleted_at IS NULL
            """.formatted(TABLE);

    private static final String DELETE_SQL = """
            UPDATE %s
            SET deleted_at=?
            WHERE id=? AND project_id=? AND gas_reservoir_id=?
              AND storage_id=? AND deleted_at IS NULL
            """.formatted(TABLE);

    private static final String COUNT_SQL = """
            SELECT COUNT(*)
            FROM %s
            WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?
              AND deleted_at IS NULL
            """.formatted(TABLE);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ReservoirConstraintRecordStorageService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> save(ReservoirConstraintRecordSaveRequest save) {
        validateScope(save);
        String finalTitle = title(save.title(), save.projectId(), save.gasReservoirId(), save.storageId());
        KeyHolder key = new GeneratedKeyHolder();
        String actor = who(save.updatedBy(), save.createdBy());
        String state = normalState(save.state());
        jdbc.update(c -> {
            PreparedStatement p = c.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            p.setLong(i++, save.projectId());
            p.setLong(i++, save.gasReservoirId());
            p.setLong(i++, save.storageId());
            p.setString(i++, finalTitle);
            p.setString(i++, state);
            p.setString(i++, write(save.formJson()));
            nullableString(p, i++, save.wellResultsJson() != null ? write(save.wellResultsJson()) : null);
            nullableString(p, i++, save.boundariesJson() != null ? write(save.boundariesJson()) : null);
            p.setInt(i++, 1);
            p.setString(i++, actor);
            p.setString(i++, actor);
            return p;
        }, key);
        return detail(key.getKey().longValue());
    }

    @Transactional
    public Map<String, Object> update(long id, ReservoirConstraintRecordSaveRequest update) {
        validateScope(update);
        Map<String, Object> existing = detail(id);
        checkSameScope(existing, update, "约束记录");
        // UPDATE 的 WHERE 同时约束所属库和原 ID；绝不触发 INSERT 或重新编号。
        String actor = who(update.updatedBy(), update.createdBy());
        int changed = jdbc.update(
                UPDATE_SQL,
                normalState(update.state()),
                write(update.formJson()),
                update.wellResultsJson() != null ? write(update.wellResultsJson()) : null,
                update.boundariesJson() != null ? write(update.boundariesJson()) : null,
                actor, id, update.projectId(), update.gasReservoirId(), update.storageId()
        );
        if (changed != 1) throw new BusinessException(404, "数据库中未找到该库的这条约束记录，更新已取消");
        return detail(id);
    }

    public List<Map<String, Object>> list(long projectId, long gasReservoirId, long storageId) {
        return jdbc.queryForList(
                LIST_SQL,
                projectId, gasReservoirId, storageId);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(DETAIL_SQL, id);
        if (rows.size() != 1) throw new BusinessException(404, "未找到库级约束记录");
        return rows.get(0);
    }

    @Transactional
    public void delete(long id, long projectId, long gasReservoirId, long storageId) {
        int changed = jdbc.update(
                DELETE_SQL,
                Timestamp.from(Instant.now()),
                id, projectId, gasReservoirId, storageId);
        if (changed != 1) throw new BusinessException(404, "未找到该库的约束记录");
    }

    private static void validateScope(ReservoirConstraintRecordSaveRequest r) {
        if (r.projectId() == null || r.projectId() <= 0 ||
                r.gasReservoirId() == null || r.gasReservoirId() <= 0 ||
                r.storageId() == null || r.storageId() <= 0) {
            throw new BusinessException(400, "项目、气藏和库ID不能为空");
        }
        if (!"CALCULATED".equals(normalState(r.state()))) {
            throw new BusinessException(422, "只有计算完成（CALCULATED）的约束条件才能保存");
        }
        if (r.formJson() == null) {
            throw new BusinessException(422, "缺少约束表单快照 formJson");
        }
    }

    private static void checkSameScope(Map<String, Object> existing, ReservoirConstraintRecordSaveRequest r, String kind) {
        if (((Number) existing.get("projectId")).longValue() != r.projectId() ||
                ((Number) existing.get("gasReservoirId")).longValue() != r.gasReservoirId() ||
                ((Number) existing.get("storageId")).longValue() != r.storageId()) {
            throw new BusinessException(404, "该" + kind + "不属于当前项目、气藏或库，不允许覆盖");
        }
    }

    private String title(String requested, long projectId, long gasReservoirId, long storageId) {
        if (requested != null && !requested.trim().isEmpty()) return requested.trim();
        Long count = jdbc.queryForObject(
                COUNT_SQL, Long.class, projectId, gasReservoirId, storageId);
        return "约束条件" + String.valueOf((count == null ? 0 : count) + 1);
    }

    private static String normalState(String state) {
        return Objects.equals(state, "CALCULATED") ? "CALCULATED" : "DRAFT";
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
        if (v == null) p.setNull(index, java.sql.Types.VARCHAR); else p.setString(index, v);
    }
}