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
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.MANUAL;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.MISSING;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 库级预填与编排的测试，用 H2（MODE=MySQL）内存库，不连真实 MySQL——
 * 与本仓库 {@code StorageMaterialBalanceServiceTests} 的做法一致。
 *
 * <p>插入的数据一律是本机实测值（spec §4.1），用来钉住"数据库口径 → 原平台口径"的换算。
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
                  modification_method BIGINT,deviation_factor_method BIGINT,original_pressure DOUBLE,temperature DOUBLE,
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

        jdbc.update("INSERT INTO project_summaries VALUES(8,0)");
        jdbc.update("INSERT INTO project_gas_reservoir VALUES(5,8)");
        jdbc.update("INSERT INTO project_storage VALUES(2,8,5)");
        jdbc.update("INSERT INTO project_well_heads VALUES(21,8,5,'X-1')");
        jdbc.update("INSERT INTO project_storage_well VALUES(2,21)");
        service = new StorageMainFactorService(jdbc, new StorageCatalogService(jdbc), platformReturning(null));
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

    static FactorRow row(List<FactorRow> rows, String key) {
        return rows.stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    }

    /** 插入一口井的物质平衡输入与同源的动态储量；数值取自本机实测。 */
    void measuredSource() {
        jdbc.update("INSERT INTO dynamic_original_gas_in_place VALUES(1,8,5,'X-1',2,TIMESTAMP '2020-01-01 00:00:00',TIMESTAMP '2020-01-01 00:00:00')");
        jdbc.update("""
                INSERT INTO dynamic_original_gas_in_place_by_mb_input
                (id,dynamic_original_gas_in_place_id,gas_type,specific_gravity,hydrogen_sulfide,carbon_dioxide,nitrogen,
                 modification_method,deviation_factor_method,original_pressure,temperature,
                 rock_compression_coefficient,water_compression_coefficient,water_saturation,
                 reservoir_porosity,shale_rock_desity,langmuir_pressure,langmuir_volume,gas_reservoir_type)
                VALUES(11,1,'干气',0.58,0.0462,0.0396,0,0,0,50000000,353.15,
                       1.0E-10,3.744512763331313E-10,0.26158040988077613,0,0,0,0,1)
                """);
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_output VALUES(101,1,8,5,'X-1',2,2339827089.8104453,1.0,2)");
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_by_mb_input_item VALUES(201,11,TIMESTAMP '2013-03-29 00:00:00',13557268.419307435,1230637276.8,0.0,FALSE)");
    }

    @Test
    void rejectsScopeThatIsNotTheGivenStorage() {
        assertThrows(ResponseStatusException.class, () -> service.context(8, 5, 999));
    }

    @Test
    void prefillsToolboxInputsConvertedToPlatformUnits() {
        measuredSource();
        var context = service.context(8, 5, 2);
        var inputs = context.inputs();

        // 已是原平台口径的列必须原样透传，不能被再缩放一次
        assertEquals(50_000_000d, inputs.originalPressure(), 1e-6);
        assertEquals(353.15, inputs.formationTemperature(), 1e-9);
        assertEquals(0.26158040988077613, inputs.waterSaturation(), 1e-15);
        assertEquals(1.0E-10, inputs.rockCompressionCoefficient(), 1e-20);
        assertEquals(1, inputs.gasReservoirType());
        // 地质储量存的是 m³，原平台要 10⁸m³
        assertEquals(23.398270898104453, inputs.originalGasInPlace(), 1e-9);
        // 累产气量同表存 m³
        assertEquals(12.306372768, inputs.cumulativeGasProduction(), 1e-9);
        // 组分在物质平衡输入表里存小数，原平台要百分数
        assertEquals(0, inputs.gasPvtParam().gasType());
        assertEquals(4.62, inputs.gasPvtParam().h2SMoleFraction(), 1e-9);
        assertEquals(3.96, inputs.gasPvtParam().co2MoleFraction(), 1e-9);
    }

    @Test
    void reportsMissingDataAsExplicitWarningsNotEmptyTable() {
        var context = service.context(8, 5, 2);
        assertEquals(4, context.factors().size());
        context.factors().forEach(f -> assertEquals(MISSING, f.theoretical().source()));
        assertFalse(context.warnings().isEmpty(), "库级无数据时必须给出中文说明，而不是空表");
    }

    @Test
    void readsGasVolumeFromTheSameSourceRowAsTheInputs() {
        measuredSource();
        // 另一条来源记录：method 6 也有一个储量，但与本井的物质平衡输入不是同一行
        jdbc.update("INSERT INTO dynamic_original_gas_in_place VALUES(2,8,5,'X-1',6,TIMESTAMP '2021-01-01 00:00:00',TIMESTAMP '2021-01-01 00:00:00')");
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_output VALUES(102,2,8,5,'X-1',6,2940228666.6234493,0.61,1)");

        var gas = row(service.context(8, 5, 2).factors(), "gas");
        assertEquals(AUTO, gas.actual().source());
        assertEquals(23.398270898104453, gas.actual().value(), 1e-9);
    }

    @Test
    void fallsBackToStaticPressureTableWhenNoMeasurementsExist() {
        // 真实库里这一列是 **Pa**（实测 19650000 ~ 31608000），不是 MPa。
        // 早期版本的 fixture 写的是 19.65 并断言 19.65，等于把这个错误钉死成"通过"。
        jdbc.update("INSERT INTO project_static_pressure_data VALUES(301,'X-1',TIMESTAMP '2010-06-10 00:00:00',19650000,5,8)");
        var pressure = row(service.context(8, 5, 2).factors(), "formationPressure");
        assertEquals(AUTO, pressure.actual().source());
        assertEquals(19.65, pressure.actual().value(), 1e-9);
    }

    @Test
    void calculateStillReturnsDifferencesWhenThereIsNoMbInputAtAll() {
        // spec §8/§11-5：库级数据为空是本机现状。此时用户仍要能手工填四个值并看到差异，
        // 不能因为"没有入参"就把整个请求拒掉（那是把最主要的兜底路径堵死）。
        var theoretical = Map.of("formationPressure", new FactorValue(32.15, MANUAL, "用户手输"),
                "gasSaturation", new FactorValue(0.8, MANUAL, "用户手输"));
        var actual = Map.of("formationPressure", new FactorValue(13.5573, MANUAL, "用户手输"));
        var request = new CalculateRequest(8, 5, 2, null, theoretical, actual, null);

        var result = service.calculate(request, Map.of());

        assertNull(result.formationPressure());
        assertEquals(MANUAL, result.formationPressureSource());
        assertEquals(4, result.factors().size());
        assertEquals(-18.5927, row(result.factors(), "formationPressure").difference(), 1e-9);
        assertEquals("NEGATIVE", row(result.factors(), "formationPressure").direction());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("手动")),
                "必须说明为什么理论地层压力没有自动算出来");
    }

    @Test
    void preservesAutomaticProvenanceWhenTheCallerEchoesContextValuesBack() {
        // 页面把 context 读到的实际值原样回传，来源必须仍然是"自动读取"，
        // 否则点一次计算之后"实测静压：X-1"就变成了"手动填写"，用户再也分不清数据出处。
        measuredSource();
        var context = service.context(8, 5, 2);
        var request = new CalculateRequest(8, 5, 2, 0.0065,
                Map.of(), context.factors().stream().collect(
                        java.util.stream.Collectors.toMap(FactorRow::key, FactorRow::actual)),
                context.inputs());

        var result = service.calculate(request, Map.of());

        var pressure = row(result.factors(), "formationPressure").actual();
        assertEquals(AUTO, pressure.source());
        assertTrue(pressure.note() != null && pressure.note().contains("X-1"),
                "自动值要保留它的来源说明");
        assertEquals(AUTO, row(result.factors(), "gas").actual().source());
    }

    @Test
    void prefersTheGasVolumeWhoseMethodMatchesTheSourceRowAndLabelsIt() {
        // 同一个来源行挂多条输出时，必须挑与母行 method 一致的那条，并把选中的行说清楚；
        // 靠 o.id 取第一条会随插入顺序漂移（本库 G 的跨度是 19.85~33.05 ×10⁸m³）。
        measuredSource();
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_output VALUES(103,1,8,5,'X-1',7,9900000000.0,0.5,2)");
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_output VALUES(104,1,8,5,'X-1',2,1985476101.8,0.88,1)");

        var gas = row(service.context(8, 5, 2).factors(), "gas");
        // 母行 method=2 → 应取 method=2 的那条 (1.9854761018e9 m³ = 19.854761018 ×10⁸m³)
        assertEquals(19.854761018, gas.actual().value(), 1e-9);
        assertTrue(gas.actual().note() != null && gas.actual().note().contains("104"),
                "要把选中的输出行标出来，便于人工核对：" + gas.actual().note());
    }

    @Test
    void calculateUsesPlatformPressureAndDerivesPoreVolumeAndGasSaturation() {
        service = new StorageMainFactorService(jdbc, new StorageCatalogService(jdbc), platformReturning(32.1534));
        measuredSource();
        var context = service.context(8, 5, 2);
        var request = new CalculateRequest(8, 5, 2, 0.0065, Map.of(), Map.of(), context.inputs());

        var result = service.calculate(request, Map.of());

        assertEquals(32.1534, result.formationPressure(), 1e-9);
        assertEquals(AUTO, result.formationPressureSource());
        var theoreticalPressure = row(result.factors(), "formationPressure").theoretical();
        assertEquals(AUTO, theoreticalPressure.source());
        assertEquals(32.1534, theoreticalPressure.value(), 1e-9);

        // Vp = G * Bg / (1 - Swi)
        double g = 23.398270898104453;
        double expectedVp = g * 0.0065 / (1 - 0.26158040988077613);
        assertEquals(expectedVp, row(result.factors(), "poreVolume").actual().value(), 1e-9);
        // Sg = (G - Gp) * Bg / Vp
        double expectedSg = (g - 12.306372768) * 0.0065 / expectedVp;
        assertEquals(expectedSg, row(result.factors(), "gasSaturation").actual().value(), 1e-9);
    }

    @Test
    void calculateDegradesToManualWhenThePlatformIsUnavailable() {
        measuredSource();
        var context = service.context(8, 5, 2);
        var request = new CalculateRequest(8, 5, 2, 0.0065, Map.of(), Map.of(), context.inputs());

        var result = service.calculate(request, Map.of());

        assertNull(result.formationPressure());
        assertEquals(MANUAL, result.formationPressureSource());
        assertEquals(MISSING, row(result.factors(), "formationPressure").theoretical().source());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("原平台")),
                "降级时必须给出中文提示，否则用户不知道为什么理论值是空的");
    }
}
