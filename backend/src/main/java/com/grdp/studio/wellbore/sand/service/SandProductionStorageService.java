package com.grdp.studio.wellbore.sand.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.wellbore.sand.dto.SandProductionRequest;
import com.grdp.studio.wellbore.sand.dto.SandProductionResult;
import com.grdp.studio.wellbore.sand.dto.SandProductionSaveRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

@Service
public class SandProductionStorageService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SandProductionCalculator calculator;

    public SandProductionStorageService(JdbcTemplate jdbc, ObjectMapper json, SandProductionCalculator calculator) {
        this.jdbc = jdbc; this.json = json; this.calculator = calculator;
    }

    @Transactional
    public Map<String, Object> save(SandProductionSaveRequest save) {
        SandProductionRequest req = save.calculation();
        SandProductionResult r = calculator.calculate(req);
        long wellId = wellId(req.projectId(), req.gasReservoirId(), req.wellName());
        int no = nextNo("project_well_sand_production", wellId);
        KeyHolder key = new GeneratedKeyHolder();
        String sql = """
                INSERT INTO project_well_sand_production(well_id,calculation_no,calculation_name,
                proppant_type,proppant_type_label,proppant_mass_t,proppant_density_g_cm3,fracture_half_length_m,
                closure_pressure_mpa,critical_velocity_m_s,velocity_overridden,proppant_volume_m3,fracture_area_m2,
                critical_rate_1e4_m3d,actual_rate_1e4_m3d,ratio_percent,risk_level,level_key,
                input_json,result_json,remark) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        jdbc.update(c -> {
            PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            p.setLong(i++, wellId);
            p.setInt(i++, no);
            p.setString(i++, name(save.calculationName(), "出砂方案" + no));
            p.setString(i++, r.proppantType());
            p.setString(i++, r.proppantTypeLabel());
            p.setDouble(i++, req.massT());
            p.setDouble(i++, req.densityGCm3() != null
                    ? req.densityGCm3() : SandProductionCalculator.defaultDensity(req.proppantType()));
            p.setDouble(i++, req.halfLengthM());
            p.setDouble(i++, req.closurePressureMpa());
            p.setDouble(i++, r.criticalVelocityMS());
            p.setBoolean(i++, r.velocityOverridden());
            p.setDouble(i++, r.proppantVolumeM3());
            p.setDouble(i++, r.fractureAreaM2());
            p.setDouble(i++, r.criticalRate1e4M3d());
            nullableDouble(p, i++, r.actualRate1e4M3d());
            nullableDouble(p, i++, r.ratioPercent());
            p.setString(i++, r.riskLevel());
            p.setString(i++, r.levelKey());
            p.setString(i++, write(req));
            p.setString(i++, write(r));
            p.setString(i, blank(save.remark()));
            return p;
        }, key);
        return detail(key.getKey().longValue(), wellId);
    }

    public List<Map<String, Object>> list(long projectId, long reservoirId, String wellName) {
        return list("project_well_sand_production", wellId(projectId, reservoirId, wellName), "risk_level");
    }

    public Map<String, Object> detail(long id, long projectId, long reservoirId, String wellName) {
        return detail(id, wellId(projectId, reservoirId, wellName));
    }

    private Map<String, Object> detail(long id, long wellId) {
        return one("""
                SELECT *,calculation_no AS calculationNo,calculation_name AS calculationName,
                proppant_type AS proppantType,proppant_type_label AS proppantTypeLabel,
                proppant_mass_t AS proppantMassT,proppant_density_g_cm3 AS proppantDensityGCm3,
                fracture_half_length_m AS fractureHalfLengthM,closure_pressure_mpa AS closurePressureMpa,
                critical_velocity_m_s AS criticalVelocityMS,velocity_overridden AS velocityOverridden,
                proppant_volume_m3 AS proppantVolumeM3,fracture_area_m2 AS fractureAreaM2,
                critical_rate_1e4_m3d AS criticalRate1e4M3d,actual_rate_1e4_m3d AS actualRate1e4M3d,
                ratio_percent AS ratioPercent,risk_level AS riskLevel,level_key AS levelKey
                FROM project_well_sand_production WHERE id=? AND well_id=?
                """, id, wellId);
    }

    public void delete(long id, long projectId, long reservoirId, String wellName) {
        delete("project_well_sand_production", id, wellId(projectId, reservoirId, wellName));
    }

    private List<Map<String, Object>> list(String table, long wellId, String statusColumn) {
        return jdbc.queryForList("SELECT id,calculation_no AS calculationNo,calculation_name AS calculationName," + statusColumn + " AS status,created_at AS createdAt FROM " + table + " WHERE well_id=? ORDER BY calculation_no DESC", wellId);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.size() != 1) throw new BusinessException(404, "未找到当前井计算方案");
        return rows.getFirst();
    }

    private void delete(String table, long id, long wellId) {
        int count = jdbc.update("DELETE FROM " + table + " WHERE id=? AND well_id=?", id, wellId);
        if (count == 0) throw new BusinessException(404, "未找到当前井计算方案");
    }

    private int nextNo(String table, long wellId) {
        List<Integer> rows = jdbc.queryForList("SELECT calculation_no FROM " + table + " WHERE well_id=? ORDER BY calculation_no DESC LIMIT 1 FOR UPDATE", Integer.class, wellId);
        return rows.isEmpty() ? 1 : rows.getFirst() + 1;
    }

    private long wellId(Long projectId, Long reservoirId, String wellName) {
        if (projectId == null || reservoirId == null || wellName == null || wellName.isBlank())
            throw new BusinessException(400, "项目、气藏和井名不能为空");
        List<Long> ids = jdbc.queryForList("SELECT id FROM project_well_heads WHERE project_id=? AND project_gas_reservoir_id=? AND well_name=?", Long.class, projectId, reservoirId, wellName.trim());
        if (ids.size() != 1) throw new BusinessException(ids.isEmpty() ? 404 : 409, "当前项目、气藏和井名无法唯一定位井记录");
        return ids.getFirst();
    }

    private static void nullableDouble(PreparedStatement p, int index, Double value) throws java.sql.SQLException {
        if (value == null) p.setNull(index, java.sql.Types.DOUBLE);
        else p.setDouble(index, value);
    }

    private String write(Object v) {
        try { return json.writeValueAsString(v); }
        catch (JacksonException e) { throw new BusinessException(500, "计算快照序列化失败"); }
    }

    private static String name(String v, String fallback) { return v == null || v.isBlank() ? fallback : v.trim(); }
    private static String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }
}
