package com.grdp.studio.storagemainfactor;

import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 保存与读取一份分析：整条地层压力序列作为快照存下来，**差异与百分比偏差读取时逐点重算**。
 *
 * <p>重算是刻意的：差异由两侧压力推导，不入库；读取时不重算，
 * 重新打开页面就会看到"两侧都有值、差异却是 —"，像是坏了。
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
        jdbc.execute("CREATE TABLE project_storage(id BIGINT PRIMARY KEY,project_id BIGINT,gas_reservoir_id BIGINT)");
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
        jdbc.update("INSERT INTO project_storage VALUES(2,8,5)");

        service = new StorageMainFactorService(jdbc, new StorageCatalogService(jdbc),
                new StorageMainFactorSavedStorage(jdbc, new ObjectMapper()));
    }

    @AfterEach
    void cleanup() {
        dataSource.destroy();
    }

    static ToolboxInput inputs() {
        return new ToolboxInput(50_000_000d, 353.15, 500d, 57.43970823,
                1.0E-10, 3.744512763331313E-10, 0.26158040988077613, 1, 0d, 0d, 0d, 0d,
                new GasPvtParam(0, 0.58, 0, 0.0462, 0.0396, 0, 0, 0));
    }

    static StoredPoint point(String date, double gas, double measured, Double theoretical) {
        return new StoredPoint(LocalDate.parse(date), gas, measured, theoretical);
    }

    static SaveRequest request(Double lastTheoretical) {
        return new SaveRequest(8, 5, 2, inputs(), List.of(
                point("2004-04-20", 0.0012918, 50.0, 49.9998),
                point("2009-04-30", 37.356667794, 44.2505, 43.5972),
                point("2013-10-31", 57.43970823, 41.4794921875, lastTheoretical)));
    }

    @Test
    void saveThenLoadSavedRoundTripsThroughTheService() {
        service.save(request(40.5345));

        List<SeriesPoint> points = service.loadSaved(8, 5, 2).orElseThrow().points();

        assertEquals(3, points.size());
        assertEquals(LocalDate.parse("2004-04-20"), points.getFirst().date());
        assertEquals(0.0012918, points.getFirst().cumulativeGasProduction(), 1e-12);
        assertEquals(50.0, points.getFirst().measuredPressure(), 1e-12);
        assertEquals(41.4794921875, points.getLast().measuredPressure(), 1e-12);
        assertTrue(points.stream().allMatch(point -> point.difference() != null),
                "每个点都要有差异，不能只有最后一个点算出来");
    }

    @Test
    void loadSavedReturnsTheDifferenceOfTheLastPointSoTheCardIsCompleteAfterReload() {
        service.save(request(40.5345));

        SeriesPoint last = service.loadSaved(8, 5, 2).orElseThrow().points().getLast();

        assertEquals(0.9449921875, last.difference(), 1e-12);
        assertEquals(2.331, last.deviationPercent(), 1e-3);
    }

    @Test
    void loadSavedHasNoDifferenceWhenTheTheoreticalPressureIsEmpty() {
        // 原平台不可用时保存下来的那一份，重新打开必须仍是"理论值空、差异空"，
        // 不能因为读取路径换了就变成 0 或者拿实测值顶替。
        service.save(request(null));

        SeriesPoint last = service.loadSaved(8, 5, 2).orElseThrow().points().getLast();

        assertNull(last.theoreticalPressure());
        assertNull(last.difference());
        assertNull(last.deviationPercent());
        assertEquals(41.4794921875, last.measuredPressure(), 1e-12);
    }

    @Test
    void saveAndLoadSavedRejectAScopeThatIsNotTheGivenStorage() {
        assertThrows(ResponseStatusException.class, () -> service.save(
                new SaveRequest(8, 5, 3, inputs(), List.of())));
        assertThrows(ResponseStatusException.class, () -> service.loadSaved(8, 5, 3));
    }

    @Test
    void loadSavedIsEmptyBeforeTheFirstSave() {
        assertEquals(Optional.empty(), service.loadSaved(8, 5, 2));
    }
}
