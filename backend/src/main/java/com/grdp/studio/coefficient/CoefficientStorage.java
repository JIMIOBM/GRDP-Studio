package com.grdp.studio.coefficient;

import com.grdp.studio.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 两种产能系数共用一张表；只保存参数、PVT快照与标量结果，不保存曲线点。 */
@Service
public class CoefficientStorage {
    public record Parameters(Double pressure, Double temperature, Double a, Double b, Double correctedA,
                             Double correctedB, Double c, Double n, Double correctedC, Double correctedN,
                             Double pointPressure, Double pointRate) {}
    public record Save(Long id, long projectId, long gasReservoirId, String wellName, String name,
                       String method, String operation, String pressureMethod, Long pvtId,
                       Parameters parameters, Map<String, Object> pvtSnapshot, Double result) {}
    public record Summary(long id, String name, String method) {}
    public record Detail(long id, String name, String method, String operation, String pressureMethod,
                         Long pvtId, Parameters parameters, Map pvtSnapshot, double result, String version) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public CoefficientStorage(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    // 结构由单独交付的产能系数建表文本显式部署；服务启动不改表。

    private long well(long project, long reservoir, String name, boolean lock) {
        if (project <= 0 || reservoir <= 0 || name == null || name.isBlank()) fail("项目、气藏、井名不能为空");
        var ids = jdbc.queryForList("SELECT id FROM project_well_heads WHERE project_id=? AND project_gas_reservoir_id=? AND well_name=?" + (lock ? " FOR UPDATE" : ""), Long.class, project, reservoir, name.trim());
        if (ids.size() != 1) throw new BusinessException(404, "当前项目、气藏无法唯一定位该井");
        return ids.getFirst();
    }
    public List<Summary> list(long project, long reservoir, String name) {
        long well = well(project, reservoir, name, false);
        return jdbc.query("SELECT id,record_name,method_type FROM project_well_productivity_coefficient WHERE project_id=? AND gas_reservoir_id=? AND well_id=? ORDER BY method_type,record_no",
            (r, i) -> new Summary(r.getLong(1), r.getString(2), r.getString(3)), project, reservoir, well);
    }
    public Detail detail(long id, long project, long reservoir, String name) {
        long well = well(project, reservoir, name, false);
        var rows = jdbc.query("SELECT * FROM project_well_productivity_coefficient WHERE id=? AND project_id=? AND gas_reservoir_id=? AND well_id=?",
            (r, i) -> new Detail(r.getLong("id"), r.getString("record_name"), r.getString("method_type"),
                r.getString("operation_type"), r.getString("pressure_method"), r.getObject("pvt_id", Long.class),
                json.readValue(r.getString("parameters_json"), Parameters.class), json.readValue(r.getString("pvt_snapshot_json"), Map.class),
                r.getDouble("result_value"), r.getString("calculation_version")), id, project, reservoir, well);
        if (rows.size() != 1) throw new BusinessException(404, "当前井的产能系数记录不存在");
        return rows.getFirst();
    }

    @Transactional
    public Detail save(Save s) {
        validate(s);
        // 锁定所属井，避免同时保存时分配重复的井内方法编号。
        long well = well(s.projectId(), s.gasReservoirId(), s.wellName(), true);
        if (s.pvtId() != null && jdbc.queryForObject("SELECT COUNT(*) FROM project_well_pvt WHERE id=? AND well_id=?", Integer.class, s.pvtId(), well) != 1)
            fail("PVT不属于当前井");
        Long id = s.id();
        Detail existing = id == null ? null : detail(id, s.projectId(), s.gasReservoirId(), s.wellName());
        if (existing != null && !existing.method().equals(s.method())) fail("不能修改已保存记录的方法类型");
        String resultType = s.method().equals("二项式") && s.operation().equals("injection") ? "injection-limit" : "open-flow";
        String parameters = json.writeValueAsString(s.parameters());
        String snapshot = json.writeValueAsString(s.pvtSnapshot());
        if (id == null) {
            Integer no = jdbc.queryForObject("SELECT COALESCE(MAX(record_no),0)+1 FROM project_well_productivity_coefficient WHERE project_id=? AND gas_reservoir_id=? AND well_id=? AND method_type=?", Integer.class, s.projectId(), s.gasReservoirId(), well, s.method());
            String name = s.name() == null || s.name().isBlank() ? s.method() + no : s.name().trim();
            var key = new GeneratedKeyHolder();
            jdbc.update(c -> {
                var p = c.prepareStatement("INSERT INTO project_well_productivity_coefficient(project_id,gas_reservoir_id,well_id,record_no,record_name,method_type,operation_type,pressure_method,pvt_id,parameters_json,pvt_snapshot_json,result_value,result_type,calculation_version,units) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", new String[]{"id"});
                Object[] values = {s.projectId(), s.gasReservoirId(), well, no, name, s.method(), s.operation(), s.pressureMethod(), s.pvtId(), parameters, snapshot, s.result(), resultType, "coefficient-v1", "pressure=MPa;temperature=C;rate=1e4m3/d;pseudo=MPa2/(mPa.s)"};
                for (int i = 0; i < values.length; i++) p.setObject(i + 1, values[i]);
                return p;
            }, key);
            id = key.getKey().longValue();
        } else {
            String name = s.name() == null || s.name().isBlank() ? existing.name() : s.name().trim();
            jdbc.update("UPDATE project_well_productivity_coefficient SET record_name=?,operation_type=?,pressure_method=?,pvt_id=?,parameters_json=?,pvt_snapshot_json=?,result_value=?,result_type=?,calculation_version='coefficient-v1',updated_at=CURRENT_TIMESTAMP WHERE id=? AND project_id=? AND gas_reservoir_id=? AND well_id=?",
                name, s.operation(), s.pressureMethod(), s.pvtId(), parameters, snapshot, s.result(), resultType, id, s.projectId(), s.gasReservoirId(), well);
        }
        return detail(id, s.projectId(), s.gasReservoirId(), s.wellName());
    }
    private void validate(Save s) {
        if (s == null || s.parameters() == null || s.pvtSnapshot() == null) fail("缺少计算参数或PVT快照");
        if (!Set.of("二项式", "指数式").contains(s.method() == null ? "" : s.method())) fail("产能系数方法无效");
        if (!Set.of("production", "injection").contains(s.operation() == null ? "" : s.operation())) fail("注采类型无效");
        if (!Set.of("拟压力", "压力平方法", "压力法").contains(s.pressureMethod() == null ? "" : s.pressureMethod())) fail("压力计算方法无效");
        if (s.name() != null && s.name().trim().length() > 100) fail("记录名称不能超过100字");
        var p = s.parameters();
        positive(p.pressure());
        if (p.pressure() <= 0.101325) fail("地层压力必须大于大气压");
        if (p.temperature() != null && (!Double.isFinite(p.temperature()) || p.temperature() <= -273.15)) fail("地层温度无效");
        if (s.method().equals("指数式")) {
            positive(p.c()); positive(p.correctedC()); exponent(p.n()); exponent(p.correctedN());
            if (p.a() != null || p.b() != null || p.correctedA() != null || p.correctedB() != null) fail("指数式不能保存二项式系数");
        } else {
            nonnegative(p.a()); nonnegative(p.b()); nonnegative(p.correctedA()); nonnegative(p.correctedB());
            if (p.a() + p.b() == 0 || p.correctedA() + p.correctedB() == 0) fail("二项式系数不能同时为0");
            if (p.c() != null || p.n() != null || p.correctedC() != null || p.correctedN() != null) fail("二项式不能保存指数式系数");
            if (p.pointRate() == null) fail("二项式需要参数点");
        }
        if ((p.pointRate() == null) != (p.pointPressure() == null)) fail("参数点必须同时填写气量和压力");
        if (p.pointRate() != null) { nonnegative(p.pointRate()); nonnegative(p.pointPressure()); }
        if (s.result() == null || !Double.isFinite(s.result()) || s.result() < 0) fail("请先完成有效计算");
        if (s.pressureMethod().equals("拟压力") && !(s.pvtSnapshot().get("gasResultRows") instanceof List<?> rows && rows.size() >= 2)) fail("拟压力计算必须保存PVT结果快照");
    }
    private static void positive(Double v) { if (v == null || !Double.isFinite(v) || v <= 0) fail("系数或压力必须大于0"); }
    private static void nonnegative(Double v) { if (v == null || !Double.isFinite(v) || v < 0) fail("参数必须为非负数"); }
    private static void exponent(Double v) { if (v == null || !Double.isFinite(v) || v < 0.5 || v > 1) fail("指数必须在0.5至1之间"); }
    private static void fail(String message) { throw new BusinessException(400, message); }
}
