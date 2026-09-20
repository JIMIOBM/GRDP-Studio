package com.grdp.studio.storagematerialbalance;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class StorageMaterialBalanceSourceTests {
    StorageMaterialBalanceServiceTests fixture;
    JdbcTemplate jdbc;
    StorageMaterialBalanceSourceService service;
    @BeforeEach void setup() {
        fixture = new StorageMaterialBalanceServiceTests(); fixture.setup(); jdbc = fixture.jdbc;
        fixture.source(1,7,6,"X-1",1,3500778064.134614,2);
        fixture.source(2,7,6,"X-2",2,20e8,2);
        fixture.source(3,7,6,"X-5",1,30e8,2);
        fixture.source(4,9,9,"X-1",1,40e8,2);
        jdbc.execute("ALTER TABLE dynamic_original_gas_in_place_by_mb_input ADD gas_type VARCHAR(30) DEFAULT '干气'");
        for (String name : new String[]{"specific_gravity","hydrogen_sulfide","carbon_dioxide","nitrogen","temperature","water_gas_ratio_limit"})
            jdbc.execute("ALTER TABLE dynamic_original_gas_in_place_by_mb_input ADD " + name + " DOUBLE");
        for (String name : new String[]{"modification_method","deviation_factor_method"})
            jdbc.execute("ALTER TABLE dynamic_original_gas_in_place_by_mb_input ADD " + name + " INT DEFAULT 0");
        jdbc.update("UPDATE dynamic_original_gas_in_place_by_mb_input SET specific_gravity=.58,hydrogen_sulfide=.0462,carbon_dioxide=.0396,nitrogen=0,temperature=353.15,water_gas_ratio_limit=.00000602");
        jdbc.execute("ALTER TABLE dynamic_original_gas_in_place_output ADD id BIGINT");
        jdbc.execute("ALTER TABLE dynamic_original_gas_in_place_output ADD intercept DOUBLE DEFAULT 31217243.331049547");
        jdbc.execute("ALTER TABLE dynamic_original_gas_in_place_output ADD gradient DOUBLE DEFAULT -.008917230044049191");
        jdbc.execute("ALTER TABLE dynamic_original_gas_in_place_output ADD reliability_desc VARCHAR(100) DEFAULT '分析结果可靠性较高'");
        jdbc.update("UPDATE dynamic_original_gas_in_place_output SET id=dynamic_original_gas_in_place_id");
        jdbc.execute("CREATE TABLE dynamic_original_gas_in_place_output_item(id BIGINT,date TIMESTAMP,formation_pressure DOUBLE,pressure DOUBLE,cumulative_gas_production DOUBLE,cumulative_water_production DOUBLE,linear_regression_pressure DOUBLE,is_deleted BOOLEAN,dynamic_original_gas_inplace_output_id BIGINT)");
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_output_item VALUES(1,TIMESTAMP '2005-06-10 00:00:00',30312000,30554630.646744568,79428720,299.754,NULL,FALSE,1)");
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_output_item VALUES(2,TIMESTAMP '2005-06-10 00:00:00',NULL,NULL,79428720,NULL,30508959.162705176,FALSE,1)");
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_output_item VALUES(3,TIMESTAMP '2006-06-10 00:00:00',NULL,NULL,292150560,NULL,28000000,FALSE,1)");
        jdbc.update("INSERT INTO dynamic_original_gas_in_place_output_item VALUES(4,TIMESTAMP '2006-06-10 00:00:00',27900000,28704853.080207244,292150560,791.094,NULL,TRUE,1)");
        service = new StorageMaterialBalanceSourceService(jdbc,new StorageCatalogService(jdbc));
    }
    @AfterEach void cleanup() { fixture.cleanup(); }
    @Test void convertsSourceUnitsWithoutTurningInputPressureIntoRegressionPressure() {
        var detail = service.detail(7,6,1,21,1);
        assertEquals(80,detail.parameters().temperature(),1e-9);
        assertEquals(4.62,detail.parameters().hydrogenSulfide(),1e-9);
        assertEquals(3.96,detail.parameters().carbonDioxide(),1e-9);
        assertEquals(.0602,detail.parameters().waterGasRatioLimit(),1e-9);
        assertEquals(35.00778064134614,detail.output().gasVolume(),1e-9);
        assertEquals(31.217243331049547,detail.output().intercept(),1e-9);
        assertEquals(-.8917230044049191,detail.output().gradient(),1e-9);
        assertEquals(30.312,detail.inputRows().getFirst().pressure(),1e-9);
        assertEquals(30.554630646744568,detail.resultRows().getFirst().pressure(),1e-9);
        assertEquals("2005-06-10",detail.inputRows().getFirst().date().toString());
    }
    @Test void distinguishesOriginalInputPointsFromSeparateStoredRegressionRows() {
        var detail = service.detail(7,6,1,21,1);
        assertEquals(1,detail.inputRows().size()); assertEquals(2,detail.resultRows().size());
        assertEquals(2,detail.regressionLine().size()); assertTrue(detail.resultRows().getLast().deleted());
        assertEquals(.7942872,detail.regressionLine().getFirst().gas(),1e-9);
        assertEquals(30.508959162705176,detail.regressionLine().getFirst().pressure(),1e-9);
    }
    @Test void refusesCalculatedPressureForeignScopeAndNonMemberWell() {
        assertThrows(BusinessException.class,()->service.detail(7,6,1,22,2));
        assertThrows(BusinessException.class,()->service.detail(7,6,1,21,4));
        assertThrows(BusinessException.class,()->service.detail(7,6,1,25,3));
        assertThrows(BusinessException.class,()->service.detail(7,6,1,22,1));
    }
    @Test void availabilityExplainsMeasuredDataOutsideStorageWithoutAddingMembership() {
        var list = service.availability(7,6,1);
        var first = list.stream().filter(w->w.wellId()==21).findFirst().orElseThrow();
        var second = list.stream().filter(w->w.wellId()==22).findFirst().orElseThrow();
        var outside = list.stream().filter(w->w.wellId()==25).findFirst().orElseThrow();
        assertEquals(1,first.measuredCount()); assertEquals(0,first.calculatedCount());
        assertEquals(0,second.measuredCount()); assertEquals(1,second.calculatedCount());
        assertFalse(outside.inStorage()); assertEquals(1,outside.measuredCount());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM project_storage_well",Integer.class));
    }
    @Test void failedOriginalRegressionKeepsDataButDoesNotDrawASuccessfulLine() {
        jdbc.update("UPDATE dynamic_original_gas_in_place_output SET reliablity=0,original_gas_volume=0 WHERE id=1");
        var detail = service.detail(7,6,1,21,1);
        assertEquals(0,detail.output().gasVolume()); assertTrue(detail.regressionLine().isEmpty());
        assertEquals(2,detail.resultRows().size()); assertTrue(detail.message().contains("失败"));
    }
    @Test void missingOutputOrInputsAreExplainedNotRecalculated() {
        jdbc.update("DELETE FROM dynamic_original_gas_in_place_output WHERE id=1");
        var detail = service.detail(7,6,1,21,1);
        assertNull(detail.output()); assertTrue(detail.message().contains("尚未保存"));
        assertEquals(1,detail.inputRows().size());
        jdbc.update("DELETE FROM dynamic_original_gas_in_place_by_mb_input WHERE id=1");
        detail = service.detail(7,6,1,21,1);
        assertNull(detail.parameters()); assertTrue(detail.inputRows().isEmpty());
        assertTrue(detail.message().contains("缺失"));
    }
    @Test void deletedProjectCannotExposeSavedSources() {
        jdbc.update("UPDATE project_summaries SET delete_status=1 WHERE id=7");
        assertThrows(BusinessException.class,()->service.detail(7,6,1,21,1));
    }
    @Test void neverUsesOutputOfAnotherScopeEvenWhenParentReferenceMatches() {
        jdbc.update("UPDATE dynamic_original_gas_in_place_output SET project_id=9 WHERE id=1");
        var detail = service.detail(7,6,1,21,1);
        assertNull(detail.output()); assertTrue(detail.resultRows().isEmpty()); assertTrue(detail.regressionLine().isEmpty());
    }
}
