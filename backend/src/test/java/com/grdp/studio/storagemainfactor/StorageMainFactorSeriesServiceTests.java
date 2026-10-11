package com.grdp.studio.storagemainfactor;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 序列服务：把本库导入的时序数据逐点送进原平台工具箱，得到理论地层压力序列。
 *
 * <p>核心要求是**只有累产气在变**：整条序列共用一组参数，工具箱会话也只开一次。
 *
 * <p>两类失败要分清楚：数据本身不合法（重复日期、累产气回退、点太少）与作用域不符
 * 都是"用户的东西不对"，要报出来；而原平台不可用是**降级**，理论值为空但实测值照常返回。
 */
class StorageMainFactorSeriesServiceTests {

    private static final long PROJECT = 8;
    private static final long RESERVOIR = 5;
    private static final long STORAGE = 2;

    SingleConnectionDataSource dataSource;
    JdbcTemplate jdbc;
    StorageMainFactorPointStorage pointStorage;
    FakeToolbox toolbox;
    StorageMainFactorSeriesService service;

    @BeforeEach
    void setup() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:mf_series_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE project_storage(id BIGINT PRIMARY KEY,project_id BIGINT,gas_reservoir_id BIGINT)");
        jdbc.execute("""
                CREATE TABLE project_reservoir_main_factor_point(
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  project_id BIGINT NOT NULL, gas_reservoir_id BIGINT NOT NULL, storage_id BIGINT NOT NULL,
                  date DATE NOT NULL,
                  cumulative_gas_production DOUBLE NOT NULL,
                  measured_pressure DOUBLE NOT NULL,
                  created_at TIMESTAMP, updated_at TIMESTAMP,
                  UNIQUE (storage_id, date))
                """);
        jdbc.update("INSERT INTO project_storage VALUES(2,8,5)");

        pointStorage = new StorageMainFactorPointStorage(jdbc);
        toolbox = new FakeToolbox();
        service = new StorageMainFactorSeriesService(new StorageCatalogService(jdbc), pointStorage, toolbox);
    }

    @AfterEach
    void cleanup() {
        dataSource.destroy();
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    /**
     * 库 5 的真实 20 点（{@code project_gas_static_pressure_data}，已随交付脚本给出）。
     * 用真数据而不是编几个数：折线图的量级与点数就是这么来的，编的数据测不出"20 个点"这类要求。
     */
    static List<SeriesPointInput> realSeries() {
        return List.of(
                new SeriesPointInput(LocalDate.parse("2004-04-20"), 0.0012918, 50.0000),
                new SeriesPointInput(LocalDate.parse("2004-10-31"), 0.9164784, 49.8535),
                new SeriesPointInput(LocalDate.parse("2005-04-30"), 3.7837536, 49.3774),
                new SeriesPointInput(LocalDate.parse("2005-10-31"), 8.1793068, 48.6694),
                new SeriesPointInput(LocalDate.parse("2006-04-30"), 12.6527628, 47.9614),
                new SeriesPointInput(LocalDate.parse("2006-10-31"), 17.651742, 47.1802),
                new SeriesPointInput(LocalDate.parse("2007-04-30"), 21.6650484, 46.5698),
                new SeriesPointInput(LocalDate.parse("2007-10-31"), 25.88460048, 45.9351),
                new SeriesPointInput(LocalDate.parse("2008-04-30"), 29.796443082, 45.3491),
                new SeriesPointInput(LocalDate.parse("2008-10-31"), 33.58828602, 44.7998),
                new SeriesPointInput(LocalDate.parse("2009-04-30"), 37.356667794, 44.2505),
                new SeriesPointInput(LocalDate.parse("2009-10-31"), 40.990912284, 43.7378),
                new SeriesPointInput(LocalDate.parse("2010-04-30"), 43.213067964, 43.4204),
                new SeriesPointInput(LocalDate.parse("2010-10-31"), 45.009733062, 43.1641),
                new SeriesPointInput(LocalDate.parse("2011-04-30"), 45.574576554, 43.0908),
                new SeriesPointInput(LocalDate.parse("2011-10-31"), 47.99724921, 42.7490),
                new SeriesPointInput(LocalDate.parse("2012-04-30"), 50.03547003, 42.4805),
                new SeriesPointInput(LocalDate.parse("2012-10-31"), 52.416186102, 42.1509),
                new SeriesPointInput(LocalDate.parse("2013-04-30"), 54.826091124, 41.8213),
                new SeriesPointInput(LocalDate.parse("2013-10-31"), 57.43970823, 41.4794921875));
    }

    /** 动态地质储量取档案 ogip：500×10⁸m³（**独立来源**，不是从这条曲线反算的）。 */
    static ToolboxInput inputs(double gasInPlace) {
        return new ToolboxInput(50_000_000d, 353.15, gasInPlace, 0d,
                1.0E-10, 3.744512763331313E-10, 0.26158040988077613, 1, 0d, 0d, 0d, 0d,
                new GasPvtParam(0, 0.58, 0, 0.0462, 0.0396, 0, 0, 0));
    }

    private void importRealSeries() {
        assertEquals(20, service.importPoints(
                new SeriesDataRequest(PROJECT, RESERVOIR, STORAGE, realSeries())));
    }

    /** 假工具箱：记下开箱次数与每个点提交的入参，理论值可控。 */
    static final class FakeToolbox extends MaterialBalanceEquationClient {

        final List<ToolboxInput> submitted = new ArrayList<>();
        int openCount = 0;
        boolean available = true;
        Double theory = 40.5345;

        FakeToolbox() {
            super(null, new ObjectMapper());
        }

        @Override
        public ToolboxSession openToolbox(long projectId, Map<String, String> headers) {
            openCount++;
            return available ? new ToolboxSession(7L, Map.of(), "contract") : null;
        }

        @Override
        public Double calculateWith(ToolboxSession session, ToolboxInput input, Map<String, String> headers) {
            submitted.add(input);
            return available ? theory : null;
        }
    }

    // ------------------------------------------------------------------
    // 计算
    // ------------------------------------------------------------------

    @Test
    void calculateCallsTheToolboxOnceForTheWholeSeries() {
        importRealSeries();

        SeriesResult result = service.calculate(
                new SeriesRequest(PROJECT, RESERVOIR, STORAGE, inputs(500d)), Map.of());

        assertEquals(20, result.points().size());
        assertEquals(1, toolbox.openCount,
                "20 个点只开一次工具箱：每点新建会让请求数翻倍，平台侧还会堆一堆无用记录");
        assertEquals(20, toolbox.submitted.size(), "每个点都要提交一次，不能只算最后一个");
    }

    @Test
    void calculateVariesOnlyTheCumulativeProductionBetweenPoints() {
        importRealSeries();

        service.calculate(new SeriesRequest(PROJECT, RESERVOIR, STORAGE, inputs(500d)), Map.of());

        ToolboxInput first = toolbox.submitted.getFirst();
        ToolboxInput last = toolbox.submitted.getLast();
        assertEquals(0.0012918, first.cumulativeGasProduction(), 1e-12);
        assertEquals(57.43970823, last.cumulativeGasProduction(), 1e-12);
        assertEquals(first.originalPressure(), last.originalPressure());
        assertEquals(first.formationTemperature(), last.formationTemperature());
        assertEquals(first.originalGasInPlace(), last.originalGasInPlace(),
                "动态地质储量是储层属性，不随时间变——它变了就不是「只有累产气在变」");
        assertEquals(first.rockCompressionCoefficient(), last.rockCompressionCoefficient());
        assertEquals(first.waterCompressionCoefficient(), last.waterCompressionCoefficient());
        assertEquals(first.waterSaturation(), last.waterSaturation());
        assertEquals(first.gasReservoirType(), last.gasReservoirType());
        assertEquals(first.gasPvtParam(), last.gasPvtParam());
    }

    @Test
    void calculateReturnsDifferenceAndDeviationPerPoint() {
        importRealSeries();

        SeriesResult result = service.calculate(
                new SeriesRequest(PROJECT, RESERVOIR, STORAGE, inputs(500d)), Map.of());

        SeriesPoint last = result.points().getLast();
        assertEquals(LocalDate.parse("2013-10-31"), last.date());
        assertEquals(41.4794921875, last.measuredPressure(), 1e-12);
        assertEquals(40.5345, last.theoreticalPressure(), 1e-12);
        // 实测取的是库里存的原始值 41.4794921875（不是四舍五入到 4 位的 41.4795），
        // 所以差异精确值是 0.9449921875 —— 断言 0.945 是把显示精度当成了数据精度。
        assertEquals(0.9449921875, last.difference(), 1e-12);
        assertEquals(2.331, last.deviationPercent(), 1e-3);
        // 逐点算，不是全序列共用一个值
        assertNotEquals(result.points().getFirst().difference(), last.difference());
    }

    @Test
    void calculateRejectsAGasVolumeSmallerThanTheLargestCumulativeProduction() {
        importRealSeries();

        // 23.3983 是单井动态储量：拿它配库级累产气（最大 57.44），方程根本不成立。
        BusinessException thrown = assertThrows(BusinessException.class, () -> service.calculate(
                new SeriesRequest(PROJECT, RESERVOIR, STORAGE, inputs(23.3983)), Map.of()));

        assertEquals(400, thrown.getCode());
        assertTrue(thrown.getMessage().contains("57.43970823") || thrown.getMessage().contains("累产气"),
                "报错要说清是累产气超过了动态地质储量，不能只说「参数错误」：" + thrown.getMessage());
        assertEquals(0, toolbox.openCount, "物理上不成立就不该去调平台——画出一条负压力的线比不画更糟");
    }

    @Test
    void calculateDegradesToNullTheoryWhenThePlatformIsUnavailable() {
        importRealSeries();
        toolbox.available = false;

        SeriesResult result = service.calculate(
                new SeriesRequest(PROJECT, RESERVOIR, STORAGE, inputs(500d)), Map.of());

        assertEquals(20, result.points().size(), "原平台不可用不等于整条序列没了");
        assertTrue(result.points().stream().allMatch(point -> point.theoreticalPressure() == null));
        assertTrue(result.points().stream().allMatch(point -> point.difference() == null));
        assertEquals(41.4794921875, result.points().getLast().measuredPressure(), 1e-12,
                "实测值来自本库数据，跟原平台在不在线无关，必须照常返回");
        assertFalse(result.warnings().isEmpty(), "降级要让用户知道为什么理论值是空的");
    }

    @Test
    void calculateRejectsWhenNoSeriesDataWasImported() {
        BusinessException thrown = assertThrows(BusinessException.class, () -> service.calculate(
                new SeriesRequest(PROJECT, RESERVOIR, STORAGE, inputs(500d)), Map.of()));

        assertEquals(400, thrown.getCode());
        assertEquals(0, toolbox.openCount);
    }

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------

    @Test
    void importThenPointsReturnsTheImportedSeries() {
        importRealSeries();

        List<SeriesPointInput> stored = service.points(PROJECT, RESERVOIR, STORAGE);
        assertEquals(20, stored.size());
        assertEquals(LocalDate.parse("2004-04-20"), stored.getFirst().date());
        assertEquals(57.43970823, stored.getLast().cumulativeGasProduction(), 1e-12);
    }

    @Test
    void importRejectsDuplicateDates() {
        BusinessException thrown = assertThrows(BusinessException.class, () -> service.importPoints(
                new SeriesDataRequest(PROJECT, RESERVOIR, STORAGE, List.of(
                        new SeriesPointInput(LocalDate.parse("2004-04-20"), 0.0012918, 50.0),
                        new SeriesPointInput(LocalDate.parse("2004-04-20"), 0.9164784, 49.8535)))));

        assertEquals(400, thrown.getCode());
        assertTrue(thrown.getMessage().contains("2004-04-20"), "要说清是哪一天重复了：" + thrown.getMessage());
    }

    @Test
    void importRejectsNonMonotonicCumulativeProduction() {
        BusinessException thrown = assertThrows(BusinessException.class, () -> service.importPoints(
                new SeriesDataRequest(PROJECT, RESERVOIR, STORAGE, List.of(
                        new SeriesPointInput(LocalDate.parse("2004-04-20"), 10.0, 50.0),
                        new SeriesPointInput(LocalDate.parse("2004-10-31"), 9.0, 49.8)))));

        assertEquals(400, thrown.getCode(),
                "累产气不可能回退；出现了说明文件选错或列错位，不能照画");
    }

    @Test
    void importRejectsImplausiblePressureValues() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.importPoints(
                new SeriesDataRequest(PROJECT, RESERVOIR, STORAGE, List.of(
                        new SeriesPointInput(LocalDate.parse("2004-04-20"), 0.0012918, 0.0),
                        new SeriesPointInput(LocalDate.parse("2004-10-31"), 0.9164784, 49.8))))).getCode(),
                "0 压力不是真实测压值，当它是数据会让实测线从 0 开始");

        assertEquals(400, assertThrows(BusinessException.class, () -> service.importPoints(
                new SeriesDataRequest(PROJECT, RESERVOIR, STORAGE, List.of(
                        new SeriesPointInput(LocalDate.parse("2004-04-20"), 0.0012918, 500.0),
                        new SeriesPointInput(LocalDate.parse("2004-10-31"), 0.9164784, 49.8))))).getCode(),
                "压力超出 (0, 300] MPa 说明单位或列选错了");

        assertEquals(400, assertThrows(BusinessException.class, () -> service.importPoints(
                new SeriesDataRequest(PROJECT, RESERVOIR, STORAGE, List.of(
                        new SeriesPointInput(LocalDate.parse("2004-04-20"), 0.0012918, Double.NaN),
                        new SeriesPointInput(LocalDate.parse("2004-10-31"), 0.9164784, 49.8))))).getCode(),
                "NaN 会一路算到 echarts 上变成一个看不见的坏点");
    }

    @Test
    void importRejectsFewerThanTwoPoints() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.importPoints(
                new SeriesDataRequest(PROJECT, RESERVOIR, STORAGE, List.of(
                        new SeriesPointInput(LocalDate.parse("2004-04-20"), 0.0012918, 50.0))))).getCode(),
                "折线图至少要两个点；一个点画不出「随时间变化」");

        assertEquals(400, assertThrows(BusinessException.class, () -> service.importPoints(
                new SeriesDataRequest(PROJECT, RESERVOIR, STORAGE, List.of()))).getCode());
    }

    @Test
    void importReplacesThePreviousSeriesForThatStorage() {
        assertEquals(3, service.importPoints(new SeriesDataRequest(PROJECT, RESERVOIR, STORAGE, List.of(
                new SeriesPointInput(LocalDate.parse("2004-04-20"), 0.0012918, 50.0),
                new SeriesPointInput(LocalDate.parse("2004-10-31"), 0.9164784, 49.8535),
                new SeriesPointInput(LocalDate.parse("2005-04-30"), 3.7837536, 49.3774)))));

        assertEquals(2, service.importPoints(new SeriesDataRequest(PROJECT, RESERVOIR, STORAGE, List.of(
                new SeriesPointInput(LocalDate.parse("2012-04-30"), 50.03547003, 42.4805),
                new SeriesPointInput(LocalDate.parse("2012-10-31"), 52.416186102, 42.1509)))));

        assertEquals(2, service.points(PROJECT, RESERVOIR, STORAGE).size(),
                "重新导入是整体替换，上一次导错的文件不能残留成图上来历不明的点");
    }

    @Test
    void importAndCalculateRejectAScopeThatIsNotTheGivenStorage() {
        // 作用域不符由 StorageCatalogService.requireScope 判定，它抛的是 ResponseStatusException
        assertThrows(ResponseStatusException.class, () -> service.importPoints(
                new SeriesDataRequest(PROJECT, RESERVOIR, 3L, realSeries())));
        assertThrows(ResponseStatusException.class, () -> service.calculate(
                new SeriesRequest(PROJECT, RESERVOIR, 3L, inputs(500d)), Map.of()));
        assertThrows(ResponseStatusException.class, () -> service.points(PROJECT, 9L, STORAGE));
    }

    @Test
    void calculateRejectsAGasVolumeExactlyEqualToTheLargestCumulativeProduction() {
        importRealSeries();

        // 边界：累产气恰好等于动态地质储量时气藏已完全枯竭、方程退化，
        // 规范要求 >= 即报错——这也是"G 填小了一点点"最容易被放过的一档。
        BusinessException thrown = assertThrows(BusinessException.class, () -> service.calculate(
                new SeriesRequest(PROJECT, RESERVOIR, STORAGE, inputs(57.43970823)), Map.of()));

        assertEquals(400, thrown.getCode());
        assertEquals(0, toolbox.openCount);
    }

    @Test
    void calculateStopsTryingAfterConsecutiveEmptyResults() {
        importRealSeries();
        toolbox.theory = null; // 平台在线，但每个点都拿不到结果

        SeriesResult result = service.calculate(
                new SeriesRequest(PROJECT, RESERVOIR, STORAGE, inputs(500d)), Map.of());

        assertEquals(20, result.points().size(), "序列长度不变：实测线要照常完整");
        assertTrue(result.points().stream().allMatch(point -> point.theoreticalPressure() == null));
        assertEquals(3, toolbox.submitted.size(),
                "连续 3 个点拿不到结果就停手，不把 20 个点逐个等超时——浏览器早就放弃了");
        assertFalse(result.warnings().isEmpty(), "要说明其余点为什么没算");
    }
}
