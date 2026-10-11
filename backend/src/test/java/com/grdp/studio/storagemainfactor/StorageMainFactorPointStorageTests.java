package com.grdp.studio.storagemainfactor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.LocalDate;
import java.util.List;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SeriesPointInput;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 主控因素分析时序数据的持久化。导入语义是**整体替换**：一次导入就是这个库当前的全部时序数据，
 * 不做增量合并——否则上一次导错的文件会残留下来，图上多出几个来历不明的点。
 *
 * <p>H2 用 MODE=MySQL，表结构与迁移脚本 {@code 015_project_reservoir_main_factor_series.sql} 一致。
 */
class StorageMainFactorPointStorageTests {

    private static final long PROJECT = 8;
    private static final long RESERVOIR = 5;
    private static final long STORAGE = 2;
    private static final long OTHER_STORAGE = 3;

    SingleConnectionDataSource dataSource;
    JdbcTemplate jdbc;
    StorageMainFactorPointStorage storage;

    @BeforeEach
    void setup() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:mf_point_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
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
        storage = new StorageMainFactorPointStorage(jdbc);
    }

    @AfterEach
    void cleanup() {
        dataSource.destroy();
    }

    private static SeriesPointInput point(String date, double gas, double pressure) {
        return new SeriesPointInput(LocalDate.parse(date), gas, pressure);
    }

    @Test
    void replaceThenLoadReturnsPointsInDateOrder() {
        storage.replace(PROJECT, RESERVOIR, STORAGE, List.of(
                point("2013-10-31", 57.43970823, 41.4795),
                point("2004-04-20", 0.0012918, 50.0),
                point("2009-04-30", 37.356667794, 44.2505)));

        List<SeriesPointInput> loaded = storage.load(PROJECT, RESERVOIR, STORAGE);

        assertEquals(List.of(LocalDate.parse("2004-04-20"), LocalDate.parse("2009-04-30"),
                        LocalDate.parse("2013-10-31")),
                loaded.stream().map(SeriesPointInput::date).toList(),
                "折线图的 x 轴按日期升序，不能被传入顺序决定");
        assertEquals(0.0012918, loaded.getFirst().cumulativeGasProduction(), 1e-12);
        assertEquals(50.0, loaded.getFirst().measuredPressure(), 1e-12);
    }

    @Test
    void replaceIsAtomicAndDropsThePreviousImport() {
        storage.replace(PROJECT, RESERVOIR, STORAGE, List.of(
                point("2004-04-20", 0.0012918, 50.0),
                point("2004-10-31", 0.9164784, 49.8535),
                point("2005-04-30", 3.7837536, 49.3774)));

        storage.replace(PROJECT, RESERVOIR, STORAGE, List.of(
                point("2012-04-30", 50.03547003, 42.4805),
                point("2012-10-31", 52.416186102, 42.1509)));

        List<SeriesPointInput> loaded = storage.load(PROJECT, RESERVOIR, STORAGE);

        assertEquals(2, loaded.size(), "重新导入是整体替换，旧的点必须消失，不能新旧混在一起");
        assertEquals(LocalDate.parse("2012-04-30"), loaded.getFirst().date());
    }

    @Test
    void anotherStorageKeepsItsOwnPoints() {
        storage.replace(PROJECT, RESERVOIR, STORAGE, List.of(
                point("2004-04-20", 0.0012918, 50.0),
                point("2004-10-31", 0.9164784, 49.8535)));
        storage.replace(PROJECT, RESERVOIR, OTHER_STORAGE, List.of(
                point("2004-04-20", 99.0, 30.0),
                point("2004-10-31", 98.0, 29.0),
                point("2005-04-30", 97.0, 28.0)));

        assertEquals(2, storage.load(PROJECT, RESERVOIR, STORAGE).size(),
                "另一个库的导入不能动到这个库的点");
        assertEquals(3, storage.load(PROJECT, RESERVOIR, OTHER_STORAGE).size());
    }

    @Test
    void loadReturnsEmptyListWhenNothingImported() {
        assertTrue(storage.load(PROJECT, RESERVOIR, STORAGE).isEmpty(),
                "没导入过要返回空表而不是 null，调用方要能直接判空");
    }
}
