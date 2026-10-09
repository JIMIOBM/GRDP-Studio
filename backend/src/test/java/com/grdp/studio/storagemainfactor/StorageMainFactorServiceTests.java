package com.grdp.studio.storagemainfactor;

import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.AUTO;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.MISSING;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 库级主控因素分析：改造后只比较**地层压力**一个因素。
 *
 * <p>理论值来自原平台物质平衡方程工具箱，实际值来自库内实测静压；
 * 两者都不由前端回传，所以这里不再有"理论/实际值回传"的用例。
 */
class StorageMainFactorServiceTests {

    SingleConnectionDataSource dataSource;
    JdbcTemplate jdbc;
    StorageMainFactorService service;

    @BeforeEach
    void setup() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:mf_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE project_summaries(id BIGINT PRIMARY KEY,delete_status INT)");
        jdbc.execute("CREATE TABLE project_gas_reservoir(id BIGINT PRIMARY KEY,project_id BIGINT)");
        jdbc.execute("CREATE TABLE project_storage(id BIGINT PRIMARY KEY,project_id BIGINT,gas_reservoir_id BIGINT)");
        jdbc.execute("CREATE TABLE project_well_heads(id BIGINT PRIMARY KEY,project_id BIGINT,project_gas_reservoir_id BIGINT,well_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE project_storage_well(storage_id BIGINT,well_id BIGINT)");
        jdbc.execute("CREATE TABLE dynamic_original_gas_in_place(id BIGINT PRIMARY KEY,project_id BIGINT,project_gas_reservoir_id BIGINT,well_name VARCHAR(100),dynamic_original_gas_inplace_method BIGINT,create_time TIMESTAMP,update_time TIMESTAMP)");
        jdbc.execute("""
                CREATE TABLE dynamic_original_gas_in_place_by_mb_input(
                  id BIGINT PRIMARY KEY,dynamic_original_gas_in_place_id BIGINT,gas_type VARCHAR(20),
                  specific_gravity DOUBLE,hydrogen_sulfide DOUBLE,carbon_dioxide DOUBLE,nitrogen DOUBLE,
                  modification_method BIGINT,deviation_factor_method BIGINT,viscosity_method BIGINT,original_pressure DOUBLE,temperature DOUBLE,
                  rock_compression_coefficient DOUBLE,water_compression_coefficient DOUBLE,water_saturation DOUBLE,
                  reservoir_porosity DOUBLE,shale_rock_desity DOUBLE,langmuir_pressure DOUBLE,langmuir_volume DOUBLE,
                  gas_reservoir_type BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE dynamic_original_gas_in_place_output(
                  id BIGINT PRIMARY KEY,dynamic_original_gas_in_place_id BIGINT,project_id BIGINT,
                  project_gas_reservoir_id BIGINT,well_name VARCHAR(100),dynamic_original_gas_inplace_method BIGINT,
                  original_gas_volume DOUBLE,rsquared DOUBLE,reliablity INT)
                """);
        jdbc.execute("""
                CREATE TABLE dynamic_original_gas_in_place_by_mb_input_item(
                  id BIGINT PRIMARY KEY,dynamic_original_gas_inplace_by_mb_input_id BIGINT,date TIMESTAMP,
                  formation_pressure DOUBLE,cumulative_production DOUBLE,cumulative_water_production DOUBLE,is_deleted BOOLEAN)
                """);
        jdbc.execute("CREATE TABLE project_static_pressure_data(id BIGINT PRIMARY KEY,well_name VARCHAR(100),date TIMESTAMP,reservior_pressure DOUBLE,project_gas_reservoir_id BIGINT,project_id BIGINT)");
        jdbc.execute("""
                CREATE TABLE project_reservoir_main_factor(
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  project_id BIGINT NOT NULL, gas_reservoir_id BIGINT NOT NULL, storage_id BIGINT NOT NULL,
                  gas_reservoir_type INT, original_pressure DOUBLE, formation_temperature DOUBLE,
                  original_gas_in_place DOUBLE, cumulative_gas_production DOUBLE,
                  rock_compression_coefficient DOUBLE, water_compression_coefficient DOUBLE,
                  water_saturation DOUBLE, gas_type INT, specific_gravity DOUBLE,
                  h2s_mole_fraction DOUBLE, co2_mole_fraction DOUBLE, n2_mole_fraction DOUBLE,
                  modification_method INT, deviation_factor_method INT, viscosity_method INT,
                  theoretical_pressure DOUBLE, actual_pressure DOUBLE,
                  created_at TIMESTAMP, updated_at TIMESTAMP,
                  UNIQUE (project_id, gas_reservoir_id, storage_id))
                """);

        jdbc.update("INSERT INTO project_summaries VALUES(8,0)");
        jdbc.update("INSERT INTO project_gas_reservoir VALUES(5,8)");
        jdbc.update("INSERT INTO project_storage VALUES(2,8,5)");
        jdbc.update("INSERT INTO project_well_heads VALUES(21,8,5,'X-1')");
        jdbc.update("INSERT INTO project_storage_well VALUES(2,21)");
        service = new StorageMainFactorService(jdbc, new StorageCatalogService(jdbc), platformReturning(null),
                new StorageMainFactorSavedStorage(jdbc));
    }

    @AfterEach
    void cleanup() {
        dataSource.destroy();
    }

    /** 用匿名子类替代 HTTP：Task 3 的类与方法都不是 final，正是为了这里能替换掉网络调用。 */
    static MaterialBalanceEquationClient platformReturning(Double pressure) {
        return new MaterialBalanceEquationClient(null, new ObjectMapper()) {
            @Override
            public Double calculateFormationPressure(long projectId, ToolboxInput input, Map<String, String> headers) {
                return pressure;
            }
        };
    }

    /** 插入一口井的物质平衡输入与同源的动态储量；数值取自本机实测。 */
    void measuredSource() {
        jdbc.update("INSERT INTO dynamic_original_gas_in_place VALUES(1,8,5,'X-1',2,TIMESTAMP '2020-01-01 00:00:00',TIMESTAMP '2020-01-01 00:00:00')");
        jdbc.update("""
                INSERT INTO dynamic_original_gas_in_place_by_mb_input
                (id,dynamic_original_gas_in_place_id,gas_type,specific_gravity,hydrogen_sulfide,carbon_dioxide,nitrogen,
                 modification_method,deviation_factor_method,viscosity_method,original_pressure,temperature,
                 rock_compression_coefficient,water_compression_coefficient,water_saturation,
                 reservoir_porosity,shale_rock_desity,langmuir_pressure,langmuir_volume,gas_reservoir_type)
                VALUES(11,1,'干气',0.58,0.0462,0.0396,0,0,0,0,50000000,353.15,
                       1.0E-10,3.744512763331313E-10,0.26158040988077613,0,0,0,0,1)
                """);
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_output VALUES(101,1,8,5,'X-1',2,2339827089.8104453,1.0,2)");
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_by_mb_input_item VALUES(201,11,TIMESTAMP '2013-03-29 00:00:00',13557268.419307435,1230637276.8,0.0,FALSE)");
    }

    /** 本机实测：实测静压 13557268.419307435 Pa = 13.557268419307435 MPa。 */
    static final double MEASURED_MPA = 13.557268419307435;

    @Test
    void rejectsScopeThatIsNotTheGivenStorage() {
        assertThrows(ResponseStatusException.class, () -> service.context(8, 5, 999));
    }

    @Test
    void prefillsToolboxInputsConvertedToPlatformUnits() {
        measuredSource();
        var context = service.context(8, 5, 2);
        var inputs = context.inputs();

        // 已是应用口径的列必须原样透传，不能被再缩放一次
        assertEquals(50_000_000d, inputs.originalPressure(), 1e-6);
        assertEquals(353.15, inputs.formationTemperature(), 1e-9);
        assertEquals(0.26158040988077613, inputs.waterSaturation(), 1e-15);
        assertEquals(1.0E-10, inputs.rockCompressionCoefficient(), 1e-20);
        assertEquals(1, inputs.gasReservoirType());
        // 地质储量存的是 m³，应用口径要 10⁸m³
        assertEquals(23.398270898104453, inputs.originalGasInPlace(), 1e-9);
        // 累产气量同表存 m³
        assertEquals(12.306372768, inputs.cumulativeGasProduction(), 1e-9);
        // 组分在**应用口径**下保持小数（库里存的就是 0.0462）；
        // 换算成原平台 calc 要的百分数由 toolboxPayload 统一完成（inputRange 里 maxH2SMoleFraction=100）。
        assertEquals(0, inputs.gasPvtParam().gasType());
        assertEquals(0.0462, inputs.gasPvtParam().h2SMoleFraction(), 1e-12);
        assertEquals(0.0396, inputs.gasPvtParam().co2MoleFraction(), 1e-12);
    }

    @Test
    void reportsMissingDataAsExplicitWarningsInsteadOfInventingZero() {
        var context = service.context(8, 5, 2);

        assertEquals(MISSING, context.measuredPressure().source());
        assertNull(context.measuredPressure().value(), "没有实测数据时不能拿 0 顶替，0 会算出一个看着正常的错差异");
        assertNull(context.inputs(), "没有代表井就没有工具箱入参");
        assertFalse(context.warnings().isEmpty(), "库级无数据时必须给出中文说明，而不是空表");
    }

    @Test
    void readsGasVolumeFromTheSameSourceRowAsTheInputs() {
        measuredSource();
        // 另一条来源记录：method 6 也有一个储量，但与本井的物质平衡输入不是同一行
        jdbc.update("INSERT INTO dynamic_original_gas_in_place VALUES(2,8,5,'X-1',6,TIMESTAMP '2021-01-01 00:00:00',TIMESTAMP '2021-01-01 00:00:00')");
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_output VALUES(102,2,8,5,'X-1',6,2940228666.6234493,0.61,1)");

        // 动态地质储量 G 是物质平衡工具箱的必填入参，必须与其它入参同源
        assertEquals(23.398270898104453, service.context(8, 5, 2).inputs().originalGasInPlace(), 1e-9);
    }

    @Test
    void fallsBackToStaticPressureTableWhenNoMeasurementsExist() {
        // 真实库里这一列是 **Pa**（实测 19650000 ~ 31608000），不是 MPa。
        // 早期版本的 fixture 写的是 19.65 并断言 19.65，等于把这个错误钉死成"通过"。
        jdbc.update("INSERT INTO project_static_pressure_data VALUES(301,'X-1',TIMESTAMP '2010-06-10 00:00:00',19650000,5,8)");

        var measured = service.context(8, 5, 2).measuredPressure();

        assertEquals(AUTO, measured.source());
        assertEquals(19.65, measured.value(), 1e-9);
        assertTrue(measured.note().contains("静态压力"), "要说明这个值不是实测静压而是静态压力表：" + measured.note());
    }

    @Test
    void calculateWithoutInputsReportsMissingTheoryButStillShowsTheMeasuredValue() {
        // 库级没有入参（本机现状）时不能把整个请求拒掉：实际值仍要显示，
        // 只是理论值空缺并说明原因。
        measuredSource();

        var result = service.calculate(new CalculateRequest(8, 5, 2, null), Map.of());

        assertNull(result.formationPressure());
        assertEquals(MISSING, result.formationPressureSource());
        assertEquals(MEASURED_MPA, result.measuredPressure().value(), 1e-9);
        assertNull(result.difference(), "理论值缺失时不能给出差异");
        assertNull(result.deviationPercent());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("没有物质平衡方程输入")));
    }

    @Test
    void calculateKeepsTheMeasuredPressureProvenance() {
        // "实测静压：X-1"这类溯源信息不能因为点了一次计算就丢掉，
        // 否则用户分不清这个实际值是哪口井的。
        measuredSource();

        var result = service.calculate(new CalculateRequest(8, 5, 2, null), Map.of());

        assertEquals(AUTO, result.measuredPressure().source());
        assertTrue(result.measuredPressure().note() != null && result.measuredPressure().note().contains("X-1"),
                "自动值要保留它的来源说明");
    }

    @Test
    void prefersTheGasVolumeWhoseMethodMatchesTheSourceRow() {
        // 同一个来源行挂多条输出时，必须挑与母行 method 一致的那条；
        // 靠 o.id 取第一条会随插入顺序漂移（本库 G 的跨度是 19.85~33.05 ×10⁸m³）。
        measuredSource();
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_output VALUES(103,1,8,5,'X-1',7,9900000000.0,0.5,2)");
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_output VALUES(104,1,8,5,'X-1',2,1985476101.8,0.88,1)");

        // 母行 method=2 → 应取 method=2 的那条 (1.9854761018e9 m³ = 19.854761018 ×10⁸m³)
        assertEquals(19.854761018, service.context(8, 5, 2).inputs().originalGasInPlace(), 1e-9);
    }

    @Test
    void calculateUsesThePlatformPressureAndReportsTheDifference() {
        service = new StorageMainFactorService(jdbc, new StorageCatalogService(jdbc), platformReturning(32.1534),
                new StorageMainFactorSavedStorage(jdbc));
        measuredSource();
        var inputs = service.context(8, 5, 2).inputs();

        var result = service.calculate(new CalculateRequest(8, 5, 2, inputs), Map.of());

        assertEquals(32.1534, result.formationPressure(), 1e-9);
        assertEquals(AUTO, result.formationPressureSource());
        // 差异 = 实际 − 理论，负值表示实测低于理论
        assertEquals(MEASURED_MPA - 32.1534, result.difference(), 1e-9);
        assertEquals((MEASURED_MPA - 32.1534) / 32.1534 * 100d, result.deviationPercent(), 1e-9);
    }

    @Test
    void calculateDegradesToMissingTheoryWhenThePlatformIsUnavailable() {
        measuredSource();
        var inputs = service.context(8, 5, 2).inputs();

        var result = service.calculate(new CalculateRequest(8, 5, 2, inputs), Map.of());

        assertNull(result.formationPressure());
        assertEquals(MISSING, result.formationPressureSource());
        // 平台失败不影响实际值：它照常从库里读出来并显示
        assertEquals(MEASURED_MPA, result.measuredPressure().value(), 1e-9);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("原平台")),
                "降级时必须给出中文提示，否则用户不知道为什么理论值是空的");
    }

    @Test
    void saveThenLoadSavedRoundTripsThroughTheService() {
        // 这是控制器实际走的路径：先把页面上的参数与结果存下，再原样读回来。
        measuredSource();
        var inputs = service.context(8, 5, 2).inputs();

        service.save(new SaveRequest(8, 5, 2, inputs, 18.1045, MEASURED_MPA));
        var saved = service.loadSaved(8, 5, 2).orElseThrow();

        assertEquals(18.1045, saved.theoreticalPressure(), 1e-9);
        assertEquals(MEASURED_MPA, saved.actualPressure(), 1e-9);
        assertEquals(50_000_000d, saved.inputs().originalPressure(), 1e-6);
        assertEquals(0.0462, saved.inputs().gasPvtParam().h2SMoleFraction(), 1e-12);
    }

    @Test
    void saveAndLoadSavedRejectAScopeThatIsNotTheGivenStorage() {
        assertThrows(ResponseStatusException.class,
                () -> service.save(new SaveRequest(8, 5, 999, null, 18.1045, null)));
        assertThrows(ResponseStatusException.class, () -> service.loadSaved(8, 5, 999));
    }

    @Test
    void loadSavedIsEmptyBeforeTheFirstSave() {
        assertTrue(service.loadSaved(8, 5, 2).isEmpty());
    }

    @Test
    void loadSavedReturnsTheDifferenceSoTheCardIsCompleteAfterReload() {
        // 保存行里只有两侧压力；差异与百分比偏差必须由后端**重新算出来**，
        // 否则重新打开时"理论值 19.65 / 实际值 13.56 / 差异 —"看起来像坏了。
        service.save(new SaveRequest(8, 5, 2, null, 19.6454, MEASURED_MPA));

        var saved = service.loadSaved(8, 5, 2).orElseThrow();

        assertEquals(19.6454, saved.theoreticalPressure(), 1e-9);
        assertEquals(MEASURED_MPA, saved.actualPressure(), 1e-9);
        assertEquals(MEASURED_MPA - 19.6454, saved.difference(), 1e-9);
        assertEquals((MEASURED_MPA - 19.6454) / 19.6454 * 100d, saved.deviationPercent(), 1e-9);
    }

    @Test
    void loadSavedHasNoDifferenceWhenTheSavedTheoryIsEmpty() {
        // 平台不可用时保存的理论值是空的：此时给不出差异，也不能拿 0 顶替。
        service.save(new SaveRequest(8, 5, 2, null, null, MEASURED_MPA));

        var saved = service.loadSaved(8, 5, 2).orElseThrow();

        assertNull(saved.difference());
        assertNull(saved.deviationPercent());
    }

    @Test
    void calculateReturnsOnlyTheFormationPressureComparison() {
        // 改造后结果只剩地层压力一项：理论值 / 实际值 / 差异 / 百分比偏差。
        // 断言记录组件名而不是逐个 getter，这样"多留了一个字段"也会被挡住：
        // 老师的要求是另外三个因素在前后端一并删除，不是只在界面上藏起来。
        var result = service.calculate(new CalculateRequest(8, 5, 2, null), Map.of());

        var names = java.util.Arrays.stream(result.getClass().getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        // 实际值用 FactorValue 打包（值 + 来源 + 出处说明），这样"实测静压：X-1"这条线索不丢。
        assertEquals(List.of("formationPressure", "formationPressureSource", "measuredPressure",
                "difference", "deviationPercent", "warnings"), names);
    }
}
