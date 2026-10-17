package com.grdp.studio.solutiongeneration.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.solutiongeneration.dto.SolutionRecordSaveRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Service
public class SolutionRecordStorageService {
    private final JdbcTemplate jdbc;

    public SolutionRecordStorageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> save(SolutionRecordSaveRequest save) {
        String well = save.wellName().trim();
        if (well.isEmpty()) throw new BusinessException(400, "井名不能为空");

        String finalRecordName = save.recordName();
        if (finalRecordName == null || finalRecordName.isBlank()) {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM multi_period_solution_records" +
                            " WHERE project_id=? AND gas_reservoir_id=? AND well_name=?",
                    Long.class, save.projectId(), save.gasReservoirId(), well);
            finalRecordName = "方案" + (count + 1);
        }

        KeyHolder key = new GeneratedKeyHolder();
        String sql = """
            INSERT INTO multi_period_solution_records(
                project_id, gas_reservoir_id, well_name, record_name,
                target_production, export_pressure, constraint_record_id,
                source_request_json, chart_data_json, boundary_report_json,
                created_at, updated_at
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        String nameForInsert = finalRecordName;
        jdbc.update(c -> {
            PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            Timestamp now = Timestamp.from(Instant.now());
            int i = 0;
            p.setLong(++i, save.projectId());
            p.setLong(++i, save.gasReservoirId());
            p.setString(++i, well);
            p.setString(++i, nameForInsert);
            p.setBigDecimal(++i, save.targetProduction());
            p.setBigDecimal(++i, save.exportPressure());
            if (save.constraintRecordId() != null) p.setLong(++i, save.constraintRecordId());
            else p.setNull(++i, java.sql.Types.BIGINT);
            p.setString(++i, save.sourceRequestJson());
            p.setString(++i, save.chartDataJson());
            p.setString(++i, save.boundaryReportJson());
            p.setTimestamp(++i, now);
            p.setTimestamp(++i, now);
            return p;
        }, key);

        Number generatedId = key.getKey();
        if (generatedId == null) throw new BusinessException(500, "方案保存失败，未获取到ID");
        return detail(generatedId.longValue());
    }

    @Transactional
    public Map<String, Object> update(long id, SolutionRecordSaveRequest save) {
        String well = save.wellName().trim();
        Map<String, Object> existing = detail(id);
        String existingWell = String.valueOf(existing.getOrDefault("well_name", ""));
        Number existingProject = (Number) existing.get("project_id");
        Number existingReservoir = (Number) existing.get("gas_reservoir_id");
        if (!well.equals(existingWell.trim()) ||
                existingProject == null || existingProject.longValue() != save.projectId() ||
                existingReservoir == null || existingReservoir.longValue() != save.gasReservoirId()) {
            throw new BusinessException(404, "该方案不属于当前项目、气藏或井，不能更新");
        }

        String recordName = save.recordName();
        if (recordName == null || recordName.isBlank()) {
            recordName = String.valueOf(existing.getOrDefault("record_name", "方案" + id));
        }

        String sql = """
            UPDATE multi_period_solution_records SET
                record_name=?, target_production=?, export_pressure=?,
                constraint_record_id=?, source_request_json=?, chart_data_json=?,
                boundary_report_json=?, updated_at=?
            WHERE id=? AND project_id=? AND gas_reservoir_id=? AND well_name=?
            """;
        int updated = jdbc.update(sql,
                recordName, save.targetProduction(), save.exportPressure(),
                save.constraintRecordId(), save.sourceRequestJson(), save.chartDataJson(),
                save.boundaryReportJson(), Timestamp.from(Instant.now()), id,
                save.projectId(), save.gasReservoirId(), well);
        if (updated != 1) {
            throw new BusinessException(404, "没有找到匹配当前井的方案记录，更新失败");
        }
        return detail(id);
    }

    // 全量历史查询：只按当前项目/气藏筛选，不要求井号。
    // 为左侧树提供所有真实数据库记录；新历史记录不会由于未包含在原平台井列表而遗漏。
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByScope(long projectId, long gasReservoirId) {
        return jdbc.queryForList(
                "SELECT id, project_id, gas_reservoir_id, well_name, record_name," +
                        " target_production, export_pressure, constraint_record_id," +
                        " created_at, updated_at" +
                        " FROM multi_period_solution_records" +
                        " WHERE project_id=? AND gas_reservoir_id=?" +
                        " ORDER BY created_at DESC, id DESC",
                projectId, gasReservoirId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(long projectId, long gasReservoirId, String wellName) {
        return jdbc.queryForList(
                "SELECT id, project_id, gas_reservoir_id, well_name, record_name," +
                        " target_production, export_pressure, constraint_record_id," +
                        " source_request_json, chart_data_json, boundary_report_json," +
                        " created_at, updated_at" +
                        " FROM multi_period_solution_records" +
                        " WHERE project_id=? AND gas_reservoir_id=? AND well_name=?" +
                        " ORDER BY created_at DESC",
                projectId, gasReservoirId, wellName.trim());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, project_id, gas_reservoir_id, well_name, record_name," +
                        " target_production, export_pressure, constraint_record_id," +
                        " source_request_json, chart_data_json, boundary_report_json," +
                        " created_at, updated_at" +
                        " FROM multi_period_solution_records WHERE id=?",
                id);
        if (rows.isEmpty()) throw new BusinessException(404, "方案记录不存在：" + id);
        return rows.get(0);
    }

    @Transactional
    public void delete(long id, long projectId, long gasReservoirId, String wellName) {
        int rows = jdbc.update(
                "DELETE FROM multi_period_solution_records" +
                        " WHERE id=? AND project_id=? AND gas_reservoir_id=? AND well_name=?",
                id, projectId, gasReservoirId, wellName.trim());
        if (rows == 0) throw new BusinessException(404, "方案记录不存在或无权删除");
    }
}