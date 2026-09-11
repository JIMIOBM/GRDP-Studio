package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelinePvtThermalPropertiesTests {
    private JdbcTemplate jdbc;
    private PipelinePvtModel models;
    private PipelinePvtThermalProperties service;
    private PipelineGasProperties gas;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:thermal_composition_"+System.nanoTime()
                +";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa",""));
        jdbc.execute("CREATE TABLE project_well_heads(id BIGINT PRIMARY KEY,project_id BIGINT,project_gas_reservoir_id BIGINT,well_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE pipeline_pvt_model(id BIGINT AUTO_INCREMENT PRIMARY KEY,well_id BIGINT UNIQUE,revision INT,pvt_name VARCHAR(100),method VARCHAR(8),composition_json CLOB,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO project_well_heads VALUES(1,6,4,'测试井'),(2,7,4,'测试井'),(3,6,4,'另一口井')");
        var wells=new PipelineWellContext(jdbc);
        models=new PipelinePvtModel(jdbc,wells);gas=new PipelineGasProperties();
        service=new PipelinePvtThermalProperties(new PipelinePvtComposition(jdbc,wells),gas);
        // No pipeline_gas_model (Z/Cp inspection point table) is needed for thermal property evaluation.
    }
    @AfterEach void cleanup() {jdbc.execute("DROP ALL OBJECTS");}
    private List<PipelinePvtModel.ComponentInput> dryGas(double methane) {
        return List.of(new PipelinePvtModel.ComponentInput("CH4",methane),
                new PipelinePvtModel.ComponentInput("CO2",.012),new PipelinePvtModel.ComponentInput("N2",1-methane-.012));
    }
    private PipelinePvtModel.Detail save(int revision,String method,double methane) {
        return models.save(new PipelinePvtModel.Save(6,4,"测试井",revision,"测试组分PVT",method,dryGas(methane)));
    }
    private PipelinePvtThermalProperties.Detail detail(long id,double pressure,double temperature) {
        return service.detail(6,4,"测试井",id,pressure,temperature);
    }
    @Test void compositionDirectlyProvidesCpAndDensityWithoutAnySavedInspectionPointOrLegacyCurve() {
        var pvt=save(0,"PR",.98);
        var properties=detail(pvt.pvtId(),5,39.35);
        var expected=gas.calculate("PR",pvt.composition(),5,39.35);
        assertEquals(expected.densityKgM3(),properties.densityKgM3());
        assertEquals(expected.cpJkgK(),properties.cpJkgK());
        assertTrue(properties.sources().get("cpJkgK").contains("PR"));
        assertTrue(properties.sourceRevision().startsWith("pvt-thermal:v3:standing-teacher-v1:pipeline-pvt:"));
        assertEquals(gas.prepare("PR",pvt.composition()).standingViscosity(5,39.35).viscosityMpaS(),properties.viscosityMpaS());
        assertNotNull(properties.viscosityCalculation());
        assertFalse(properties.issue().contains("先在压缩因子"));
    }
    @Test void anyRequestedGasStateIsRecomputedInsteadOfRestrictingToOldFixedTemperatureCurve() {
        var pvt=save(0,"SRK",.98);
        var first=detail(pvt.pvtId(),5,20);var second=detail(pvt.pvtId(),8,60);
        assertNotEquals(first.densityKgM3(),second.densityKgM3());
        assertNotEquals(first.cpJkgK(),second.cpJkgK());
        assertNotEquals(first.viscosityMpaS(),second.viscosityMpaS());
        assertEquals(gas.calculate("SRK",pvt.composition(),8,60).cpJkgK(),second.cpJkgK());
        assertEquals(first.sourceRevision(),second.sourceRevision(),"State is separate from composition provenance");
    }
    @Test void legacyCurveWithSameRecordIdIsNeverUsedForDensityViscosityOrComposition() {
        var pvt=save(0,"PR",.98);
        jdbc.execute("CREATE TABLE project_well_pvt(id BIGINT,well_id BIGINT)");
        jdbc.execute("CREATE TABLE project_well_pvt_gas_result(pvt_id BIGINT,pressure DOUBLE,temperature DOUBLE,density DOUBLE,viscosity DOUBLE)");
        jdbc.update("INSERT INTO project_well_pvt VALUES(?,1)",pvt.pvtId());
        jdbc.update("INSERT INTO project_well_pvt_gas_result VALUES(?,5,39.35,999,.0127)",pvt.pvtId());
        var properties=detail(pvt.pvtId(),5,39.35);
        assertNotEquals(999.0,properties.densityKgM3());assertNotEquals(.0127,properties.viscosityMpaS());
        assertNull(properties.gasConductivityWmK());
        assertTrue(properties.issue().isBlank());
        assertDoesNotThrow(()->service.requireComplete(properties));
    }
    @Test void compositionAndMethodChangesImmediatelyChangeThermalSourceAndValues() {
        var pvt=save(0,"PR",.98);
        var first=detail(pvt.pvtId(),5,39.35);
        save(1,"PR",.95);var second=detail(pvt.pvtId(),5,39.35);
        assertNotEquals(first.sourceRevision(),second.sourceRevision());assertNotEquals(first.cpJkgK(),second.cpJkgK());
        save(2,"SRK",.95);var third=detail(pvt.pvtId(),5,39.35);
        assertNotEquals(second.sourceRevision(),third.sourceRevision());assertNotEquals(second.densityKgM3(),third.densityKgM3());
    }
    @Test void otherWellsProjectsAndDeletedModelsCannotSupplyProperties() {
        var pvt=save(0,"PR",.98);
        var other=models.save(new PipelinePvtModel.Save(7,4,"测试井",0,"另一项目","PR",dryGas(.98)));
        assertThrows(BusinessException.class,()->detail(other.pvtId(),5,39.35));
        assertThrows(BusinessException.class,()->service.detail(6,4,"另一口井",pvt.pvtId(),5.0,39.35));
        assertThrows(BusinessException.class,()->service.detail(6,999,"测试井",pvt.pvtId(),5.0,39.35));
        jdbc.update("DELETE FROM pipeline_pvt_model WHERE id=?",pvt.pvtId());
        assertThrows(BusinessException.class,()->detail(pvt.pvtId(),5,39.35));
    }
    @Test void missingPvtOrStateIsActionableAndMissingSelectionStillValidatesWellScope() {
        var empty=service.detail(6,4,"测试井",null,null,null);
        assertTrue(empty.issue().contains("完整气体组成"));assertNull(empty.densityKgM3());
        assertThrows(BusinessException.class,()->service.detail(6,999,"测试井",null,null,null));
        var pvt=save(0,"PR",.98);
        var missing=service.detail(6,4,"测试井",pvt.pvtId(),null,null);
        assertTrue(missing.issue().contains("物性压力"));assertTrue(missing.issue().contains("物性温度"));
        assertNull(missing.cpJkgK());assertNull(detail(pvt.pvtId(),-1,-273.15).densityKgM3());
    }
    @Test void invalidPersistedCompositionDoesNotYieldGasProperties() {
        var pvt=save(0,"PR",.98);
        jdbc.update("UPDATE pipeline_pvt_model SET composition_json=? WHERE id=?","[{\"code\":\"CH4\",\"moleFraction\":0.8}]",pvt.pvtId());
        var invalid=detail(pvt.pvtId(),5,39.35);
        assertNull(invalid.densityKgM3());assertNull(invalid.cpJkgK());assertTrue(invalid.issue().contains("100%"));
    }
    @Test void standingProvidesViscosityWhileConductivityBelongsToThePipeInput() {
        var pvt=save(0,"BWRS",.98);var properties=detail(pvt.pvtId(),5,39.35);
        assertTrue(properties.densityKgM3()>0);assertTrue(properties.cpJkgK()>0);
        assertTrue(properties.viscosityMpaS()>0);assertNull(properties.gasConductivityWmK());
        assertEquals(4,properties.sources().size());
        assertTrue(properties.sources().get("viscosityMpaS").contains("Standing"));
        assertDoesNotThrow(()->service.requireComplete(properties));
    }
    @Test void completenessRequiresThreeAutomaticPropertiesAndAllowsSeparatelyProvidedConductivity() {
        var complete=new PipelinePvtThermalProperties.Detail(1L,"测试","test",5.0,39.0,50.0,.02,2200.0,null,Map.of(),"");
        assertDoesNotThrow(()->service.requireComplete(complete));
        assertThrows(BusinessException.class,()->service.requireComplete(null));
        var missing=new PipelinePvtThermalProperties.Detail(1L,"测试","test",5.0,39.0,50.0,null,2200.0,.035,Map.of(),"");
        assertThrows(BusinessException.class,()->service.requireComplete(missing));
    }
}
