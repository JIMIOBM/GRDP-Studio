package com.grdp.studio.productivity;

import com.grdp.studio.common.BusinessException;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.grdp.studio.productivity.ProductivityTestModels.*;

@Service
public class ProductivityTestService {
    private static final Set<String> METHODS = Set.of(
            "back-pressure", "isochronal", "modified-isochronal", "one-point");
    private static final Set<String> PRESSURE_METHODS = Set.of(
            "pseudo-pressure", "pressure-squared", "pressure");
    private static final Set<String> RESULT_TYPES = Set.of("binomial", "exponential");
    private static final Set<String> BINOMIAL_CURVE_TYPES = Set.of(
            "regularized", "stable", "regression", "shifted-regression");
    private static final Set<String> EXPONENTIAL_CURVE_TYPES = Set.of(
            "analysis", "regression", "transient");
    private static final DateTimeFormatter NODE_TIME_FORMAT = DateTimeFormatter.ofPattern("yy.M.d.HHmm");
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;

    public ProductivityTestService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Summary> list(long projectId, long gasReservoirId, String wellName, String testMethod) {
        validateMethod(testMethod);
        return jdbc.query("""
                SELECT t.id,t.test_no,t.test_name,t.test_date,t.operation_type,t.test_method,t.status,
                       GROUP_CONCAT(DISTINCT o.pressure_method ORDER BY o.pressure_method SEPARATOR ',') AS pressure_methods
                FROM project_well_productivity_test t
                LEFT JOIN (
                    SELECT test_id,pressure_method FROM project_well_productivity_binomial_output
                    UNION ALL
                    SELECT test_id,pressure_method FROM project_well_productivity_exponential_output
                ) o ON o.test_id=t.id
                WHERE t.project_id=? AND t.project_gas_reservoir_id=? AND t.well_name=? AND t.test_method=?
                GROUP BY t.id,t.test_no,t.test_name,t.test_date,t.operation_type,t.test_method,t.status
                ORDER BY t.test_no,t.test_date,t.id
                """, (rs, row) -> new Summary(
                rs.getLong("id"), rs.getInt("test_no"), rs.getString("test_name"),
                rs.getDate("test_date").toLocalDate(), rs.getString("operation_type"),
                rs.getString("test_method"), rs.getString("status"),
                split(rs.getString("pressure_methods"))),
                projectId, gasReservoirId, wellName.trim(), testMethod);
    }

    public Detail detail(long testId, long projectId, long gasReservoirId, String wellName) {
        Map<String, Object> test = one("""
                SELECT id,pvt_id,test_no,test_name,test_date,operation_type,test_method,well_name,well_type,status
                FROM project_well_productivity_test
                WHERE id=? AND project_id=? AND project_gas_reservoir_id=? AND well_name=?
                """, new Object[]{testId, projectId, gasReservoirId, wellName.trim()}, "试井记录不存在或不属于当前井");
        Map<String, Object> input = one("SELECT * FROM project_well_productivity_test_input WHERE test_id=?",
                testId, "试井输入不存在");
        long inputId = number(input.get("id")).longValue();
        List<InputItem> items = jdbc.query("""
                SELECT test_point_number,test_daily_gas_production,reservoir_pressure,test_flow_pressure
                FROM project_well_productivity_test_input_item WHERE input_id=? ORDER BY test_point_number
                """, (rs, row) -> new InputItem(rs.getInt(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)), inputId);

        List<Map<String, Object>> binomialOutputs = jdbc.queryForList("""
                SELECT * FROM project_well_productivity_binomial_output
                WHERE test_id=? ORDER BY updated_at DESC,id DESC
                """, testId);
        List<Map<String, Object>> exponentialOutputs = jdbc.queryForList("""
                SELECT * FROM project_well_productivity_exponential_output
                WHERE test_id=? ORDER BY updated_at DESC,id DESC
                """, testId);
        List<Result> results = new ArrayList<>();
        binomialOutputs.stream().map(this::binomialResult).forEach(results::add);
        exponentialOutputs.stream().map(this::exponentialResult).forEach(results::add);
        Result result = results.isEmpty() ? null : results.getFirst();
        return new Detail(testId, longValue(test.get("pvt_id")), number(test.get("test_no")).intValue(),
                string(test.get("test_name")), date(test.get("test_date")), string(test.get("operation_type")),
                string(test.get("test_method")), string(test.get("well_name")), string(test.get("well_type")),
                string(test.get("status")), input(input), items, result, List.of(), results);
    }

    @Transactional
    public SaveResponse save(SaveRequest request) {
        validate(request);
        long wellId = requireWellId(request.projectId(), request.gasReservoirId(), request.wellName());
        if (request.pvtId() != null) requirePvt(request.pvtId(), wellId);
        boolean created = request.testId() == null;
        int testNo = created ? nextTestNo(wellId, request.operationType(), request.testMethod())
                : requireTest(request.testId(), wellId);
        String testName = created ? createTestName(request.testMethod(), request.operationType(), testNo)
                : requireTestName(request.testId());
        long testId = created ? insertTest(request, wellId, testNo, testName) : request.testId();
        if (!created) {
            jdbc.update("""
                    UPDATE project_well_productivity_test SET pvt_id=?,test_date=?,well_type=?,status='calculated'
                    WHERE id=?
                    """, request.pvtId(), Date.valueOf(request.testDate()), blankToNull(request.wellType()), testId);
        }

        if (created || request.replaceInput()) {
            if (!created) {
                jdbc.update("DELETE FROM project_well_productivity_binomial_output WHERE test_id=?", testId);
                jdbc.update("DELETE FROM project_well_productivity_exponential_output WHERE test_id=?", testId);
            }
            long inputId = replaceInput(testId, request.input(), request.inputItems());
            if (inputId < 1) throw new BusinessException(500, "保存试井输入失败");
        }
        replaceResult(testId, request.result());
        jdbc.update("UPDATE project_well_productivity_test SET status='calculated' WHERE id=?", testId);
        return new SaveResponse(testId, testNo, testName);
    }

    public List<InputItem> importRows(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(400, "请选择Excel或CSV文件");
        String name = String.valueOf(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        try {
            List<List<String>> raw = name.endsWith(".csv") ? readCsv(file) : readWorkbook(file);
            List<InputItem> rows = normalizeRows(raw);
            if (rows.isEmpty()) throw new BusinessException(400, "文件中没有有效的四列试井数据");
            return rows;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(400, "文件解析失败，请检查格式和四列数据");
        }
    }

    private List<List<String>> readCsv(MultipartFile file) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) rows.add(parseCsvLine(line));
        }
        return rows;
    }

    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') value.append(line.charAt(i++));
                else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                cells.add(value.toString().trim()); value.setLength(0);
            } else value.append(c);
        }
        cells.add(value.toString().trim());
        return cells;
    }

    private List<List<String>> readWorkbook(MultipartFile file) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int column = 0; column < Math.max(4, row.getLastCellNum()); column++) {
                    cells.add(formatter.formatCellValue(row.getCell(column)).trim());
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private List<InputItem> normalizeRows(List<List<String>> raw) {
        if (raw.isEmpty()) return List.of();
        int[] columns = headerColumns(raw.getFirst());
        int start = columns == null ? 0 : 1;
        if (columns == null) columns = new int[]{0, 1, 2, 3};
        List<InputItem> rows = new ArrayList<>();
        for (int index = start; index < raw.size(); index++) {
            List<String> source = raw.get(index);
            try {
                int point = (int) Math.round(parse(cell(source, columns[0])));
                double rate = parse(cell(source, columns[1]));
                double reservoir = parse(cell(source, columns[2]));
                double flowing = parse(cell(source, columns[3]));
                if (point > 0 && rate > 0 && reservoir > 0 && flowing >= 0) {
                    rows.add(new InputItem(point, rate, reservoir, flowing));
                }
            } catch (RuntimeException ignored) {
                // 空行、标题行和无效行忽略；至少一行有效数据才允许继续。
            }
        }
        return rows;
    }

    private int[] headerColumns(List<String> header) {
        int[] result = {-1, -1, -1, -1};
        for (int index = 0; index < header.size(); index++) {
            String key = header.get(index).toLowerCase(Locale.ROOT).replaceAll("[\\s_()/（）-]", "");
            if (contains(key, "测点序号", "序号", "testpointnumber", "pointnumber")) result[0] = index;
            else if (contains(key, "测试气产量", "气产量", "testdailygasproduction", "gasrate", "qsc")) result[1] = index;
            else if (contains(key, "地层压力", "恢复压力", "reservoirpressure", "reserviorpressure")) result[2] = index;
            else if (contains(key, "测试流压", "井底流压", "testflowpressure", "flowingpressure", "pwf")) result[3] = index;
        }
        return Arrays.stream(result).allMatch(value -> value >= 0) ? result : null;
    }

    private boolean contains(String value, String... candidates) {
        return Arrays.stream(candidates).anyMatch(value::contains);
    }

    private void validate(SaveRequest request) {
        validateMethod(request.testMethod());
        if (!Set.of("injection", "production").contains(request.operationType()))
            throw new BusinessException(400, "注采类型不正确");
        Result result = request.result();
        String resultType = resultType(result);
        if (!RESULT_TYPES.contains(resultType)) throw new BusinessException(400, "计算结果类型不正确");
        if (!PRESSURE_METHODS.contains(result.pressureMethod())) throw new BusinessException(400, "压力处理方法不正确");
        if ("injection".equals(request.operationType()) && !"exponential".equals(resultType))
            throw new BusinessException(400, "注气当前仅支持指数式计算");
        if ("pseudo-pressure".equals(result.pressureMethod()) && request.pvtId() == null)
            throw new BusinessException(400, "拟压力方法必须选择PVT");
        finite(request.input().maximumFormationPressure(), "最大地层压力");
        finite(request.input().formationTemperature(), "地层温度");
        if ("pseudo-pressure".equals(result.pressureMethod())) {
            if (request.input().gasType() == null || request.input().gasType().isBlank())
                throw new BusinessException(400, "天然气类型不能为空");
            positive(request.input().specificGravity(), "天然气比重");
        }
        if (result.evaluationId() != null && result.evaluationId() <= 0)
            throw new BusinessException(400, "原平台计算记录编号必须大于0");
        if ("exponential".equals(resultType)) {
            positive(result.productivityCoefficient(), "产能系数C");
            positive(result.productivityExponent(), "产能指数n");
            positive(result.openFlowCapacity(), "无阻流量");
            if (result.chartPoints() == null || result.chartPoints().isEmpty())
                throw new BusinessException(400, "指数式结果缺少分析曲线点");
            if (result.iprPoints() == null || result.iprPoints().isEmpty())
                throw new BusinessException(400, "指数式结果缺少IPR曲线点");
        } else {
            finite(result.darcySeepageCoefficient(), "达西渗流系数A");
            finite(result.nonDarcySeepageCoefficient(), "非达西渗流系数B");
            finite(result.openFlowCapacity(), "无阻流量");
        }
        request.inputItems().forEach(item -> {
            finite(item.testDailyGasProduction(), "测试气产量");
            finite(item.reservoirPressure(), "地层/恢复压力");
            finite(item.testFlowPressure(), "测试流压");
        });
        Set<String> curveTypes = "exponential".equals(resultType)
                ? EXPONENTIAL_CURVE_TYPES : BINOMIAL_CURVE_TYPES;
        if (result.chartPoints() != null) result.chartPoints().forEach(point -> {
            if (!curveTypes.contains(point.curveType())) throw new BusinessException(400, "分析曲线类型不正确");
            if (point.sourcePointNumber() != null && point.sourcePointNumber() <= 0)
                throw new BusinessException(400, "来源测点序号必须大于0");
            finite(point.xValue(), "分析图横坐标"); finite(point.yValue(), "分析图纵坐标");
        });
        if (result.iprPoints() != null) result.iprPoints().forEach(point -> {
            if ("exponential".equals(resultType)) positive(point.formationPressure(), "IPR曲线地层压力");
            finite(point.gasProduction(), "IPR曲线产气量");
            finite(point.bottomHoleFlowingPressure(), "IPR曲线井底流压");
        });
    }

    private void validateMethod(String method) {
        if (!METHODS.contains(method)) throw new BusinessException(400, "试井方法不正确");
    }

    private long requireWellId(long projectId, long gasReservoirId, String wellName) {
        List<Long> ids = jdbc.query("""
                SELECT id FROM project_well_heads
                WHERE project_id=? AND project_gas_reservoir_id=? AND well_name=? ORDER BY id
                """, (rs, row) -> rs.getLong(1), projectId, gasReservoirId, wellName.trim());
        if (ids.isEmpty()) throw new BusinessException(404, "没有找到当前项目、气藏和井名对应的井");
        if (ids.size() > 1) throw new BusinessException(409, "当前项目、气藏和井名对应多口井");
        return ids.getFirst();
    }

    private void requirePvt(Long pvtId, long wellId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM project_well_pvt WHERE id=? AND well_id=?",
                Integer.class, pvtId, wellId);
        if (count == null || count != 1) throw new BusinessException(400, "所选PVT不属于当前井");
    }

    private int nextTestNo(long wellId, String operation, String method) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(test_no),0)+1 FROM project_well_productivity_test
                WHERE well_id=? AND operation_type=? AND test_method=?
                """, Integer.class, wellId, operation, method);
        return value == null ? 1 : value;
    }

    private int requireTest(long testId, long wellId) {
        List<Integer> values = jdbc.query("SELECT test_no FROM project_well_productivity_test WHERE id=? AND well_id=?",
                (rs, row) -> rs.getInt(1), testId, wellId);
        if (values.isEmpty()) throw new BusinessException(404, "试井记录不存在或不属于当前井");
        return values.getFirst();
    }

    private long insertTest(SaveRequest request, long wellId, int testNo, String name) {
        return insert("""
                INSERT INTO project_well_productivity_test
                (project_id,project_gas_reservoir_id,well_id,well_name,pvt_id,operation_type,test_method,
                 test_no,test_name,test_date,well_type,status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'calculated')
                """, request.projectId(), request.gasReservoirId(), wellId, request.wellName().trim(),
                request.pvtId(), request.operationType(), request.testMethod(), testNo, name,
                Date.valueOf(request.testDate()), blankToNull(request.wellType()));
    }

    private long replaceInput(long testId, Input input, List<InputItem> rows) {
        jdbc.update("DELETE FROM project_well_productivity_test_input WHERE test_id=?", testId);
        long inputId = insert("""
                INSERT INTO project_well_productivity_test_input
                (test_id,maximum_formation_pressure,formation_temperature,one_point_alpha,gas_type,specific_gravity,
                 hydrogen_sulfide,carbon_dioxide,nitrogen,condensate_oil_density,modification_method,
                 deviation_factor_method,viscosity_method) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, testId, input.maximumFormationPressure(), input.formationTemperature(), input.onePointAlpha(),
                input.gasType() == null ? "" : input.gasType().trim(), zero(input.specificGravity()),
                zero(input.hydrogenSulfide()), zero(input.carbonDioxide()),
                zero(input.nitrogen()), input.condensateOilDensity(), blankToNull(input.modificationMethod()),
                blankToNull(input.deviationFactorMethod()), blankToNull(input.viscosityMethod()));
        for (InputItem row : rows) jdbc.update("""
                INSERT INTO project_well_productivity_test_input_item
                (input_id,test_point_number,test_daily_gas_production,reservoir_pressure,test_flow_pressure)
                VALUES (?,?,?,?,?)
                """, inputId, row.testPointNumber(), row.testDailyGasProduction(),
                row.reservoirPressure(), row.testFlowPressure());
        return inputId;
    }

    private void replaceResult(long testId, Result result) {
        if ("exponential".equals(resultType(result))) {
            replaceExponentialResult(testId, result);
            return;
        }
        jdbc.update("DELETE FROM project_well_productivity_binomial_output WHERE test_id=? AND pressure_method=?",
                testId, result.pressureMethod());
        long outputId = insert("""
                INSERT INTO project_well_productivity_binomial_output
                (test_id,pressure_method,darcy_seepage_coefficient,non_darcy_seepage_coefficient,open_flow_capacity,
                 gradient,intercept,r_squared,reliability_level,reliability_description)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, testId, result.pressureMethod(), result.darcySeepageCoefficient(),
                result.nonDarcySeepageCoefficient(), result.openFlowCapacity(), result.gradient(), result.intercept(),
                result.rSquared(), result.reliabilityLevel(), blankToNull(result.reliabilityDescription()));
        if (result.chartPoints() != null) for (ChartPoint point : result.chartPoints()) jdbc.update("""
                INSERT INTO project_well_productivity_binomial_output_item
                (output_id,curve_type,point_number,x_value,y_value,is_deleted,data_label) VALUES (?,?,?,?,?,?,?)
                """, outputId, point.curveType(), point.pointNumber(), point.xValue(), point.yValue(),
                point.deleted(), blankToNull(point.dataLabel()));
        if (result.iprPoints() != null) for (IprPoint point : result.iprPoints()) jdbc.update("""
                INSERT INTO project_well_productivity_binomial_ipr_item
                (output_id,curve_number,point_number,gas_production,bottom_hole_flowing_pressure,is_deleted,data_label)
                VALUES (?,?,?,?,?,?,?)
                """, outputId, point.curveNumber(), point.pointNumber(), point.gasProduction(),
                point.bottomHoleFlowingPressure(), point.deleted(), blankToNull(point.dataLabel()));
    }

    private void replaceExponentialResult(long testId, Result result) {
        jdbc.update("DELETE FROM project_well_productivity_exponential_output WHERE test_id=? AND pressure_method=?",
                testId, result.pressureMethod());
        long outputId = insert("""
                INSERT INTO project_well_productivity_exponential_output
                (test_id,pressure_method,productivity_coefficient,productivity_exponent,open_flow_capacity,
                 r_squared,reliability_description,calculated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, testId, result.pressureMethod(), result.productivityCoefficient(),
                result.productivityExponent(), result.openFlowCapacity(), result.rSquared(),
                blankToNull(result.reliabilityDescription()), LocalDateTime.now(CHINA_ZONE));
        for (ChartPoint point : result.chartPoints()) jdbc.update("""
                INSERT INTO project_well_productivity_exponential_output_item
                (output_id,curve_type,point_number,source_point_number,x_value,y_value,is_deleted,data_label)
                VALUES (?,?,?,?,?,?,?,?)
                """, outputId, point.curveType(), point.pointNumber(), point.sourcePointNumber(),
                point.xValue(), point.yValue(), point.deleted(), blankToNull(point.dataLabel()));
        for (IprPoint point : result.iprPoints()) jdbc.update("""
                INSERT INTO project_well_productivity_exponential_ipr_item
                (output_id,curve_number,formation_pressure,point_number,gas_production,
                 bottom_hole_flowing_pressure,is_deleted,data_label) VALUES (?,?,?,?,?,?,?,?)
                """, outputId, point.curveNumber(), point.formationPressure(), point.pointNumber(),
                point.gasProduction(), point.bottomHoleFlowingPressure(), point.deleted(),
                blankToNull(point.dataLabel()));
    }

    private Result binomialResult(Map<String, Object> output) {
        long outputId = number(output.get("id")).longValue();
        List<ChartPoint> charts = jdbc.query("""
                SELECT curve_type,point_number,x_value,y_value,is_deleted,data_label
                FROM project_well_productivity_binomial_output_item WHERE output_id=? ORDER BY curve_type,point_number
                """, (rs, row) -> new ChartPoint(rs.getString(1), rs.getInt(2), null,
                rs.getDouble(3), rs.getDouble(4), rs.getBoolean(5), rs.getString(6)), outputId);
        List<IprPoint> ipr = jdbc.query("""
                SELECT curve_number,point_number,gas_production,bottom_hole_flowing_pressure,is_deleted,data_label
                FROM project_well_productivity_binomial_ipr_item WHERE output_id=? ORDER BY curve_number,point_number
                """, (rs, row) -> new IprPoint(rs.getInt(1), rs.getInt(2), null,
                rs.getDouble(3), rs.getDouble(4), rs.getBoolean(5), rs.getString(6)), outputId);
        return new Result("binomial", string(output.get("pressure_method")),
                null,
                decimal(output.get("darcy_seepage_coefficient")),
                decimal(output.get("non_darcy_seepage_coefficient")), decimal(output.get("open_flow_capacity")),
                null, null,
                decimal(output.get("gradient")), decimal(output.get("intercept")), decimal(output.get("r_squared")),
                integer(output.get("reliability_level")), string(output.get("reliability_description")), charts, ipr);
    }

    private Result exponentialResult(Map<String, Object> output) {
        long outputId = number(output.get("id")).longValue();
        List<ChartPoint> charts = jdbc.query("""
                SELECT curve_type,point_number,source_point_number,x_value,y_value,is_deleted,data_label
                FROM project_well_productivity_exponential_output_item
                WHERE output_id=? ORDER BY curve_type,point_number
                """, (rs, row) -> new ChartPoint(rs.getString(1), rs.getInt(2),
                nullableInteger(rs, 3), rs.getDouble(4), rs.getDouble(5), rs.getBoolean(6), rs.getString(7)), outputId);
        List<IprPoint> ipr = jdbc.query("""
                SELECT curve_number,point_number,formation_pressure,gas_production,
                       bottom_hole_flowing_pressure,is_deleted,data_label
                FROM project_well_productivity_exponential_ipr_item
                WHERE output_id=? ORDER BY curve_number,point_number
                """, (rs, row) -> new IprPoint(rs.getInt(1), rs.getInt(2), rs.getDouble(3),
                rs.getDouble(4), rs.getDouble(5), rs.getBoolean(6), rs.getString(7)), outputId);
        return new Result("exponential", string(output.get("pressure_method")), null,
                null, null, decimal(output.get("open_flow_capacity")),
                decimal(output.get("productivity_coefficient")), decimal(output.get("productivity_exponent")),
                null, null, decimal(output.get("r_squared")), null,
                string(output.get("reliability_description")), charts, ipr);
    }

    private Input input(Map<String, Object> value) {
        return new Input(decimal(value.get("maximum_formation_pressure")), decimal(value.get("formation_temperature")),
                decimal(value.get("one_point_alpha")), string(value.get("gas_type")), decimal(value.get("specific_gravity")),
                decimal(value.get("hydrogen_sulfide")), decimal(value.get("carbon_dioxide")), decimal(value.get("nitrogen")),
                decimal(value.get("condensate_oil_density")), string(value.get("modification_method")),
                string(value.get("deviation_factor_method")), string(value.get("viscosity_method")));
    }

    private Map<String, Object> one(String sql, Object argument, String message) {
        List<Map<String, Object>> rows = argument instanceof Object[] values
                ? jdbc.queryForList(sql, values)
                : jdbc.queryForList(sql, argument);
        if (rows.isEmpty()) throw new BusinessException(404, message);
        return rows.getFirst();
    }

    private long insert(String sql, Object... values) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement;
        }, keys);
        if (keys.getKey() == null) throw new BusinessException(500, "数据库未返回新记录编号");
        return keys.getKey().longValue();
    }

    private List<String> split(String value) { return value == null || value.isBlank() ? List.of() : List.of(value.split(",")); }
    private String operationLabel(String value) { return "injection".equals(value) ? "注气" : "采气"; }
    private String methodLabel(String value) { return Map.of("back-pressure", "回压", "isochronal", "等时", "modified-isochronal", "修正等时", "one-point", "一点法").get(value); }
    private String createTestName(String method, String operation, int testNo) {
        if ("modified-isochronal".equals(method)) {
            return "修正等时-" + LocalDateTime.now(CHINA_ZONE).format(NODE_TIME_FORMAT);
        }
        return operationLabel(operation) + methodLabel(method) + "试井" + testNo;
    }
    private String requireTestName(long testId) {
        return jdbc.queryForObject("SELECT test_name FROM project_well_productivity_test WHERE id=?", String.class, testId);
    }
    private String cell(List<String> row, int index) { return index < row.size() ? row.get(index) : ""; }
    private double parse(String value) { return Double.parseDouble(value.replace(",", "").trim()); }
    private void finite(Double value, String field) { if (value == null || !Double.isFinite(value)) throw new BusinessException(400, field + "必须是有效数字"); }
    private void positive(Double value, String field) { finite(value, field); if (value <= 0) throw new BusinessException(400, field + "必须大于0"); }
    private String resultType(Result result) { return result.calculationResultType() == null || result.calculationResultType().isBlank() ? "binomial" : result.calculationResultType().trim(); }
    private Integer nullableInteger(java.sql.ResultSet resultSet, int column) throws java.sql.SQLException { int value = resultSet.getInt(column); return resultSet.wasNull() ? null : value; }
    private Number number(Object value) { return (Number) value; }
    private Long longValue(Object value) { return value == null ? null : number(value).longValue(); }
    private Double decimal(Object value) { return value == null ? null : number(value).doubleValue(); }
    private Integer integer(Object value) {
        if (value == null) return null;
        return value instanceof Number number ? number.intValue() : Integer.valueOf(String.valueOf(value));
    }
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private LocalDate date(Object value) { return value instanceof Date d ? d.toLocalDate() : LocalDate.parse(String.valueOf(value)); }
    private double zero(Double value) { return value == null ? 0 : value; }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
