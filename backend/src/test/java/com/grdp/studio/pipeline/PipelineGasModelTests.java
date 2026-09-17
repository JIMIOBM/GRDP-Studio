package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineGasProperties.Fraction;
import static org.junit.jupiter.api.Assertions.*;

class PipelineGasModelTests {
    private PipelineGasModel models;
    private PipelinePvtComposition compositions;
    private PipelinePvtModel pvts;
    private PipelineGasProperties gas;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private long pvtId=11;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:gasmodel_"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE project_well_heads(id BIGINT PRIMARY KEY,project_id BIGINT,project_gas_reservoir_id BIGINT,well_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE project_well_pvt(id BIGINT PRIMARY KEY,well_id BIGINT)");
        jdbc.execute("CREATE TABLE project_well_pvt_gas_input(pvt_id BIGINT PRIMARY KEY,hydrogen_sulfide DOUBLE,carbon_dioxide DOUBLE,nitrogen DOUBLE)");
        jdbc.update("INSERT INTO project_well_heads VALUES(1,6,4,'测试井'),(2,7,4,'测试井'),(3,6,4,'另一口井')");
        jdbc.update("INSERT INTO project_well_pvt VALUES(11,1)");
        jdbc.update("INSERT INTO project_well_pvt_gas_input VALUES(11,0,5,95)");
        String sql=Files.readString(Path.of("sql/pipeline_capacity.sql"),StandardCharsets.UTF_8)
                .replaceAll("(?s)-- BEGIN REMOVE_BOUNDARY_USAGE.*?-- END REMOVE_BOUNDARY_USAGE", "")
                .replaceAll("(?m)^--.*$", "")
                .replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='[^']*'", "")
                .replaceAll("\\bJSON\\b","CLOB");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(ds);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var json=JsonMapper.builder().build();var wells=new PipelineWellContext(jdbc);gas=new PipelineGasProperties();
        compositions=new PipelinePvtComposition(jdbc,wells);pvts=new PipelinePvtModel(jdbc,wells);
        models=new PipelineGasModel(jdbc,json,wells,compositions,gas);
    }
    @AfterEach void teardown() {if(jdbc!=null)jdbc.execute("DROP ALL OBJECTS");}
    private List<Fraction> mixture() {return List.of(new Fraction("CH4",.98),new Fraction("CO2",.012),new Fraction("N2",.008));}
    private PipelinePvtModel.Detail savePvt(String method,List<Fraction> fractions) {
        var current=pvts.detail(6,4,"测试井");
        var saved=tx.execute(t->pvts.save(new PipelinePvtModel.Save(6,4,"测试井",current==null?0:current.revision(),"测试天然气",method,
                fractions.stream().map(f->new PipelinePvtModel.ComponentInput(f.code(),f.moleFraction())).toList())));
        pvtId=saved.pvtId();return saved;
    }
    private PipelineGasModel.Request request(int revision,String method,String kind,double pressure,double temperature) {
        return new PipelineGasModel.Request(6,4,"测试井",pvtId,revision,method,kind,pressure,temperature);
    }
    @Test void currentPvtSuppliesSnapshotAndBoundaryPropertiesBeforeAnyZOrCpPointIsSaved() {
        var pvt=savePvt("PR",mixture());assertNull(models.detail(6,4,"测试井"));
        var snapshot=models.snapshot(6,4,"测试井");
        assertEquals(pvt.revision(),snapshot.revision());assertEquals(pvt.pvtId(),snapshot.pvtId());
        assertEquals(pvt.compositionRevision(),snapshot.compositionRevision());assertEquals(mixture(),snapshot.composition());
        var base=PipelineCalculatorTests.example();var resolved=models.resolve(6,4,"测试井",base);
        var expected=gas.calculate("PR",mixture(),base.inletMpa(),base.inletC());
        assertEquals(expected.z(),resolved.z());assertEquals(expected.cpJkgK(),resolved.cpJkgK());
        assertEquals(snapshot,resolved.gasModel());assertNull(models.detail(6,4,"测试井"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_gas_model",Integer.class));
    }
    @Test void parameterPreviewUsesTheAuthoritativePvtMethodWithoutWritingInspectionPoints() {
        var source=savePvt("SRK",mixture());
        var preview=models.parameters(6,4,"测试井",pvtId,"SRK",null,null);
        assertEquals(source.compositionRevision(),preview.compositionRevision());
        assertEquals("SRK",preview.parameters().method());assertEquals("",preview.parameters().issue());assertEquals(3,preview.parameters().species().size());
        assertTrue(preview.parameters().groups().stream().anyMatch(g->g.key().equals("mixture")));
        assertNull(models.detail(6,4,"测试井"));assertEquals(source,pvts.detail(6,4,"测试井"));
        assertThrows(BusinessException.class,()->models.parameters(7,4,"测试井",pvtId,"SRK",null,null));
    }
    @Test void tamperedOrStalePageMethodsCannotOverrideThePvtMethod() {
        savePvt("PR",mixture());
        for(String method:Arrays.asList("SRK","BWRS",null)) {
            assertEquals(409,assertThrows(BusinessException.class,()->models.calculate(request(0,method,"z",5,40))).getCode());
            assertEquals(409,assertThrows(BusinessException.class,()->tx.execute(t->models.save(request(0,method,"cp",5,40)))).getCode());
            assertEquals(409,assertThrows(BusinessException.class,()->models.parameters(6,4,"测试井",pvtId,method,5.0,40.0)).getCode());
        }
        assertNull(models.detail(6,4,"测试井"));assertEquals("PR",pvts.detail(6,4,"测试井").method());
    }
    @Test void malformedStoredCompositionCanBePreviewedButNeverCalculatedOrResolved() {
        savePvt("BWRS",mixture());
        jdbc.update("UPDATE pipeline_pvt_model SET composition_json=? WHERE id=?","[{\"code\":\"CH4\",\"moleFraction\":0.98}]",pvtId);
        var preview=models.parameters(6,4,"测试井",pvtId,"BWRS",5.0,40.0);
        assertFalse(preview.parameters().issue().isBlank());
        assertFalse(preview.parameters().groups().stream().anyMatch(g->g.key().equals("mixture")));
        assertThrows(BusinessException.class,()->models.calculate(request(0,"BWRS","z",5,40)));
        assertThrows(BusinessException.class,()->tx.execute(t->models.save(request(0,"BWRS","z",5,40))));
        assertThrows(BusinessException.class,()->models.snapshot(6,4,"测试井"));
        assertThrows(BusinessException.class,()->models.resolve(6,4,"测试井",PipelineCalculatorTests.example()));
        assertNull(models.detail(6,4,"测试井"));
    }
    @Test void calculationCarriesImmutableParametersFromTheSamePointMethodAndComposition() {
        savePvt("PR",mixture());var calculation=models.calculate(request(0,"PR","z",5,40));
        var snapshot=calculation.parameterSnapshot();assertEquals("PR",snapshot.method());
        assertTrue(snapshot.groups().stream().anyMatch(g->g.key().equals("caloric")&&g.rows().stream().anyMatch(r->r.key().equals("molarDensityMolM3"))));
        String before=JsonMapper.builder().build().writeValueAsString(snapshot);
        savePvt("SRK",List.of(new Fraction("CH4",.95),new Fraction("CO2",.03),new Fraction("N2",.02)));
        var changed=models.parameters(6,4,"测试井",pvtId,"SRK",3.0,60.0);
        assertNotEquals(calculation.compositionRevision(),changed.compositionRevision());
        assertEquals(before,JsonMapper.builder().build().writeValueAsString(snapshot));assertNull(models.detail(6,4,"测试井"));
    }
    @Test void calculationAndSaveAcceptValidStatesOutsideTheFormerEosInputBounds() {
        int pointRevision=0;
        for(String method:List.of("PR","SRK","BWRS")) {
            var source=savePvt(method,List.of(new Fraction("N2",1)));
            for(double[] pt:List.of(new double[]{70,40},new double[]{1e-8,26.85},new double[]{.1,-123.15},new double[]{1,326.85})) {
                var request=request(pointRevision,method,"cp",pt[0],pt[1]);var calculation=models.calculate(request);
                assertEquals(source.compositionRevision(),calculation.compositionRevision());assertTrue(Double.isFinite(calculation.z())&&calculation.z()>0);
                var saved=tx.execute(t->models.save(request));
                assertEquals(pt[0],saved.points().get("cp").pressureMpa());assertEquals(pt[1],saved.points().get("cp").temperatureC());
                assertEquals(calculation.cpJkgK(),saved.points().get("cp").result().cpJkgK());assertEquals(saved,models.detail(6,4,"测试井"));
                pointRevision=saved.revision();assertEquals(source.revision(),models.snapshot(6,4,"测试井").revision());
            }
        }
    }
    @Test void calculationAndSaveRejectNonphysicalAndNonfiniteStatesWithoutWritingAPoint() {
        savePvt("PR",List.of(new Fraction("N2",1)));
        for(double[] pt:List.of(new double[]{0,40},new double[]{-1,40},new double[]{Double.NaN,40},
                new double[]{1,-273.15},new double[]{1,-274},new double[]{1,Double.POSITIVE_INFINITY})) {
            var request=request(0,"PR","z",pt[0],pt[1]);
            assertThrows(BusinessException.class,()->models.calculate(request));assertThrows(BusinessException.class,()->tx.execute(t->models.save(request)));
        }
        assertNull(models.detail(6,4,"测试井"));
    }
    @Test void preservesIndependentInspectionPointsWithoutChangingPvtRevisionOrMethod() {
        var source=savePvt("PR",mixture());var calculated=models.calculate(request(0,"PR","z",5,40));assertTrue(calculated.z()>0);
        assertNull(models.detail(6,4,"测试井"));
        var first=tx.execute(t->models.save(request(0,"PR","z",5,40)));
        var second=tx.execute(t->models.save(request(1,"PR","cp",3,60)));
        assertEquals(2,second.revision());assertEquals(Set.of("z","cp"),second.points().keySet());
        assertEquals(first.points().get("z"),second.points().get("z"));assertEquals(3,second.points().get("cp").pressureMpa());assertEquals(60,second.points().get("cp").temperatureC());
        assertEquals(second,models.detail(6,4,"测试井"));assertEquals(source,pvts.detail(6,4,"测试井"));
        assertEquals(source.revision(),models.snapshot(6,4,"测试井").revision());
    }
    @Test void readingStaleInspectionPointsPreservesTheirStoredParameterSnapshotsAndJson() {
        savePvt("PR",mixture());var calculation=models.calculate(request(0,"PR","z",5,40));
        var saved=tx.execute(t->models.save(request(0,"PR","z",5,40)));var point=saved.points().get("z");
        assertEquals(calculation.parameterSnapshot(),point.parameterSnapshot());
        String stored=jdbc.queryForObject("SELECT settings_json FROM pipeline_gas_model WHERE well_id=1",String.class);
        var next=savePvt("SRK",mixture());var historical=models.detail(6,4,"测试井");
        assertTrue(historical.compositionChanged());assertEquals(point,historical.points().get("z"));
        assertEquals(stored,jdbc.queryForObject("SELECT settings_json FROM pipeline_gas_model WHERE well_id=1",String.class));
        assertEquals(next.method(),models.snapshot(6,4,"测试井").method());assertEquals(next.revision(),models.snapshot(6,4,"测试井").revision());
        var resolved=models.resolve(6,4,"测试井",PipelineCalculatorTests.example());assertEquals("SRK",resolved.gasModel().method());
    }
    @Test void legacyPointsRemainReadableWithoutInventingMissingParameterSnapshots() {
        savePvt("PR",mixture());var state=gas.calculate("PR",mixture(),5,40);var json=JsonMapper.builder().build();
        String legacy=json.writeValueAsString(Map.of("revision",1,"pvtId",pvtId,"compositionRevision",compositions.current(6,4,"测试井").revision(),
                "method","PR","compositionChanged",false,"points",Map.of("z",Map.of("pressureMpa",5,"temperatureC",40,"result",state))));
        jdbc.update("INSERT INTO pipeline_gas_model(well_id,revision,settings_json) VALUES(1,1,?)",legacy);
        var detail=models.detail(6,4,"测试井");assertEquals(state,detail.points().get("z").result());assertNull(detail.points().get("z").parameterSnapshot());
        assertFalse(detail.compositionChanged());
        var saved=tx.execute(t->models.save(request(1,"PR","cp",3,60)));
        assertNull(saved.points().get("z").parameterSnapshot());assertNotNull(saved.points().get("cp").parameterSnapshot());
    }
    @Test void explicitlySavingNewSourceClearsOtherStaleResultButRetainsItsConditions() {
        savePvt("PR",mixture());tx.executeWithoutResult(t->models.save(request(0,"PR","z",5,40)));tx.executeWithoutResult(t->models.save(request(1,"PR","cp",3,60)));
        savePvt("SRK",mixture());var changed=tx.execute(t->models.save(request(2,"SRK","cp",3,60)));
        assertEquals("SRK",changed.method());assertNull(changed.points().get("z").result());assertNull(changed.points().get("z").parameterSnapshot());
        assertEquals(5,changed.points().get("z").pressureMpa());assertEquals(40,changed.points().get("z").temperatureC());
        assertEquals("SRK",changed.points().get("cp").parameterSnapshot().method());
        jdbc.update("DELETE FROM pipeline_pvt_model WHERE id=?",pvtId);savePvt("SRK",mixture());
        var replaced=tx.execute(t->models.save(request(3,"SRK","z",5,40)));
        assertEquals(pvtId,replaced.pvtId());assertNotNull(replaced.points().get("z").result());assertNull(replaced.points().get("cp").result());
        assertEquals(3,replaced.points().get("cp").pressureMpa());assertEquals(60,replaced.points().get("cp").temperatureC());
    }
    @Test void compositionChangeUpdatesCurrentSnapshotButCannotMutatePreviouslyReturnedSnapshot() {
        var source=savePvt("PR",mixture());tx.executeWithoutResult(t->models.save(request(0,"PR","z",5,40)));
        var snapshot=models.snapshot(6,4,"测试井");var state=gas.calculate(snapshot.method(),snapshot.composition(),5,40);
        String before=JsonMapper.builder().build().writeValueAsString(snapshot);
        var changed=savePvt("PR",List.of(new Fraction("CH4",.95),new Fraction("CO2",.03),new Fraction("N2",.02)));
        var current=models.snapshot(6,4,"测试井");assertNotEquals(source.compositionRevision(),current.compositionRevision());
        assertEquals(changed.revision(),current.revision());assertTrue(models.detail(6,4,"测试井").compositionChanged());
        assertEquals(mixture(),snapshot.composition());assertEquals(before,JsonMapper.builder().build().writeValueAsString(snapshot));
        assertEquals(state,gas.calculate(snapshot.method(),snapshot.composition(),5,40));
        assertThrows(UnsupportedOperationException.class,()->snapshot.composition().add(new Fraction("N2",0)));
    }
    @Test void staleInspectionPageSaveCannotOverwriteNewPointRevision() {
        savePvt("PR",mixture());tx.executeWithoutResult(t->models.save(request(0,"PR","z",5,40)));
        assertEquals(409,assertThrows(BusinessException.class,()->tx.execute(t->models.save(request(0,"PR","cp",4,50)))).getCode());
        tx.executeWithoutResult(t->models.save(request(1,"PR","cp",4,50)));
        assertEquals(409,assertThrows(BusinessException.class,()->tx.execute(t->models.save(request(1,"PR","z",5,40)))).getCode());
        assertEquals(2,models.detail(6,4,"测试井").revision());assertEquals(1,pvts.detail(6,4,"测试井").revision());
    }
    @Test void missingNewPvtRejectsLegacyPvtIdsAndBrowserFixedGasValues() {
        assertNull(models.snapshot(6,4,"测试井"));assertNull(models.detail(6,4,"测试井"));
        assertThrows(BusinessException.class,()->models.calculate(request(0,"PR","z",5,40)));
        assertThrows(BusinessException.class,()->tx.execute(t->models.save(request(0,"PR","z",5,40))));
        var failure=assertThrows(BusinessException.class,()->models.resolve(6,4,"测试井",PipelineCalculatorTests.example()));
        assertTrue(failure.getMessage().contains("PVT 模型"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_gas_model",Integer.class));
    }
    @Test void selectedPvtMustBelongToTheCurrentWellAndProject() {
        savePvt("PR",mixture());
        var foreign=pvts.save(new PipelinePvtModel.Save(7,4,"测试井",0,"其他项目","PR",List.of(new PipelinePvtModel.ComponentInput("N2",1.0))));
        for(long id:List.of(foreign.pvtId(),11L,99L)) {
            var request=new PipelineGasModel.Request(6,4,"测试井",id,0,"PR","z",5.0,40.0);
            assertThrows(BusinessException.class,()->models.calculate(request));assertThrows(BusinessException.class,()->tx.execute(t->models.save(request)));
        }
        assertNull(models.detail(6,4,"测试井"));assertNull(models.detail(7,4,"测试井"));
    }
    @Test void deletingNewPvtLeavesHistoricalPointsVisibleButPreventsAnyFallback() {
        savePvt("PR",mixture());tx.executeWithoutResult(t->models.save(request(0,"PR","z",5,40)));var before=models.detail(6,4,"测试井");
        String stored=jdbc.queryForObject("SELECT settings_json FROM pipeline_gas_model WHERE well_id=1",String.class);
        jdbc.update("DELETE FROM pipeline_pvt_model WHERE id=?",pvtId);
        var deleted=models.detail(6,4,"测试井");assertEquals(before.points(),deleted.points());assertTrue(deleted.compositionChanged());
        assertNull(models.snapshot(6,4,"测试井"));assertThrows(BusinessException.class,()->models.resolve(6,4,"测试井",PipelineCalculatorTests.example()));
        assertEquals(stored,jdbc.queryForObject("SELECT settings_json FROM pipeline_gas_model WHERE well_id=1",String.class));
    }
    @Test void recomputesBoundaryPropertiesRatherThanUsingTheSavedInspectionPoint() {
        savePvt("PR",mixture());tx.executeWithoutResult(t->models.save(request(0,"PR","z",1,25)));
        var base=PipelineCalculatorTests.example();var resolved=models.resolve(6,4,"测试井",base);var expected=gas.calculate("PR",mixture(),base.inletMpa(),base.inletC());
        assertEquals(expected.z(),resolved.z());assertEquals(expected.cpJkgK(),resolved.cpJkgK());
        assertEquals(base.segments(),resolved.segments());assertEquals(base.equipment(),resolved.equipment());
        assertEquals(gas.prepare("PR",mixture()).standingViscosity(base.inletMpa(),base.inletC()).viscosityMpaS(),resolved.viscosityMpaS());
        assertNotEquals(base.viscosityMpaS(),resolved.viscosityMpaS());
        assertNotEquals(models.detail(6,4,"测试井").points().get("z").result().z(),resolved.z());
    }
}
