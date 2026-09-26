package com.grdp.studio.wellbore.erosion.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.wellbore.erosion.dto.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Follows wellbore risk's JDBC storage style. Schema is installed manually, never at startup. */
@Service
public class ErosionStorageService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ErosionCalculationService calculator;
    public ErosionStorageService(JdbcTemplate jdbc, ObjectMapper json, ErosionCalculationService calculator) {
        this.jdbc = jdbc; this.json = json; this.calculator = calculator;
    }

    @Transactional
    public Map<String, Object> save(ErosionSaveRequest save, String token, String cookie, String environment) {
        if (save == null || save.calculation() == null) throw new BusinessException(400, "计算输入不能为空");
        var input = save.calculation();
        long wellId = wellId(input.projectId(), input.gasReservoirId(), input.wellName());
        var calculation = calculator.calculate(input, token, cookie, environment);
        var r = calculation.result();
        if (r.status() == ErosionResult.Status.NOT_APPLICABLE || r.status() == ErosionResult.Status.CALCULATION_ERROR)
            throw new BusinessException(400, "无法保存无效计算：" + r.reason());
        var req = calculation.input();
        // Serialize same-well writers by locking the parent, including the first saved calculation.
        jdbc.queryForObject("SELECT id FROM project_well_heads WHERE id=? FOR UPDATE", Long.class, wellId);
        Integer no = jdbc.queryForObject("SELECT COALESCE(MAX(calculation_no),0)+1 FROM project_well_erosion WHERE well_id=?", Integer.class, wellId);
        String name = save.calculationName() == null || save.calculationName().isBlank() ? "冲蚀方案" + no : save.calculationName().trim();
        if (name.length() > 100 || (save.remark() != null && save.remark().length() > 500)) throw new BusinessException(400, "方案名称或备注过长");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("well_id", wellId); values.put("pvt_id", req.pvtId());
        values.put("calculation_no", no); values.put("calculation_name", name);
        values.put("pressure_mpa", req.pressureMpa()); values.put("temperature_c", req.temperatureC());
        values.put("tubing_inner_diameter_mm", req.tubingInnerDiameterMm()); values.put("actual_gas_rate_1e4_m3d", req.actualGasRate1e4M3d());
        values.put("gas_density_kg_m3", req.gasDensityKgM3()); values.put("liquid_density_kg_m3", req.liquidDensityKgM3());
        values.put("gas_volume_factor", req.gasVolumeFactor()); values.put("liquid_holdup_percent", req.liquidHoldupPercent());
        values.put("sand_content_percent", req.sandContentPercent()); values.put("sand_density_kg_m3", req.sandDensityKgM3());
        values.put("sand_factor", r.sandFactor()); values.put("liquid_holdup_factor", r.liquidHoldupFactor());
        values.put("critical_erosion_coefficient", r.criticalErosionCoefficient()); values.put("mixture_density", r.mixtureDensity());
        values.put("actual_velocity", r.actualVelocity()); values.put("critical_velocity", r.criticalVelocity());
        values.put("velocity_ratio", r.velocityRatio()); values.put("critical_gas_rate", r.criticalGasRate());
        values.put("applicable", r.applicable()); values.put("status", r.status().name());
        values.put("incomplete_reason", r.criticalGasRateReason()); values.put("algorithm_code", r.modelVersion());
        values.put("pvt_snapshot_json", json.writeValueAsString(req.pvtSnapshot()));
        values.put("input_json", json.writeValueAsString(req)); values.put("result_json", json.writeValueAsString(r));
        values.put("remark", save.remark());
        String sql = "INSERT INTO project_well_erosion(" + String.join(",", values.keySet()) + ") VALUES(" + String.join(",", Collections.nCopies(values.size(), "?")) + ")";
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[] { "id" });
            int i = 1;
            for (Object value : values.values()) statement.setObject(i++, value);
            return statement;
        }, key);
        if (key.getKey() == null) throw new BusinessException(500, "冲蚀方案保存失败");
        return detail(key.getKey().longValue(), req.projectId(), req.gasReservoirId(), req.wellName());
    }

    public List<Map<String, Object>> list(Long projectId, Long reservoirId, String wellName) {
        return jdbc.queryForList("SELECT id,calculation_no AS calculationNo,calculation_name AS calculationName,status,applicable,created_at AS createdAt FROM project_well_erosion WHERE well_id=? ORDER BY calculation_no DESC",
                wellId(projectId, reservoirId, wellName));
    }
    public Map<String, Object> detail(long id, Long projectId, Long reservoirId, String wellName) {
        var rows = jdbc.queryForList("SELECT id,calculation_no AS calculationNo,calculation_name AS calculationName,input_json,result_json,remark FROM project_well_erosion WHERE id=? AND well_id=?",
                id, wellId(projectId, reservoirId, wellName));
        if (rows.size() != 1) throw new BusinessException(404, "未找到当前井冲蚀方案");
        return rows.getFirst();
    }
    public void delete(long id, Long projectId, Long reservoirId, String wellName) {
        if (jdbc.update("DELETE FROM project_well_erosion WHERE id=? AND well_id=?", id, wellId(projectId, reservoirId, wellName)) != 1)
            throw new BusinessException(404, "未找到当前井冲蚀方案");
    }
    private long wellId(Long projectId, Long reservoirId, String wellName) {
        if (projectId == null || reservoirId == null || wellName == null || wellName.isBlank()) throw new BusinessException(400, "项目、气藏和井名不能为空");
        var ids = jdbc.queryForList("SELECT id FROM project_well_heads WHERE project_id=? AND project_gas_reservoir_id=? AND well_name=?",
                Long.class, projectId, reservoirId, wellName.trim());
        if (ids.size() != 1) throw new BusinessException(ids.isEmpty() ? 404 : 409, "当前项目、气藏和井名无法唯一定位井记录");
        return ids.getFirst();
    }
}
