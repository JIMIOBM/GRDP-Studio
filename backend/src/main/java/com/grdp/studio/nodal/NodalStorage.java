package com.grdp.studio.nodal;

import com.grdp.studio.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.grdp.studio.nodal.NodalModels.*;

@Service
public class NodalStorage {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final NodalService calculation;
    public NodalStorage(JdbcTemplate jdbc, ObjectMapper json, NodalService calculation) {
        this.jdbc = jdbc; this.json = json; this.calculation = calculation;
    }
    private long well(long project, long reservoir, String name) {
        if (project <= 0 || reservoir <= 0 || name == null || name.isBlank()) throw new BusinessException(400, "请选择当前井");
        var ids = jdbc.queryForList("SELECT id FROM project_well_heads WHERE project_id=? AND project_gas_reservoir_id=? AND well_name=?", Long.class, project, reservoir, name.trim());
        if (ids.size() != 1) throw new BusinessException(404, "当前项目和气藏下未找到唯一的井");
        return ids.getFirst();
    }
    @Transactional
    public Map<String, Object> save(Save save, String token, String cookie, String env) {
        if (save == null || save.input() == null || save.name() == null || save.name().isBlank() || save.name().trim().length() > 100)
            throw new BusinessException(400, "请输入1至100字的方案名称");
        Input r = save.input(); long well = well(r.projectId, r.gasReservoirId, r.wellName);
        if (save.id() != null) {
            if (save.id() <= 0) throw new BusinessException(400, "节点分析方案ID无效");
            detail(save.id(), r.projectId, r.gasReservoirId, r.wellName);
        }
        // Recompute server-side. Clients cannot save fabricated curves or maximum capacities.
        Result result = calculation.calculate(r, token, cookie, env);
        String inputJson = json.writeValueAsString(r), resultJson = json.writeValueAsString(result);
        if (save.id() != null) {
            int updated = jdbc.update("UPDATE project_well_nodal_analysis SET record_name=?,operation_mode=?,algorithm_version=?,input_json=?,result_json=? WHERE id=? AND project_id=? AND gas_reservoir_id=? AND well_id=?",
                    save.name().trim(), r.operationMode, result.version(), inputJson, resultJson, save.id(), r.projectId, r.gasReservoirId, well);
            if (updated != 1) throw new BusinessException(404, "当前井节点分析方案不存在");
            return detail(save.id(), r.projectId, r.gasReservoirId, r.wellName);
        }
        var key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            var s = c.prepareStatement("INSERT INTO project_well_nodal_analysis(project_id,gas_reservoir_id,well_id,record_name,operation_mode,algorithm_version,input_json,result_json) VALUES(?,?,?,?,?,?,?,?)", new String[]{"id"});
            Object[] values = {r.projectId, r.gasReservoirId, well, save.name().trim(), r.operationMode, result.version(), inputJson, resultJson};
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            return s;
        }, key);
        return detail(key.getKey().longValue(), r.projectId, r.gasReservoirId, r.wellName);
    }
    public List<Map<String, Object>> list(long p, long r, String w) {
        long id = well(p, r, w);
        return jdbc.queryForList("SELECT id,record_name AS name,operation_mode AS operationMode,created_at AS createdAt FROM project_well_nodal_analysis WHERE project_id=? AND gas_reservoir_id=? AND well_id=? ORDER BY id DESC", p, r, id);
    }
    public Map<String, Object> detail(long id, long p, long r, String w) {
        long well = well(p, r, w);
        var rows = jdbc.query("SELECT record_name,input_json,result_json FROM project_well_nodal_analysis WHERE id=? AND project_id=? AND gas_reservoir_id=? AND well_id=?",
                (rs, n) -> Map.<String, Object>of("id", id, "name", rs.getString(1), "input", json.readValue(rs.getString(2), Input.class), "result", json.readValue(rs.getString(3), Result.class)), id, p, r, well);
        if (rows.isEmpty()) throw new BusinessException(404, "当前井节点分析方案不存在");
        return rows.getFirst();
    }
    public void delete(long id, long p, long r, String w) {
        long well = well(p, r, w);
        if (jdbc.update("DELETE FROM project_well_nodal_analysis WHERE id=? AND project_id=? AND gas_reservoir_id=? AND well_id=?", id, p, r, well) != 1)
            throw new BusinessException(404, "当前井节点分析方案不存在");
    }
}
