package com.grdp.studio.storagepressuretest;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StoragePressureTestMethodServiceTests {
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private StoragePressureTestMethodService service;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:pressure_test_method_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE project_summaries(id BIGINT PRIMARY KEY,delete_status INT)");
        jdbc.execute("CREATE TABLE project_gas_reservoir(id BIGINT PRIMARY KEY,project_id BIGINT)");
        jdbc.execute("CREATE TABLE project_storage(id BIGINT PRIMARY KEY,project_id BIGINT,gas_reservoir_id BIGINT)");
        jdbc.execute("CREATE TABLE project_well_heads(id BIGINT PRIMARY KEY,project_id BIGINT,project_gas_reservoir_id BIGINT,well_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE project_storage_well(storage_id BIGINT,well_id BIGINT)");
        jdbc.execute("CREATE TABLE storage_pressure_test_method_point(id BIGINT AUTO_INCREMENT PRIMARY KEY,project_id BIGINT,gas_reservoir_id BIGINT,storage_id BIGINT,well_id BIGINT,row_key VARCHAR(255),source_key VARCHAR(255),point_date DATE,measured_pressure_mpa DOUBLE,is_manual BOOLEAN,is_deleted BOOLEAN,UNIQUE(storage_id,row_key))");
        jdbc.execute("CREATE TABLE storage_pressure_test_method_migration(project_id BIGINT,gas_reservoir_id BIGINT,storage_id BIGINT,migration_key VARCHAR(64),migrated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(project_id,gas_reservoir_id,storage_id,migration_key))");
        jdbc.execute("CREATE TABLE storage_pressure_gradient_point(id BIGINT AUTO_INCREMENT PRIMARY KEY,project_id BIGINT,gas_reservoir_id BIGINT,storage_id BIGINT,well_id BIGINT,row_key VARCHAR(255),source_key VARCHAR(255),point_date DATE,measured_pressure_mpa DOUBLE,measured_coordinate_m DOUBLE,gradient_mpa_per_m DOUBLE,is_manual BOOLEAN,is_deleted BOOLEAN,UNIQUE(storage_id,row_key))");
        jdbc.update("INSERT INTO project_summaries VALUES(7,0)");
        jdbc.update("INSERT INTO project_gas_reservoir VALUES(6,7)");
        jdbc.update("INSERT INTO project_storage VALUES(2,7,6)");
        jdbc.update("INSERT INTO project_well_heads VALUES(21,7,6,'X-1'),(22,7,6,'X-2')");
        jdbc.update("INSERT INTO project_storage_well VALUES(2,21)");
        jdbc.update("INSERT INTO storage_pressure_gradient_point(project_id,gas_reservoir_id,storage_id,well_id,row_key,measured_pressure_mpa,is_manual,is_deleted) VALUES(7,6,2,21,'gradient-preserved',12.4,FALSE,FALSE)");
        service = new StoragePressureTestMethodService(jdbc, new StorageCatalogService(jdbc));
    }

    @AfterEach
    void cleanUp() { dataSource.destroy(); }

    @Test
    void savesAndReadsTestMethodPointsWithoutChangingPressureGradientRows() {
        var rows = List.of(new StoragePressureTestMethodService.Point(
                "test-import:2025-03-01:X-1", null, "X-1", LocalDate.of(2025, 3, 1), 12.4, true, false));

        var saved = service.save(7, 6, 2, new StoragePressureTestMethodService.SaveRequest(rows));
        var loaded = service.list(7, 6, 2);

        assertEquals(rows, saved.rows());
        assertEquals(2, loaded.rows().size());
        assertTrue(loaded.rows().containsAll(rows));
        assertTrue(loaded.rows().stream().anyMatch(row -> "gradient-preserved".equals(row.id())));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM storage_pressure_test_method_point WHERE storage_id=2", Integer.class));
        assertEquals("gradient-preserved", jdbc.queryForObject("SELECT row_key FROM storage_pressure_gradient_point WHERE storage_id=2", String.class));
    }

    @Test
    void migratesLegacyRowsOnlyOnceAndDoesNotResurrectDeletedRows() {
        assertEquals(1, service.list(7, 6, 2).rows().size());
        service.save(7, 6, 2, new StoragePressureTestMethodService.SaveRequest(List.of()));

        assertTrue(service.list(7, 6, 2).rows().isEmpty());
        assertEquals("gradient-preserved", jdbc.queryForObject("SELECT row_key FROM storage_pressure_gradient_point WHERE storage_id=2", String.class));
    }

    @Test
    void rejectsNonMemberWellsBeforeReplacingSavedTestMethodRows() {
        var existing = new StoragePressureTestMethodService.Point(
                "manual:existing", null, "X-1", LocalDate.of(2025, 3, 1), 12.4, true, false);
        service.save(7, 6, 2, new StoragePressureTestMethodService.SaveRequest(List.of(existing)));
        var foreign = new StoragePressureTestMethodService.Point(
                "manual:foreign", null, "X-2", LocalDate.of(2025, 3, 2), 12.5, true, false);

        assertThrows(BusinessException.class, () -> service.save(7, 6, 2,
                new StoragePressureTestMethodService.SaveRequest(List.of(foreign))));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM storage_pressure_test_method_point WHERE storage_id=2", Integer.class));
        assertEquals("manual:existing", jdbc.queryForObject("SELECT row_key FROM storage_pressure_test_method_point WHERE storage_id=2", String.class));
    }

    @Test
    void rejectsNegativePressuresBeforeReplacingSavedTestMethodRows() {
        var existing = new StoragePressureTestMethodService.Point(
                "manual:existing", null, "X-1", LocalDate.of(2025, 3, 1), 12.4, true, false);
        service.save(7, 6, 2, new StoragePressureTestMethodService.SaveRequest(List.of(existing)));
        var invalid = new StoragePressureTestMethodService.Point(
                "manual:invalid", null, "X-1", LocalDate.of(2025, 3, 2), -1d, true, false);

        assertThrows(BusinessException.class, () -> service.save(7, 6, 2,
                new StoragePressureTestMethodService.SaveRequest(List.of(invalid))));
        assertEquals("manual:existing", jdbc.queryForObject("SELECT row_key FROM storage_pressure_test_method_point WHERE storage_id=2", String.class));
    }
}
