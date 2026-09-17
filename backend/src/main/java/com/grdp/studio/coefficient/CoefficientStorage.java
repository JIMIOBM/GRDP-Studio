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

    // 结构由 sql/productivity_coefficient.sql 显式部署；服务启动不改表。

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
            if (p.temperature() == null) fail("指数式必须填写地层温度");
            if (p.pressure() / 10 <= 0.101325) fail("生成10条IPR曲线时，最大地层压力必须大于1.01325 MPa");
            if (p.pointRate() == null || p.pointPressure() == null) fail("指数式拟合点的气量和井底压力必须同时填写");
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
        if (s.pressureMethod().equals("拟压力") && !(s.pvtSnapshot().get("gasResultRows") instanceof List<?> rows && rows.size() >= (s.method().equals("指数式") ? 1 : 2))) fail("拟压力计算必须保存PVT结果快照");
        if (s.method().equals("指数式")) validateExponential(s);
    }

    private void validateExponential(Save s) {
        var p = s.parameters();
        boolean injection = s.operation().equals("injection");
        double start = injection ? p.pressure() / 10 : p.pressure();
        double minimum = injection ? start : 0.101325;
        if (p.pointPressure() < minimum || p.pointPressure() > p.pressure()) fail("拟合点井底压力超出当前曲线范围");
        if (p.pointRate() == 0) {
            if (Math.abs(p.pointPressure() - start) > 1e-9) fail("零气量点的井底压力必须等于曲线起始地层压力");
        } else if (injection ? p.pointPressure() <= start : p.pointPressure() >= start) {
            fail("拟合点井底压力与注采方向不一致");
        }
        var points = s.pressureMethod().equals("拟压力") ? pseudoPoints(s) : List.<double[]>of();
        double maximum = potential(p.pressure(), s.pressureMethod(), points);
        double startPotential = potential(start, s.pressureMethod(), points);
        double atmospheric = potential(0.101325, s.pressureMethod(), points);
        double fittedLimit = p.c() * Math.pow(injection ? maximum - startPotential : maximum - atmospheric, p.n());
        double expected = p.correctedC() * Math.pow(maximum - atmospheric, p.correctedN());
        if (!Double.isFinite(fittedLimit) || fittedLimit <= 0 || !Double.isFinite(expected) || expected <= 0) fail("参数无法生成有效的指数式流量");
        if (p.pointRate() > fittedLimit + 1e-9 * fittedLimit) fail("拟合点气量不能超过拟合曲线范围");
        // 兼容历史界面四位小数的保存值；新界面传输未舍入的计算结果。
        if (Math.abs(s.result() - expected) > Math.max(0.00005, Math.abs(expected) * 1e-9)) fail("保存的无阻流量与修正系数计算结果不一致");
    }

    private List<double[]> pseudoPoints(Save s) {
        var rows = (List<?>) s.pvtSnapshot().get("gasResultRows");
        var byPressure = new java.util.TreeMap<Double, Double>();
        for (Object row : rows) {
            Object pressure = null, pseudo = null;
            if (row instanceof List<?> values && values.size() >= 4) {
                pressure = values.get(0); pseudo = values.get(3);
            } else if (row instanceof Map<?, ?> values) {
                pressure = field(values, "pressure", "formationPressure", "reservoirPressure", "压力", "压力(MPa)");
                pseudo = field(values, "pseudoPressure", "pseudo_pressure", "gasPseudoPressure", "mP", "mp", "气体拟压力", "气体拟压力(MPa²/(mPa·s))");
            }
            Double x = numeric(pressure), y = numeric(pseudo);
            if (x != null && x >= 0 && y != null) byPressure.put(x, y);
        }
        byPressure.putIfAbsent(0d, 0d);
        if (byPressure.size() < 2) fail("PVT快照缺少有效气体拟压力数据");
        var points = new java.util.ArrayList<double[]>();
        double previous = -1;
        for (var entry : byPressure.entrySet()) {
            if (entry.getValue() < 0 || entry.getValue() <= previous) fail("PVT拟压力必须非负并随压力严格递增");
            points.add(new double[]{entry.getKey(), entry.getValue()});
            previous = entry.getValue();
        }
        return points;
    }

    private Object field(Map<?, ?> values, String... names) {
        for (String name : names) if (values.get(name) != null && !values.get(name).toString().isBlank()) return values.get(name);
        return null;
    }
    private Double numeric(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) return null;
        if (!value.toString().trim().matches("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")) return null;
        try { double number = Double.parseDouble(value.toString()); return Double.isFinite(number) ? number : null; }
        catch (NumberFormatException e) { return null; }
    }
    private double potential(double pressure, String method, List<double[]> points) {
        if (method.equals("压力法")) return pressure;
        if (method.equals("压力平方法")) return pressure * pressure;
        if (pressure > points.getLast()[0] + 1e-9) fail("PVT快照的压力范围不足以覆盖曲线");
        for (int i = 0; i < points.size(); i++) {
            var upper = points.get(i);
            if (Math.abs(pressure - upper[0]) <= 1e-9) return upper[1];
            if (upper[0] > pressure && i > 0) {
                var lower = points.get(i - 1);
                return lower[1] + (pressure - lower[0]) / (upper[0] - lower[0]) * (upper[1] - lower[1]);
            }
        }
        throw new BusinessException(400, "PVT快照的压力范围不足以覆盖曲线");
    }
    private static void positive(Double v) { if (v == null || !Double.isFinite(v) || v <= 0) fail("系数或压力必须大于0"); }
    private static void nonnegative(Double v) { if (v == null || !Double.isFinite(v) || v < 0) fail("参数必须为非负数"); }
    private static void exponent(Double v) { if (v == null || !Double.isFinite(v) || v < 0.5 || v > 1) fail("指数必须在0.5至1之间"); }
    private static void fail(String message) { throw new BusinessException(400, message); }
}
