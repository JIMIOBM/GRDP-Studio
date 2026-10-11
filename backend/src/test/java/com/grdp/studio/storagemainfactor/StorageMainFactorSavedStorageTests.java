package com.grdp.studio.storagemainfactor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 保存与读取：一个库一份（唯一键 + 先 UPDATE 后 INSERT），并把**整条地层压力序列**
 * 作为快照存进 {@code series_json}。
 *
 * <p>存快照的意义：保存过之后重开页面秒出，不必再调 20 次原平台工具箱。
 *
 * <p>H2 用 MODE=MySQL，表结构与迁移脚本
 * {@code 015_project_reservoir_main_factor_series.sql} 一致。
 */
class StorageMainFactorSavedStorageTests {

    SingleConnectionDataSource dataSource;
    JdbcTemplate jdbc;
    StorageMainFactorSavedStorage storage;

    @BeforeEach
    void setup() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:mf_saved_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
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
                  series_json LONGTEXT,
                  created_at TIMESTAMP, updated_at TIMESTAMP,
                  UNIQUE (project_id, gas_reservoir_id, storage_id))
                """);
        storage = new StorageMainFactorSavedStorage(jdbc, new ObjectMapper());
    }

    @AfterEach
    void cleanup() {
        dataSource.destroy();
    }

    /** 与 SeriesServiceTests 同一组实测参数。 */
    static ToolboxInput inputs() {
        return new ToolboxInput(50_000_000d, 353.15, 500d, 57.43970823,
                1.0E-10, 3.744512763331313E-10, 0.26158040988077613, 1, 0d, 0d, 0d, 0d,
                new GasPvtParam(0, 0.58, 0, 0.0462, 0.0396, 0, 0, 0));
    }

    static StoredPoint point(String date, double gas, double measured, Double theoretical) {
        return new StoredPoint(LocalDate.parse(date), gas, measured, theoretical);
    }

    /** 三个点，末点的理论值可控（用来验证 null 不被写成 0）。 */
    static SavedMainFactor saved(Double lastTheoretical) {
        return new SavedMainFactor(inputs(), List.of(
                point("2004-04-20", 0.0012918, 50.0, 49.9998),
                point("2009-04-30", 37.356667794, 44.2505, 43.5972),
                point("2013-10-31", 57.43970823, 41.4794921875, lastTheoretical)));
    }

    @Test
    void seriesSnapshotRoundTripsThroughTheDatabase() {
        storage.save(8, 5, 2, saved(40.5345));

        SavedMainFactor loaded = storage.load(8, 5, 2).orElseThrow();

        assertEquals(3, loaded.points().size());
        assertEquals(LocalDate.parse("2004-04-20"), loaded.points().getFirst().date());
        assertEquals(0.0012918, loaded.points().getFirst().cumulativeGasProduction(), 1e-12);
        assertEquals(50.0, loaded.points().getFirst().measuredPressure(), 1e-12);
        assertEquals(49.9998, loaded.points().getFirst().theoreticalPressure(), 1e-12);
        assertEquals(41.4794921875, loaded.points().getLast().measuredPressure(), 1e-12);
        assertEquals(40.5345, loaded.points().getLast().theoreticalPressure(), 1e-12);
    }

    @Test
    void savingTwiceKeepsExactlyOneRowAndReplacesTheSnapshot() {
        storage.save(8, 5, 2, saved(40.5345));
        storage.save(8, 5, 2, new SavedMainFactor(inputs(), List.of(
                point("2012-04-30", 50.03547003, 42.4805, 41.6357),
                point("2013-10-31", 57.43970823, 41.4794921875, 40.5345))));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_reservoir_main_factor", Integer.class),
                "一个库只能有一行：再保存是更新，不是追加");
        assertEquals(2, storage.load(8, 5, 2).orElseThrow().points().size(),
                "快照要整体替换，不能把上一次的点和这一次的混在一起");
    }

    @Test
    void nullTheoreticalPressureIsStoredAsNullNotZero() {
        // 平台不可用时该点理论值是空的。存成 0 会让下次打开显示"理论压力 0"，
        // 那是一个看着像真数据的假值，折线图上还会多出一条贴着 0 的假线。
        storage.save(8, 5, 2, saved(null));

        assertNull(storage.load(8, 5, 2).orElseThrow().points().getLast().theoreticalPressure());
    }

    @Test
    void theSnapshotIsActuallyWrittenToTheColumn() {
        // 序列快照是唯一的结果来源（结果卡只画折线图），所以它必须真的落到库里，
        // 不能只在同一段代码里"存了又读"看着对。
        storage.save(8, 5, 2, saved(40.5345));

        String seriesJson = (String) jdbc.queryForMap(
                "SELECT series_json FROM project_reservoir_main_factor WHERE storage_id=2").get("series_json");

        assertNotNull(seriesJson, "整条序列要作为快照存下来");
        assertTrue(seriesJson.contains("2013-10-31"), "快照里要有最后一点的日期：" + seriesJson);
        assertTrue(seriesJson.contains("40.5345"), "快照里要有该点的理论值：" + seriesJson);
    }

    @Test
    void parametersRoundTripThroughTheDatabase() {
        storage.save(8, 5, 2, saved(40.5345));

        var saved = storage.load(8, 5, 2).orElseThrow();

        assertEquals(50_000_000d, saved.inputs().originalPressure(), 1e-6);
        assertEquals(353.15, saved.inputs().formationTemperature(), 1e-9);
        assertEquals(500d, saved.inputs().originalGasInPlace(), 1e-9);
        assertEquals(57.43970823, saved.inputs().cumulativeGasProduction(), 1e-9);
        assertEquals(0.26158040988077613, saved.inputs().waterSaturation(), 1e-15);
        assertEquals(1, saved.inputs().gasReservoirType());
        assertEquals(0.0462, saved.inputs().gasPvtParam().h2SMoleFraction(), 1e-12);
        assertEquals(0.58, saved.inputs().gasPvtParam().specificGravity(), 1e-12);
    }

    @Test
    void anotherStorageKeepsItsOwnRow() {
        storage.save(8, 5, 2, saved(40.5345));
        storage.save(8, 5, 3, saved(41.0));

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_reservoir_main_factor", Integer.class));
        assertEquals(40.5345, storage.load(8, 5, 2).orElseThrow()
                .points().getLast().theoreticalPressure(), 1e-9);
        assertEquals(41.0, storage.load(8, 5, 3).orElseThrow()
                .points().getLast().theoreticalPressure(), 1e-9);
    }

    @Test
    void loadReturnsEmptyForAStorageThatWasNeverSaved() {
        assertTrue(storage.load(8, 5, 2).isEmpty());
    }
}
