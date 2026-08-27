package com.grdp.studio.productivitystorage.service;

import com.grdp.studio.productivitystorage.dto.IsochronalTestDtos.CurvePoint;
import com.grdp.studio.productivitystorage.dto.IsochronalTestDtos.Detail;
import com.grdp.studio.productivitystorage.dto.IsochronalTestDtos.Input;
import com.grdp.studio.productivitystorage.dto.IsochronalTestDtos.InputPoint;
import com.grdp.studio.productivitystorage.dto.IsochronalTestDtos.IprCurve;
import com.grdp.studio.productivitystorage.dto.IsochronalTestDtos.IprPoint;
import com.grdp.studio.productivitystorage.dto.IsochronalTestDtos.Result;
import com.grdp.studio.productivitystorage.dto.IsochronalTestDtos.SaveRequest;
import com.grdp.studio.productivitystorage.dto.IsochronalTestDtos.Summary;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class ProductivityTestStorageService {
    private static final List<String> PRESSURE_METHODS =
            List.of("pseudo-pressure", "pressure-squared", "pressure");
    private final JdbcTemplate jdbc;

    public ProductivityTestStorageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Summary> listIsochronal(long projectId, long reservoirId, String wellName) {
        String sql = """
                SELECT t.id,t.test_no,t.test_name,t.well_name,t.pvt_id,p.pvt_no,
                       COALESCE(o.pressure_method,''),t.status
                FROM project_well_productivity_test t
                JOIN project_well_pvt p ON p.id=t.pvt_id
                LEFT JOIN project_well_productivity_binomial_output o ON o.test_id=t.id
                WHERE t.project_id=? AND t.project_gas_reservoir_id=? AND t.test_method='isochronal'
                """ + (wellName == null || wellName.isBlank() ? "" : " AND t.well_name=?") +
                " ORDER BY t.well_name,t.test_no,o.updated_at DESC";
        Object[] args = wellName == null || wellName.isBlank()
                ? new Object[]{projectId, reservoirId}
                : new Object[]{projectId, reservoirId, wellName.trim()};
        List<Summary> rows = jdbc.query(sql, (rs, rowNum) -> new Summary(
                rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getInt(6), rs.getString(7), rs.getString(8)), args);
        List<Summary> unique = new ArrayList<>();
        for (Summary row : rows) {
            if (unique.stream().noneMatch(item -> item.testId() == row.testId())) unique.add(row);
        }
        return unique;
    }

    public Detail getIsochronal(long testId, long projectId, long reservoirId) {
        Summary summary = findSummary(testId, projectId, reservoirId);
        Input input = loadInput(testId);
        String method = summary.pressureMethod().isBlank() ? "pressure" : summary.pressureMethod();
        return new Detail(summary, input, method, loadResult(testId, method));
    }

    @Transactional
    public Summary saveIsochronal(SaveRequest request) {
        validatePressureMethod(request.pressureMethod());
        long wellId = findWellId(request.projectId(), request.gasReservoirId(), request.wellName());
        long pvtId = ensurePvt(wellId, request.pvtNo(), request.pvtName());
        long testId = request.testId() == null
                ? insertTest(request, wellId, pvtId)
                : updateAndValidateTest(request, wellId, pvtId);
        // 输入或 PVT 发生变化时，旧压力方法的结果不再可信；本次保存只重建当前方法。
        jdbc.update("DELETE FROM project_well_productivity_binomial_output WHERE test_id=?", testId);
        long inputId = replaceInput(testId, request.input());
        replaceInputPoints(inputId, request.input().points());
        long outputId = replaceOutput(testId, request.pressureMethod(), request.result());
        replaceCurvePoints(outputId, request.result());
        replaceIprPoints(outputId, request.result().iprCurves());
        jdbc.update("UPDATE project_well_productivity_test SET status='calculated' WHERE id=?", testId);
        return findSummary(testId, request.projectId(), request.gasReservoirId());
    }

    private long findWellId(long projectId, long reservoirId, String wellName) {
        try {
            return jdbc.queryForObject("""
                    SELECT id FROM project_well_heads
                    WHERE project_id=? AND project_gas_reservoir_id=? AND well_name=?
                    """, Long.class, projectId, reservoirId, wellName.trim());
        } catch (EmptyResultDataAccessException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前项目和气藏下不存在井：" + wellName);
        }
    }

    private long ensurePvt(long wellId, int pvtNo, String pvtName) {
        List<Long> ids = jdbc.query("SELECT id FROM project_well_pvt WHERE well_id=? AND pvt_no=?",
                (rs, rowNum) -> rs.getLong(1), wellId, pvtNo);
        if (!ids.isEmpty()) return ids.getFirst();
        return insertAndReturnKey("""
                INSERT INTO project_well_pvt(well_id,pvt_no,pvt_name,status,source_type)
                VALUES(?,?,?,'data-ready','productivity-reference')
                """, wellId, pvtNo, pvtName);
    }

    private long insertTest(SaveRequest request, long wellId, long pvtId) {
        Integer nextNo = jdbc.queryForObject("""
                SELECT COALESCE(MAX(test_no),0)+1 FROM project_well_productivity_test
                WHERE well_id=? AND operation_type='production' AND test_method='isochronal'
                FOR UPDATE
                """, Integer.class, wellId);
        int testNo = Objects.requireNonNullElse(nextNo, 1);
        return insertAndReturnKey("""
                INSERT INTO project_well_productivity_test
                  (project_id,project_gas_reservoir_id,well_id,well_name,pvt_id,operation_type,
                   test_method,test_no,test_name,test_date,status)
                VALUES(?,?,?,?,?,'production','isochronal',?,?,?,'data-ready')
                """, request.projectId(), request.gasReservoirId(), wellId, request.wellName().trim(),
                pvtId, testNo, "产能试井-" + testNo,
                Objects.requireNonNullElse(request.testDate(), LocalDate.now()));
    }

    private long updateAndValidateTest(SaveRequest request, long wellId, long pvtId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_well_productivity_test
                WHERE id=? AND project_id=? AND project_gas_reservoir_id=? AND well_id=?
                  AND test_method='isochronal'
                """, Long.class, request.testId(), request.projectId(), request.gasReservoirId(), wellId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "等时试井记录不存在");
        jdbc.update("UPDATE project_well_productivity_test SET pvt_id=?,test_date=? WHERE id=?",
                pvtId, Objects.requireNonNullElse(request.testDate(), LocalDate.now()), request.testId());
        return request.testId();
    }

    private long replaceInput(long testId, Input input) {
        jdbc.update("DELETE FROM project_well_productivity_test_input WHERE test_id=?", testId);
        return insertAndReturnKey("""
                INSERT INTO project_well_productivity_test_input
                  (test_id,maximum_formation_pressure,formation_temperature,one_point_alpha,gas_type,
                   specific_gravity,hydrogen_sulfide,carbon_dioxide,nitrogen,condensate_oil_density,
                   modification_method,deviation_factor_method,viscosity_method)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, testId, input.maximumFormationPressure(), input.formationTemperature(), input.onePointAlpha(),
                input.gasType(), input.specificGravity(), input.hydrogenSulfide(), input.carbonDioxide(),
                input.nitrogen(), input.condensateOilDensity(), input.modificationMethod(),
                input.deviationFactorMethod(), input.viscosityMethod());
    }

    private void replaceInputPoints(long inputId, List<InputPoint> points) {
        for (InputPoint point : points) jdbc.update("""
                INSERT INTO project_well_productivity_test_input_item
                  (input_id,test_point_number,test_daily_gas_production,reservoir_pressure,test_flow_pressure)
                VALUES(?,?,?,?,?)
                """, inputId, point.pointNumber(), point.gasProduction(), point.reservoirPressure(), point.flowPressure());
    }

    private long replaceOutput(long testId, String method, Result result) {
        List<Long> ids = jdbc.query("""
                SELECT id FROM project_well_productivity_binomial_output WHERE test_id=? AND pressure_method=?
                """, (rs, rowNum) -> rs.getLong(1), testId, method);
        if (!ids.isEmpty()) jdbc.update("DELETE FROM project_well_productivity_binomial_output WHERE id=?", ids.getFirst());
        return insertAndReturnKey("""
                INSERT INTO project_well_productivity_binomial_output
                  (test_id,pressure_method,darcy_seepage_coefficient,non_darcy_seepage_coefficient,
                   open_flow_capacity,gradient,intercept,r_squared,reliability_level,reliability_description,calculated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, testId, method, result.darcyCoefficient(), result.nonDarcyCoefficient(),
                result.openFlowCapacity(), result.gradient(), result.intercept(), result.rSquared(),
                result.reliabilityLevel(), result.reliabilityDescription(), new Timestamp(System.currentTimeMillis()));
    }

    private void replaceCurvePoints(long outputId, Result result) {
        insertCurve(outputId, "regularized", result.analysisPoints());
        insertCurve(outputId, "regression", result.regressionLine());
        insertCurve(outputId, "shifted-regression", result.transientLine());
    }

    private void insertCurve(long outputId, String type, List<CurvePoint> points) {
        if (points == null) return;
        for (int index = 0; index < points.size(); index++) {
            CurvePoint point = points.get(index);
            jdbc.update("""
                    INSERT INTO project_well_productivity_binomial_output_item
                      (output_id,curve_type,point_number,x_value,y_value,data_label)
                    VALUES(?,?,?,?,?,?)
                    """, outputId, type, index + 1, point.x(), point.y(), point.label());
        }
    }

    private void replaceIprPoints(long outputId, List<IprCurve> curves) {
        if (curves == null) return;
        for (int curveIndex = 0; curveIndex < curves.size(); curveIndex++) {
            IprCurve curve = curves.get(curveIndex);
            if (curve.points() == null) continue;
            for (int pointIndex = 0; pointIndex < curve.points().size(); pointIndex++) {
                IprPoint point = curve.points().get(pointIndex);
                String label = point.label() != null
                        ? point.label()
                        : "formationPressure:" + curve.formationPressure();
                jdbc.update("""
                        INSERT INTO project_well_productivity_binomial_ipr_item
                          (output_id,curve_number,point_number,gas_production,bottom_hole_flowing_pressure,data_label)
                        VALUES(?,?,?,?,?,?)
                        """, outputId, curveIndex + 1, pointIndex + 1, point.gasProduction(),
                        point.bottomHoleFlowingPressure(), label);
            }
        }
    }

    private Summary findSummary(long testId, long projectId, long reservoirId) {
        try {
            return jdbc.queryForObject("""
                    SELECT t.id,t.test_no,t.test_name,t.well_name,t.pvt_id,p.pvt_no,
                           COALESCE(o.pressure_method,''),t.status
                    FROM project_well_productivity_test t
                    JOIN project_well_pvt p ON p.id=t.pvt_id
                    LEFT JOIN project_well_productivity_binomial_output o ON o.test_id=t.id
                    WHERE t.id=? AND t.project_id=? AND t.project_gas_reservoir_id=?
                    ORDER BY o.updated_at DESC LIMIT 1
                    """, (rs, rowNum) -> new Summary(rs.getLong(1), rs.getInt(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5), rs.getInt(6), rs.getString(7), rs.getString(8)),
                    testId, projectId, reservoirId);
        } catch (EmptyResultDataAccessException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "等时试井记录不存在");
        }
    }

    private Input loadInput(long testId) {
        return jdbc.queryForObject("""
                SELECT id,maximum_formation_pressure,formation_temperature,one_point_alpha,gas_type,
                       specific_gravity,hydrogen_sulfide,carbon_dioxide,nitrogen,condensate_oil_density,
                       modification_method,deviation_factor_method,viscosity_method
                FROM project_well_productivity_test_input WHERE test_id=?
                """, (rs, rowNum) -> {
            long inputId = rs.getLong(1);
            List<InputPoint> points = jdbc.query("""
                    SELECT test_point_number,test_daily_gas_production,reservoir_pressure,test_flow_pressure
                    FROM project_well_productivity_test_input_item WHERE input_id=? ORDER BY test_point_number
                    """, (pointRs, pointRow) -> new InputPoint(pointRs.getInt(1), pointRs.getDouble(2),
                            pointRs.getDouble(3), pointRs.getDouble(4)), inputId);
            return new Input(rs.getDouble(2), rs.getDouble(3), nullableDouble(rs, 4), rs.getString(5),
                    nullableDouble(rs, 6), nullableDouble(rs, 7), nullableDouble(rs, 8), nullableDouble(rs, 9),
                    nullableDouble(rs, 10), rs.getString(11), rs.getString(12), rs.getString(13), points);
        }, testId);
    }

    private Result loadResult(long testId, String method) {
        return jdbc.queryForObject("""
                SELECT id,darcy_seepage_coefficient,non_darcy_seepage_coefficient,open_flow_capacity,
                       gradient,intercept,r_squared,reliability_level,reliability_description
                FROM project_well_productivity_binomial_output WHERE test_id=? AND pressure_method=?
                """, (rs, rowNum) -> {
            long outputId = rs.getLong(1);
            List<CurvePoint> analysis = loadCurve(outputId, "regularized");
            List<CurvePoint> regression = loadCurve(outputId, "regression");
            List<CurvePoint> transientLine = loadCurve(outputId, "shifted-regression");
            List<IprCurve> curves = loadIprCurves(outputId);
            return new Result(rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), nullableDouble(rs, 5),
                    nullableDouble(rs, 6), nullableDouble(rs, 7), rs.getString(8), rs.getString(9),
                    analysis, regression, transientLine, curves);
        }, testId, method);
    }

    private List<CurvePoint> loadCurve(long outputId, String type) {
        return jdbc.query("""
                SELECT x_value,y_value,data_label FROM project_well_productivity_binomial_output_item
                WHERE output_id=? AND curve_type=? AND is_deleted=0 ORDER BY point_number
                """, (rs, rowNum) -> new CurvePoint(rs.getDouble(1), rs.getDouble(2), rs.getString(3)), outputId, type);
    }

    private List<IprCurve> loadIprCurves(long outputId) {
        List<Integer> numbers = jdbc.query("""
                SELECT DISTINCT curve_number FROM project_well_productivity_binomial_ipr_item
                WHERE output_id=? AND is_deleted=0 ORDER BY curve_number
                """, (rs, rowNum) -> rs.getInt(1), outputId);
        List<IprCurve> curves = new ArrayList<>();
        for (Integer number : numbers) {
            List<IprPoint> points = jdbc.query("""
                    SELECT gas_production,bottom_hole_flowing_pressure,data_label
                    FROM project_well_productivity_binomial_ipr_item
                    WHERE output_id=? AND curve_number=? AND is_deleted=0 ORDER BY point_number
                    """, (rs, rowNum) -> new IprPoint(rs.getDouble(1), rs.getDouble(2), rs.getString(3)), outputId, number);
            Double formationPressure = points.isEmpty() ? null : parseFormationPressure(points.getFirst().label());
            curves.add(new IprCurve(formationPressure, points));
        }
        return curves;
    }

    private void validatePressureMethod(String method) {
        if (!PRESSURE_METHODS.contains(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的压力计算方法：" + method);
        }
    }

    private long insertAndReturnKey(String sql, Object... args) {
        var holder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("数据库未返回新增记录ID");
        return key.longValue();
    }

    private static Double nullableDouble(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        double value = rs.getDouble(index);
        return rs.wasNull() ? null : value;
    }

    private static Double parseFormationPressure(String label) {
        if (label == null || !label.startsWith("formationPressure:")) return null;
        try {
            return Double.valueOf(label.substring("formationPressure:".length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
