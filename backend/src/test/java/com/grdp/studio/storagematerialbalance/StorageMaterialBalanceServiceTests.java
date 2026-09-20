package com.grdp.studio.storagematerialbalance;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class StorageMaterialBalanceServiceTests {
    SingleConnectionDataSource dataSource;
    JdbcTemplate jdbc;
    StorageMaterialBalanceService service;
    @BeforeEach void setup() {
        dataSource = new SingleConnectionDataSource("jdbc:h2:mem:mb_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE project_summaries(id BIGINT PRIMARY KEY,delete_status INT)");
        jdbc.execute("CREATE TABLE project_gas_reservoir(id BIGINT PRIMARY KEY,project_id BIGINT)");
        jdbc.execute("CREATE TABLE project_storage(id BIGINT PRIMARY KEY,project_id BIGINT,gas_reservoir_id BIGINT)");
        jdbc.execute("CREATE TABLE project_well_heads(id BIGINT PRIMARY KEY,project_id BIGINT,project_gas_reservoir_id BIGINT,well_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE project_storage_well(storage_id BIGINT,well_id BIGINT)");
        jdbc.execute("CREATE TABLE dynamic_original_gas_in_place(id BIGINT PRIMARY KEY,project_id BIGINT,project_gas_reservoir_id BIGINT,well_name VARCHAR(100),dynamic_original_gas_inplace_method BIGINT,update_time TIMESTAMP,create_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE dynamic_original_gas_in_place_by_mb_input(id BIGINT PRIMARY KEY,dynamic_original_gas_in_place_id BIGINT,gas_reservoir_type INT)");
        jdbc.execute("CREATE TABLE dynamic_original_gas_in_place_output(dynamic_original_gas_in_place_id BIGINT,project_id BIGINT,project_gas_reservoir_id BIGINT,well_name VARCHAR(100),dynamic_original_gas_inplace_method BIGINT,original_gas_volume DOUBLE,rsquared DOUBLE,reliablity INT)");
        jdbc.execute("CREATE TABLE dynamic_original_gas_in_place_by_mb_input_item(id BIGINT PRIMARY KEY,dynamic_original_gas_inplace_by_mb_input_id BIGINT,date TIMESTAMP,formation_pressure DOUBLE,cumulative_production DOUBLE,cumulative_water_production DOUBLE,is_deleted BOOLEAN)");
        jdbc.update("INSERT INTO project_summaries VALUES(7,0),(9,0)");
        jdbc.update("INSERT INTO project_gas_reservoir VALUES(6,7),(9,9)");
        jdbc.update("INSERT INTO project_storage VALUES(1,7,6)");
        jdbc.update("INSERT INTO project_well_heads VALUES(21,7,6,'X-1'),(22,7,6,'X-2'),(25,7,6,'X-5'),(99,9,9,'X-1')");
        jdbc.update("INSERT INTO project_storage_well VALUES(1,21),(1,22)");
        service = new StorageMaterialBalanceService(jdbc, new StorageCatalogService(jdbc));
    }
    @AfterEach void cleanup() { dataSource.destroy(); }
    void source(long id, long p, long g, String well, int method, double volume, int reliability) {
        jdbc.update("INSERT INTO dynamic_original_gas_in_place VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id,p,g,well,method);
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_by_mb_input VALUES(?,?,1)",id,id);
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_output VALUES(?,?,?,?,?,?,.99,?)",id,p,g,well,method,volume,reliability);
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_by_mb_input_item VALUES(?,?,TIMESTAMP '2005-06-10 00:00:00',30312000,79428720,299.754,FALSE)",id,id);
    }
    @Test void readsMeasuredOnlyConvertsUnitsAndLeavesSourceUntouched() {
        source(1,7,6,"X-1",1,3500778064.134614,2);
        source(2,7,6,"X-2",2,999e8,2);
        var result = service.aggregate(7,6,1);
        assertEquals(1, result.includedWellCount()); assertEquals(35.00778064134614, result.sourceGasVolume(), 1e-10);
        var row = result.rows().getFirst();
        assertEquals("2005-06-10", row.date().toString()); assertEquals(30.312, row.pressure(), 1e-10);
        assertEquals(.7942872, row.gas(), 1e-10); assertEquals(.0299754, row.water(), 1e-10);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dynamic_original_gas_in_place_by_mb_input_item", Integer.class));
        assertEquals(79428720d, jdbc.queryForObject("SELECT cumulative_production FROM dynamic_original_gas_in_place_by_mb_input_item WHERE id=1", Double.class));
    }
    @Test void sameWellOtherProjectOrGasReservoirAndNonMembersDoNotLeak() {
        source(1,9,9,"X-1",1,10e8,2);
        source(2,7,9,"X-1",1,20e8,2);
        source(3,7,6,"X-5",1,30e8,2);
        assertEquals(0, service.aggregate(7,6,1).includedWellCount());
        assertThrows(ResponseStatusException.class, () -> service.aggregate(9,9,1));
    }
    @Test void deletedProjectIsRejected() {
        jdbc.update("UPDATE project_summaries SET delete_status=1 WHERE id=7");
        assertThrows(BusinessException.class, () -> service.aggregate(7,6,1));
    }
    @Test void latestFailedMeasuredResultDoesNotFallBackToOlderMeasuredOrCalculated() {
        source(1,7,6,"X-1",1,10e8,2);
        source(2,7,6,"X-1",1,0,0);
        source(3,7,6,"X-1",2,30e8,2);
        var result = service.aggregate(7,6,1);
        assertEquals(0, result.includedWellCount()); assertEquals(2L, result.wells().getFirst().resultId());
        assertTrue(result.wells().getFirst().warning().contains("最近更新"));
    }
    @Test void wrongOutputScopeAndWrongInputTypeAreRejected() {
        source(1,7,6,"X-1",1,10e8,2);
        source(2,7,6,"X-2",1,20e8,2);
        jdbc.update("UPDATE dynamic_original_gas_in_place_output SET project_id=9 WHERE dynamic_original_gas_in_place_id=1");
        jdbc.update("UPDATE dynamic_original_gas_in_place_by_mb_input SET gas_reservoir_type=2 WHERE id=2");
        assertEquals(0, service.aggregate(7,6,1).includedWellCount());
    }
    @Test void validLatestMeasuredResultIsCountedOnlyOnceAndLowReliabilityIsShown() {
        source(1,7,6,"X-1",1,10e8,2);
        source(2,7,6,"X-1",1,20e8,1);
        var result = service.aggregate(7,6,1);
        assertEquals(20, result.sourceGasVolume()); assertTrue(result.wells().getFirst().warning().contains("偏低"));
    }
}
