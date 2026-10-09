package com.grdp.studio.storagemainfactor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 保存与读取：一个库一份（唯一键 + 先 UPDATE 后 INSERT）。
 *
 * <p>H2 用 MODE=MySQL，表结构与迁移脚本 {@code 012_project_reservoir_main_factor.sql} 一致。
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
                  theoretical_pressure DOUBLE, actual_pressure DOUBLE,
                  created_at TIMESTAMP, updated_at TIMESTAMP,
                  UNIQUE (project_id, gas_reservoir_id, storage_id))
                """);
        storage = new StorageMainFactorSavedStorage(jdbc);
    }

    @AfterEach
    void cleanup() {
        dataSource.destroy();
    }

    /** 与 ServiceTests 同一组实测参数。 */
    static ToolboxInput inputs() {
        return new ToolboxInput(50_000_000d, 353.15, 23.398270898104453, 12.306372768,
                1.0E-10, 3.744512763331313E-10, 0.26158040988077613, 1, 0d, 0d, 0d, 0d,
                new GasPvtParam(0, 0.58, 0, 0.0462, 0.0396, 0, 0, 0));
    }

    @Test
    void savingTwiceKeepsExactlyOneRow() {
        storage.save(8, 5, 2, new SavedMainFactor(inputs(), 18.1045, 13.5573));
        storage.save(8, 5, 2, new SavedMainFactor(inputs(), 19.0, 13.6));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_reservoir_main_factor", Integer.class),
                "一个库只能有一行：再保存是更新，不是追加");
        var saved = storage.load(8, 5, 2).orElseThrow();
        assertEquals(19.0, saved.theoreticalPressure(), 1e-9);
        assertEquals(13.6, saved.actualPressure(), 1e-9);
    }

    @Test
    void nullTheoreticalPressureIsStoredAsNullNotZero() {
        // 平台不可用时理论值是空的。存成 0 会让下次打开显示"理论压力 0"，
        // 那是一个看着像真数据的假值。
        storage.save(8, 5, 2, new SavedMainFactor(inputs(), null, 13.5573));

        assertNull(storage.load(8, 5, 2).orElseThrow().theoreticalPressure());
    }

    @Test
    void loadReturnsEmptyForAStorageThatWasNeverSaved() {
        assertTrue(storage.load(8, 5, 2).isEmpty());
    }

    @Test
    void parametersRoundTripThroughTheDatabase() {
        storage.save(8, 5, 2, new SavedMainFactor(inputs(), 18.1045, 13.5573));

        var saved = storage.load(8, 5, 2).orElseThrow();

        assertEquals(50_000_000d, saved.inputs().originalPressure(), 1e-6);
        assertEquals(353.15, saved.inputs().formationTemperature(), 1e-9);
        assertEquals(23.398270898104453, saved.inputs().originalGasInPlace(), 1e-9);
        assertEquals(12.306372768, saved.inputs().cumulativeGasProduction(), 1e-9);
        assertEquals(0.26158040988077613, saved.inputs().waterSaturation(), 1e-15);
        assertEquals(1, saved.inputs().gasReservoirType());
        assertEquals(0.0462, saved.inputs().gasPvtParam().h2SMoleFraction(), 1e-12);
        assertEquals(0.58, saved.inputs().gasPvtParam().specificGravity(), 1e-12);
        assertEquals(18.1045, saved.theoreticalPressure(), 1e-9);
        assertEquals(13.5573, saved.actualPressure(), 1e-9);
    }

    @Test
    void anotherStorageKeepsItsOwnRow() {
        storage.save(8, 5, 2, new SavedMainFactor(inputs(), 18.1045, 13.5573));
        storage.save(8, 5, 3, new SavedMainFactor(inputs(), 20.0, 14.0));

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_reservoir_main_factor", Integer.class));
        assertEquals(18.1045, storage.load(8, 5, 2).orElseThrow().theoreticalPressure(), 1e-9);
        assertEquals(20.0, storage.load(8, 5, 3).orElseThrow().theoreticalPressure(), 1e-9);
    }
}
