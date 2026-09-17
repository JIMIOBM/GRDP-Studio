package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PipelineStorageTests {
    private static Boundary single(int revision,String operatingAt,List<BoundaryNode> nodes) {
        return new Boundary(revision,"case-1",List.of(new BoundaryCase("case-1",operatingAt,nodes)));
    }
    private PipelineStorage storage;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private PipelineTopology topology;
    private PipelineGasModel gasModel;
    private PipelineTemperature storedTemperature;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:pipeline_"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new JdbcTemplate(ds).execute("CREATE TABLE project_well_heads(id BIGINT PRIMARY KEY,project_id BIGINT,project_gas_reservoir_id BIGINT,well_name VARCHAR(100))");
        new JdbcTemplate(ds).update("INSERT INTO project_well_heads VALUES(1,6,4,'测试井'),(2,7,4,'测试井'),(3,6,4,'另一口井')");
        new JdbcTemplate(ds).execute("CREATE TABLE project_well_pvt(id BIGINT PRIMARY KEY,well_id BIGINT,pvt_name VARCHAR(100),pvt_no INT)");
        new JdbcTemplate(ds).execute("CREATE TABLE project_well_pvt_gas_input(pvt_id BIGINT PRIMARY KEY,hydrogen_sulfide DOUBLE,carbon_dioxide DOUBLE,nitrogen DOUBLE,gas_type VARCHAR(100),specific_gravity DOUBLE,condensate_oil_density DOUBLE)");
        new JdbcTemplate(ds).execute("CREATE TABLE project_well_pvt_gas_result(pvt_id BIGINT,pressure DOUBLE,temperature DOUBLE,density DOUBLE,viscosity DOUBLE)");
        // The ONLY module DDL is the user-deliverable SQL. Adapt JSON storage and table options for H2.
        String sql=Files.readString(Path.of("sql/pipeline_capacity.sql"),StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$", "")
                .replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='[^']*'", "")
                .replaceAll("\\bJSON\\b","CLOB");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(ds);
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var mapper=productionMapper();var wells=new PipelineWellContext(jdbc);
        topology=new PipelineTopology(jdbc,mapper,wells);
        var gas=new PipelineGasProperties();
        gasModel=new PipelineGasModel(jdbc,mapper,wells,new PipelinePvtComposition(jdbc,wells),gas);
        storedTemperature=temperatureService(new PipelinePvtThermalProperties(new PipelinePvtComposition(jdbc,wells),gas));
        storage=new PipelineStorage(jdbc,mapper,wells,new PipelineFlowTopology(topology));
        insertComposition(10,1);
    }
    @AfterEach void teardown() { if(jdbc!=null) jdbc.execute("DROP ALL OBJECTS"); }
    private void insertComposition(long id,long wellId) {
        // Synthetic gas sample used only by storage tests; no legacy PVT data is inferred.
        jdbc.update("INSERT INTO pipeline_pvt_model(id,well_id,revision,pvt_name,method,composition_json) VALUES(?,?,1,'测试组分PVT','PR',?)",
                id,wellId,productionMapper().writeValueAsString(List.of(new PipelineGasProperties.Fraction("CH4",.98),
                        new PipelineGasProperties.Fraction("CO2",.012),new PipelineGasProperties.Fraction("N2",.008))));
    }
    private void changeComposition(double methane) {
        jdbc.update("UPDATE pipeline_pvt_model SET revision=revision+1,composition_json=? WHERE id=10",
                productionMapper().writeValueAsString(List.of(new PipelineGasProperties.Fraction("CH4",methane),
                        new PipelineGasProperties.Fraction("CO2",.012),new PipelineGasProperties.Fraction("N2",1-methane-.012))));
    }
    private Input example() {
        var input=PipelineCalculatorTests.example();var snapshot=gasModel.snapshot(6,4,"测试井");
        input=withArrays(input,input.segments().stream().map(s->new Segment(s.name(),s.lengthM(),s.diameterMm(),
                s.roughnessMm(),s.elevationChangeM(),0,0)).toList(),input.equipment());
        return snapshot==null?input:gasModel.withSnapshot(input,snapshot);
    }
    private void configureGas() {
        tx.executeWithoutResult(t->gasModel.save(new PipelineGasModel.Request(6,4,"测试井",10,0,"PR","z",8.0,40.0)));
    }
    @Test void overallTemperatureAndCoupledSolveUseEosInsteadOfMissingManualDensityAndHeatCapacity() {
        configureGas();saveGraph(0,flowGraph(1000));
        String edge=topology.detail(6,4,"测试井").graph().edges().getFirst().id();
        var c=PipelineTemperatureTests.config(edge);
        var automatic=new PipelineTemperatureCalculator.Config(c.edgeId(),c.externalMethod(),c.ambientC(),c.burialDepthM(),
                c.soilConductivityWmK(),c.surfaceCoefficientWm2K(),null,.1,null,null,.035,10L,6.0,45.0,"",c.layers());
        var service=new PipelineTemperature(jdbc,productionMapper(),new PipelineWellContext(jdbc),topology,new PipelineTemperatureCalculator(),new PipelineCalculator(),gasModel,new PipelineGasProperties(),completeTestPvtProperties());
        var row=service.calculate("overall",new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(automatic))).getFirst();
        assertNull(row.error());assertTrue(row.pvtProperties().cpJkgK()>1000);
        var settings=new PipelineTemperature.Settings(List.of(automatic),.001,30);
        var solution=service.solve(new PipelineTemperature.Solve(new PipelineTemperature.Request(6,4,"测试井",0,1,settings),example()));
        assertNotNull(solution.input().gasModel());assertTrue(solution.coefficients().getFirst().properties().densityKgM3()>0);
        assertTrue(solution.result().outletC()<45);assertTrue(solution.iterations()>1);
    }
    private PipelineTemperature temperatureService() { return temperatureService(completeTestPvtProperties()); }
    private PipelineTemperature temperatureService(PipelinePvtThermalProperties provider) {
        return new PipelineTemperature(jdbc,productionMapper(),new PipelineWellContext(jdbc),topology,
                new PipelineTemperatureCalculator(),new PipelineCalculator(),gasModel,new PipelineGasProperties(),provider);
    }
    /** Synthetic complete properties are a test fixture, never a fallback for missing production PVT data. */
    private PipelinePvtThermalProperties completeTestPvtProperties() {
        var provider=mock(PipelinePvtThermalProperties.class,CALLS_REAL_METHODS);
        doAnswer(invocation->{
            long project=invocation.getArgument(0),reservoir=invocation.getArgument(1);
            String well=invocation.getArgument(2);Long pvtId=invocation.getArgument(3);
            Double pressure=invocation.getArgument(4),temperature=invocation.getArgument(5);
            long wellId=new PipelineWellContext(jdbc).require(project,reservoir,well).id();
            if(pvtId==null)return new PipelinePvtThermalProperties.Detail(null,null,null,pressure,temperature,
                    null,null,null,null,Map.of(),"请选择当前井已保存的 PVT 性质");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_pvt_model WHERE id=? AND well_id=?",Integer.class,pvtId,wellId)!=1)
                throw new BusinessException(400,"PVT记录不属于当前井或已删除，请重新选择");
            if(pressure==null||!Double.isFinite(pressure)||pressure<=0||temperature==null||!Double.isFinite(temperature)||temperature<=-273.15)
                return new PipelinePvtThermalProperties.Detail(pvtId,"测试用完整 PVT","test-source",pressure,temperature,
                        null,null,null,null,Map.of(),"请填写有效的物性压力和物性温度");
            var model=gasModel.snapshot(project,reservoir,well);
            double density=50,cp=2300;String revision="synthetic-test-properties";
            if(model!=null&&model.pvtId()==pvtId) {
                var snapshot=gasModel.snapshot(project,reservoir,well);
                var state=new PipelineGasProperties().calculate(snapshot.method(),snapshot.composition(),pressure,temperature);
                density=state.densityKgM3();cp=state.cpJkgK();revision=snapshot.compositionRevision()+":"+snapshot.revision();
            }
            return new PipelinePvtThermalProperties.Detail(pvtId,"测试用完整 PVT",revision,pressure,temperature,
                    density,.012,cp,null,Map.of("densityKgM3","测试来源：同 PVT EOS 或合成密度",
                    "viscosityMpaS","仅测试合成黏度","cpJkgK","测试来源：同 PVT EOS 或合成 Cp"),"");
        }).when(provider).detail(anyLong(),anyLong(),anyString(),nullable(Long.class),nullable(Double.class),nullable(Double.class));
        return provider;
    }
    private PipelineTemperatureCalculator.Config thermalConfig(String edge) {
        return withPvt(PipelineTemperatureTests.config(edge),10L);
    }
    private Input flowMode(Input input,String mode) {
        var raw=(tools.jackson.databind.node.ObjectNode)productionMapper().valueToTree(input);
        raw.put("thermalMode",mode);return productionMapper().treeToValue(raw,Input.class);
    }
    private PipelineTemperature.Detail saveThermal(int revision,PipelineTemperatureCalculator.Config... configs) {
        return tx.execute(t->storedTemperature.save(new PipelineTemperature.Request(6,4,"测试井",revision,1,
                new PipelineTemperature.Settings(List.of(configs),.001,40))));
    }
    private PipelineTemperatureCalculator.Config withPvt(PipelineTemperatureCalculator.Config config,Long pvtId) {
        var raw=(tools.jackson.databind.node.ObjectNode)productionMapper().valueToTree(config);
        if(pvtId==null)raw.putNull("pvtId");else raw.put("pvtId",pvtId);
        return productionMapper().treeToValue(raw,PipelineTemperatureCalculator.Config.class);
    }
    private PipelineTemperatureCalculator.Config submittedInner(Long pvtId) {
        // PVT must override submitted density/Cp/viscosity; conductivity remains the pipe's explicit input.
        return new PipelineTemperatureCalculator.Config("pipe",null,null,null,null,null,
                25.0,.2,.015,1800.0,.04,pvtId,6.0,45.0,"伪造手填物性",List.of(),75.0);
    }
    @Test void selectedPvtOverridesAutomaticPropertiesButPreservesTheSuppliedConductivity() {
        configureGas();saveGraph(0,flowGraph(1000));
        var service=temperatureService();var config=submittedInner(10L);
        var actual=service.calculate("inner",new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(config))).getFirst();
        assertNull(actual.error());assertNotNull(actual.result());assertNotNull(actual.pvtProperties());
        var expected=new PipelineGasProperties().calculate("PR",gasModel.snapshot(6,4,"测试井").composition(),6,45);
        double re=4*.2*expected.densityKgM3()/(Math.PI*.075*.000012),pr=.000012*expected.cpJkgK()/.04;
        assertEquals(re,actual.result().reynolds(),1e-8);assertEquals(pr,actual.result().prandtl(),1e-12);
        assertEquals(.021*Math.pow(re,.8)*Math.pow(pr,.43)*.04/.075,actual.result().alphaInside(),1e-8);
        assertEquals(expected.densityKgM3(),actual.usedInput().densityKgM3());
        assertEquals(expected.cpJkgK(),actual.usedInput().cpJkgK());assertEquals(.012,actual.usedInput().viscosityMpaS());
        assertEquals(.04,actual.usedInput().gasConductivityWmK());assertNull(actual.pvtProperties().gasConductivityWmK());assertEquals(10L,actual.pvtProperties().pvtId());
        assertEquals(actual.pvtProperties().pvtName(),actual.usedInput().propertySource());
        assertEquals(config.propertyPressureMpa(),actual.pvtProperties().pressureMpa());
        assertEquals(config.propertyTemperatureC(),actual.pvtProperties().temperatureC());
        assertFalse(actual.pvtProperties().sourceRevision().isBlank());assertEquals(3,actual.pvtProperties().sources().size());
        assertEquals(config.actualFlowM3s(),actual.usedInput().actualFlowM3s());assertEquals(75.0,actual.effectiveDiameterMm());
        // A supplied trial diameter remains independent of topology draft geometry.
        var graph=topology.detail(6,4,"测试井").graph();var edge=graph.edges().getFirst();
        var parameters=new HashMap<String,Object>(edge.parameters());parameters.remove("diameterMm");
        saveGraph(1,new PipelineTopology.Graph(graph.nodes(),List.of(new PipelineTopology.Edge(edge.id(),edge.source(),
                edge.target(),edge.name(),parameters)),graph.settings(),graph.layout()));
        assertEquals(actual,service.calculate("inner",new PipelineTemperature.CalculateRequest(6,4,"测试井",2,List.of(config))).getFirst());
    }
    @Test void missingForeignDeletedOrInvalidPvtCannotFallBackToSubmittedThermalProperties() {
        configureGas();saveGraph(0,flowGraph(1000));var service=temperatureService();
        insertComposition(20,2);
        for(String kind:List.of("inner","overall")) {
            for(Long pvtId:Arrays.asList(null,20L,99L)) {
                var row=service.calculate(kind,new PipelineTemperature.CalculateRequest(6,4,"测试井",1,
                        List.of(withPvt(thermalConfig("pipe"),pvtId)))).getFirst();
                assertNull(row.result(),kind+":"+pvtId);assertNotNull(row.error(),kind+":"+pvtId);assertNull(row.usedInput());
            }
        }
        jdbc.update("UPDATE pipeline_pvt_model SET composition_json=? WHERE id=10",productionMapper().writeValueAsString(List.of(new PipelineGasProperties.Fraction("CH4",.8))));
        var request=new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(thermalConfig("pipe")));
        for(String kind:List.of("inner","overall")) {
            var row=service.calculate(kind,request).getFirst();assertNull(row.result());assertTrue(row.error().contains("100%"));
        }
        jdbc.update("DELETE FROM pipeline_pvt_model WHERE id=10");
        for(String kind:List.of("inner","overall")) {
            var row=service.calculate(kind,request).getFirst();assertNull(row.result());assertTrue(row.error().contains("已删除"));
        }
    }
    @Test void realPvtProviderUsesSuppliedConductivityAndNeverReadsLegacyTransportCurves() {
        configureGas();saveGraph(0,flowGraph(1000));
        jdbc.update("INSERT INTO project_well_pvt_gas_result VALUES(10,6,45,999,999)"); // Deliberately unusable colliding legacy transport data.
        var provider=new PipelinePvtThermalProperties(new PipelinePvtComposition(jdbc,new PipelineWellContext(jdbc)),new PipelineGasProperties());
        var service=temperatureService(provider);
        for(String kind:List.of("inner","overall")) {
            var config=PipelineTemperatureTests.withConductivity(thermalConfig("pipe"),.041);
            var row=service.calculate(kind,new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(config))).getFirst();
            assertNull(row.error());assertNotNull(row.result());assertNotNull(row.usedInput());
            assertNotNull(row.pvtProperties().densityKgM3());assertNotNull(row.pvtProperties().cpJkgK());
            assertTrue(row.pvtProperties().viscosityMpaS()>0);assertNotEquals(999,row.pvtProperties().viscosityMpaS());
            assertEquals(row.pvtProperties().viscosityMpaS(),row.usedInput().viscosityMpaS());
            assertEquals(.041,row.usedInput().gasConductivityWmK());assertNull(row.pvtProperties().gasConductivityWmK());
        }
        assertNull(service.detail(6,4,"测试井"));
    }
    @Test void missingOrInvalidConductivityCannotBeSuppliedByPvtOrHistoricalResults() {
        configureGas();saveGraph(0,flowGraph(1000));var service=temperatureService();
        for(Double conductivity:Arrays.asList(null,0.0,-.01,Double.NaN,Double.POSITIVE_INFINITY)) {
            var config=PipelineTemperatureTests.withConductivity(thermalConfig("pipe"),conductivity);
            for(String kind:List.of("inner","overall")) {
                var row=service.calculate(kind,new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(config))).getFirst();
                assertNull(row.result());assertNull(row.usedInput());assertTrue(row.error().contains("气体导热系数 λ"));
            }
            var solve=new PipelineTemperature.Solve(new PipelineTemperature.Request(6,4,"测试井",0,1,
                    new PipelineTemperature.Settings(List.of(config),.001,30)),example());
            assertTrue(assertThrows(BusinessException.class,()->service.solve(solve)).getMessage().contains("气体导热系数 λ"));
        }
        var missing=PipelineTemperatureTests.withConductivity(thermalConfig("pipe"),null);
        var saved=tx.execute(t->service.save(new PipelineTemperature.Request(6,4,"测试井",0,1,
                new PipelineTemperature.Settings(List.of(missing),.001,30))));
        assertNull(saved.settings().segments().getFirst().gasConductivityWmK());
        assertEquals(saved,service.detail(6,4,"测试井"));
        for(String kind:List.of("wall","outer"))assertNotNull(service.calculate(kind,
                new PipelineTemperature.CalculateRequest(6,4,"测试井",1,saved.settings().segments())).getFirst().result());
    }
    @Test void deletedPvtDoesNotBlockSavingMaterialAndEnvironmentEditsButStillBlocksFluidCalculations() {
        configureGas();saveGraph(0,flowGraph(1000));
        var service=temperatureService(new PipelinePvtThermalProperties(new PipelinePvtComposition(jdbc,new PipelineWellContext(jdbc)),new PipelineGasProperties()));
        var original=thermalConfig("pipe");
        var saved=tx.execute(t->service.save(new PipelineTemperature.Request(6,4,"测试井",0,1,
                new PipelineTemperature.Settings(List.of(original),.001,30))));
        var graphBefore=topology.detail(6,4,"测试井");
        jdbc.update("DELETE FROM pipeline_pvt_model WHERE id=10");
        var raw=(tools.jackson.databind.node.ObjectNode)productionMapper().valueToTree(saved.settings().segments().getFirst());
        raw.put("ambientC",24).put("burialDepthM",2).put("soilConductivityWmK",1.8);
        var layers=List.of(new PipelineTemperatureCalculator.Layer("更换钢管",8.0,40.0),
                new PipelineTemperatureCalculator.Layer("更换保温层",30.0,.045));
        raw.set("layers",productionMapper().valueToTree(layers));
        var edited=productionMapper().treeToValue(raw,PipelineTemperatureCalculator.Config.class);
        var request=new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(edited));
        var historyRows=new HashMap<String,List<PipelineTemperature.SavedRow>>();
        for(String kind:List.of("wall","outer")) {
            var row=service.calculate(kind,request).getFirst();
            assertNull(row.error(),kind);assertNotNull(row.result(),kind);assertNull(row.pvtProperties(),kind);
            assertEquals(new PipelineTemperatureCalculator().calculate(kind,100,edited),row.result());
            assertNotEquals(new PipelineTemperatureCalculator().calculate(kind,100,original),row.result());
            historyRows.put(kind,List.of(new PipelineTemperature.SavedRow(row,"edited-"+kind+"-stamp",null,false)));
        }
        var history=new PipelineTemperature.SavedResults(new PipelineTemperature.ResultScope(6,4,"测试井"),1,
                historyRows,List.of(),null,"","coefficient");
        var update=new PipelineTemperature.Request(6,4,"测试井",saved.revision(),1,
                new PipelineTemperature.Settings(List.of(edited),.001,30,history));
        var updated=tx.execute(t->service.save(update));
        assertEquals(saved.revision()+1,updated.revision());assertEquals(updated,service.detail(6,4,"测试井"));
        assertEquals(history,updated.settings().savedResults());
        var reopened=updated.settings().segments().getFirst();
        assertEquals(10L,reopened.pvtId());assertEquals(layers,reopened.layers());
        assertEquals(24.0,reopened.ambientC());assertEquals(2.0,reopened.burialDepthM());assertEquals(1.8,reopened.soilConductivityWmK());
        assertEquals(graphBefore,topology.detail(6,4,"测试井"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_pvt_model WHERE id=10",Integer.class));
        for(String kind:List.of("inner","overall")) {
            var rejected=service.calculate(kind,new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(reopened))).getFirst();
            assertNull(rejected.result());assertNull(rejected.usedInput());assertTrue(rejected.error().contains("已删除"));
        }
        assertTrue(assertThrows(BusinessException.class,()->service.solve(new PipelineTemperature.Solve(
                new PipelineTemperature.Request(6,4,"测试井",updated.revision(),1,updated.settings()),
                example()))).getMessage().contains("已删除"));
    }
    @Test void selectedPvtStillRequiresPressureTemperatureFlowAndValidDiameter() {
        configureGas();saveGraph(0,flowGraph(1000));var service=temperatureService();var mapper=productionMapper();
        for(String field:List.of("propertyPressureMpa","propertyTemperatureC","actualFlowM3s")) {
            var raw=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(submittedInner(10L));raw.putNull(field);
            var config=mapper.treeToValue(raw,PipelineTemperatureCalculator.Config.class);
            var row=service.calculate("inner",new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(config))).getFirst();
            assertNull(row.result(),field);assertNotNull(row.error(),field);
        }
        var raw=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(submittedInner(10L));raw.put("innerDiameterMm",0);
        var config=mapper.treeToValue(raw,PipelineTemperatureCalculator.Config.class);
        var row=service.calculate("inner",new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(config))).getFirst();
        assertEquals("请填写有效的内径",row.error());assertNull(row.result());
        var graph=topology.detail(6,4,"测试井").graph();var edge=graph.edges().getFirst();
        var parameters=new HashMap<String,Object>(edge.parameters());parameters.remove("diameterMm");
        saveGraph(1,new PipelineTopology.Graph(graph.nodes(),List.of(new PipelineTopology.Edge(edge.id(),edge.source(),
                edge.target(),edge.name(),parameters)),graph.settings(),graph.layout()));
        raw.putNull("innerDiameterMm");var noDiameter=mapper.treeToValue(raw,PipelineTemperatureCalculator.Config.class);
        var missing=service.calculate("inner",new PipelineTemperature.CalculateRequest(6,4,"测试井",2,List.of(noDiameter))).getFirst();
        assertNull(missing.result());assertTrue(missing.error().contains("diameterMm"));
    }
    @Test void thermalSaveKeepsConductivityAndPvtConditionsButRemovesAutomaticPropertyCopies() {
        configureGas();saveGraph(0,flowGraph(1000));
        var graph=topology.detail(6,4,"测试井");var model=gasModel.detail(6,4,"测试井");
        var service=temperatureService();var config=submittedInner(10L);
        var row=service.calculate("inner",new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(config))).getFirst();
        var history=new PipelineTemperature.SavedResults(new PipelineTemperature.ResultScope(6,4,"测试井"),1,
                Map.of("inner",List.of(new PipelineTemperature.SavedRow(row,"original-pvt-source-stamp",null,false))),List.of(),null,"","coefficient");
        var settings=new PipelineTemperature.Settings(List.of(config),.001,30,history);
        var saved=tx.execute(t->service.save(new PipelineTemperature.Request(6,4,"测试井",0,1,settings)));
        assertEquals(saved,service.detail(6,4,"测试井"));assertEquals(history,saved.settings().savedResults());
        var reloaded=saved.settings().segments().getFirst();
        assertEquals(10L,reloaded.pvtId());assertEquals(75.0,reloaded.innerDiameterMm());assertEquals(.2,reloaded.actualFlowM3s());
        assertEquals(6.0,reloaded.propertyPressureMpa());assertEquals(45.0,reloaded.propertyTemperatureC());
        assertNull(reloaded.densityKgM3());assertNull(reloaded.viscosityMpaS());assertNull(reloaded.cpJkgK());
        assertEquals(.04,reloaded.gasConductivityWmK());assertNull(reloaded.propertySource());
        var stored=productionMapper().readTree(jdbc.queryForObject("SELECT settings_json FROM pipeline_temperature WHERE well_id=1",String.class));
        for(String property:List.of("densityKgM3","viscosityMpaS","cpJkgK","propertySource"))
            assertTrue(stored.path("segments").get(0).path(property).isMissingNode()||stored.path("segments").get(0).path(property).isNull(),property);
        assertEquals(.04,stored.path("segments").get(0).path("gasConductivityWmK").asDouble());
        assertEquals(graph,topology.detail(6,4,"测试井"));assertEquals(model,gasModel.detail(6,4,"测试井"));
        assertEquals(row,service.calculate("inner",new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(reloaded))).getFirst());
        // Stored settings predating the trial diameter still resolve geometry from the topology.
        var legacy=(tools.jackson.databind.node.ObjectNode)productionMapper().valueToTree(reloaded);legacy.remove("innerDiameterMm");
        var old=productionMapper().treeToValue(legacy,PipelineTemperatureCalculator.Config.class);assertNull(old.innerDiameterMm());
        var oldResult=service.calculate("inner",new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(old))).getFirst();
        assertNull(oldResult.error());assertEquals(row.result().reynolds()*.75,oldResult.result().reynolds(),1e-8);
        changeComposition(.95);
        assertEquals(history,service.detail(6,4,"测试井").settings().savedResults(),"Historical inputs and their source snapshot are immutable");
        var recalculated=service.calculate("inner",new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(reloaded))).getFirst();
        assertNotNull(recalculated.result());
        assertNotEquals(row.pvtProperties().sourceRevision(),recalculated.pvtProperties().sourceRevision());
        assertNotEquals(row.result(),recalculated.result());
    }
    @Test void changingSavedConductivityChangesOnlyCurrentFluidHeatTransferAndPreservesItsHistoricalInput() {
        configureGas();saveGraph(0,flowGraph(1000));var service=temperatureService();
        var original=PipelineTemperatureTests.withConductivity(thermalConfig("pipe"),.021);
        var request=new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(original));
        var first=service.calculate("inner",request).getFirst();
        var history=new PipelineTemperature.SavedResults(new PipelineTemperature.ResultScope(6,4,"测试井"),1,
                Map.of("inner",List.of(new PipelineTemperature.SavedRow(first,"lambda-0.021",null,false))),List.of(),null,"","coefficient");
        var saved=tx.execute(t->service.save(new PipelineTemperature.Request(6,4,"测试井",0,1,
                new PipelineTemperature.Settings(List.of(original),.001,30,history))));
        var changed=PipelineTemperatureTests.withConductivity(saved.settings().segments().getFirst(),.063);
        var updated=tx.execute(t->service.save(new PipelineTemperature.Request(6,4,"测试井",saved.revision(),1,
                new PipelineTemperature.Settings(List.of(changed),.001,30,history))));
        assertEquals(.063,updated.settings().segments().getFirst().gasConductivityWmK());
        assertEquals(history,updated.settings().savedResults());
        assertEquals(.021,updated.settings().savedResults().coefficients().get("inner").getFirst().row().usedInput().gasConductivityWmK());
        var nextRequest=new PipelineTemperature.CalculateRequest(6,4,"测试井",1,updated.settings().segments());
        var second=service.calculate("inner",nextRequest).getFirst();
        assertNull(second.error());assertEquals(.063,second.usedInput().gasConductivityWmK());
        assertEquals(first.pvtProperties(),second.pvtProperties(),"Manual conductivity must not alter the PVT projection or its source revision");
        assertEquals(first.result().alphaInside()*Math.pow(3,.57),second.result().alphaInside(),1e-8);
        assertNotEquals(service.calculate("overall",request).getFirst().result(),service.calculate("overall",nextRequest).getFirst().result());
        for(String kind:List.of("wall","outer"))assertEquals(service.calculate(kind,request).getFirst().result(),service.calculate(kind,nextRequest).getFirst().result());
    }
    @Test void coupledSolveKeepsEachPipesSuppliedConductivityWhileReevaluatingAutomaticPropertiesAtMeanConditions() {
        configureGas();
        var original=flowGraph(1000);var inlet=original.nodes().getFirst();var outlet=original.nodes().getLast();
        var middle=new PipelineTopology.Node("middle","junction","中间节点",Map.of("elevationM",0),200,0);
        var parameters=original.edges().getFirst().parameters();
        var edges=List.of(new PipelineTopology.Edge("first","in","middle","第一管段",parameters),
                new PipelineTopology.Edge("second","middle","out","第二管段",parameters));
        saveGraph(0,new PipelineTopology.Graph(List.of(inlet,middle,outlet),edges,Map.of(),Map.of()));
        var configs=List.of(PipelineTemperatureTests.withConductivity(thermalConfig("first"),.017),
                PipelineTemperatureTests.withConductivity(thermalConfig("second"),.053));
        var provider=new PipelinePvtThermalProperties(new PipelinePvtComposition(jdbc,new PipelineWellContext(jdbc)),new PipelineGasProperties());
        var service=temperatureService(provider);
        var solution=service.solve(new PipelineTemperature.Solve(new PipelineTemperature.Request(6,4,"测试井",0,1,
                new PipelineTemperature.Settings(configs,.001,40)),example()));
        assertTrue(solution.iterations()>1);assertEquals(2,solution.coefficients().size());
        for(int i=0;i<configs.size();i++) {
            var row=solution.coefficients().get(i);var used=row.usedInput();
            assertEquals(configs.get(i).gasConductivityWmK(),used.gasConductivityWmK());
            assertNull(row.pvtProperties().gasConductivityWmK());
            var expected=provider.detail(6,4,"测试井",10L,used.propertyPressureMpa(),used.propertyTemperatureC());
            assertEquals(expected.densityKgM3(),used.densityKgM3());assertEquals(expected.cpJkgK(),used.cpJkgK());
            assertEquals(expected.viscosityMpaS(),used.viscosityMpaS());
            assertEquals(new PipelineTemperatureCalculator().calculate(100,used),row.result());
            assertEquals(row.result().innerAreaU(),solution.input().segments().get(i).heatTransferWm2K());
        }
        var first=solution.coefficients().getFirst().usedInput();var last=solution.coefficients().getLast().usedInput();
        assertNotEquals(first.propertyPressureMpa(),last.propertyPressureMpa());
        assertNotEquals(first.propertyTemperatureC(),last.propertyTemperatureC());
        assertNotEquals(first.viscosityMpaS(),last.viscosityMpaS(),"Each pipe's transport properties must be evaluated at its own mean state");
        assertNotEquals(configs.getFirst().propertyTemperatureC(),first.propertyTemperatureC());
    }
    @Test void throughputThermalSolveDoesNotTreatItsInitialGuessAsAnActualLaminarFlow() {
        configureGas();
        var original=flowGraph(10000);
        var edges=original.edges().stream().map(e->{var params=new HashMap<String,Object>(e.parameters());params.put("diameterMm",1000);
            return new PipelineTopology.Edge(e.id(),e.source(),e.target(),e.name(),params);}).toList();
        saveGraph(0,new PipelineTopology.Graph(original.nodes(),edges,original.settings(),original.layout()));
        var c=thermalConfig(edges.getFirst().id());
        var b=example();
        var input=new Input("rate","heat",b.frictionMethod(),6.0,5.9,null,45.0,b.gasGravity(),b.z(),b.viscosityMpaS(),b.cpJkgK(),b.jtKmpa(),b.standardPressurePa(),b.standardTemperatureK(),b.standardZ(),List.of(),List.of(),b.constraints());
        var settings=new PipelineTemperature.Settings(List.of(c),.001,50);
        var service=new PipelineTemperature(jdbc,productionMapper(),new PipelineWellContext(jdbc),topology,new PipelineTemperatureCalculator(),new PipelineCalculator(),gasModel,new PipelineGasProperties(),completeTestPvtProperties());
        var solution=service.solve(new PipelineTemperature.Solve(new PipelineTemperature.Request(6,4,"测试井",0,1,settings),input));
        assertTrue(solution.result().rate10k()>10);
        assertTrue(solution.coefficients().getFirst().result().reynolds()>10000);
        assertEquals(5.9,solution.result().outletMpa(),1e-6);
    }
    @Test void clearedPageDefaultsRemainExplicitNullWithProductionNonNullSerialization() {
        saveGraph(0,flowGraph(4000));
        var values=new HashMap<String,Object>();
        values.put("standardPressurePa",null);values.put("standardTemperatureK",null);values.put("standardZ",null);
        values.put("boundary",nodeBoundary());
        tx.executeWithoutResult(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"测试井",0,draft(values))));
        var detail=storage.detail(6,4,"测试井");var mapper=productionMapper();
        var api=mapper.readTree(mapper.writeValueAsString(detail));
        var stored=mapper.readTree(jdbc.queryForObject("SELECT input_json FROM pipeline_model WHERE well_id=1",String.class));
        for(var field:List.of("standardPressurePa","standardTemperatureK","standardZ","gasGravity","jtKmpa")) {
            assertTrue(api.path("input").has(field),"API must retain "+field);
            assertTrue(api.path("input").path(field).isNull());
            assertTrue(stored.has(field),"Stored input must retain "+field);assertTrue(stored.path(field).isNull());
        }
        assertEquals("unknown",api.path("input").path("constraints").path("waterState").asText());
        assertEquals("unknown",stored.path("constraints").path("waterState").asText());
        assertEquals(detail,mapper.readValue(mapper.writeValueAsString(detail),Detail.class));
    }
    private static JsonMapper productionMapper() {
        return JsonMapper.builder().changeDefaultPropertyInclusion(value->value.withValueInclusion(JsonInclude.Include.NON_NULL)).build();
    }
    @Test void sectionSavesRejectInvalidOwnFieldsAndKeepConcurrentAndWellIsolationGuards() {
        var input=draft(Map.of("boundary",nodeBoundary()));
        assertThrows(BusinessException.class,()->tx.execute(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"测试井",0,PipelineModelSections.empty()))));
        saveGraph(0,flowGraph(4000));
        assertThrows(BusinessException.class,()->tx.execute(t->storage.saveSection("unknown",new SectionSaveRequest(6,4,"测试井",0,input))));
        assertTrue(assertThrows(BusinessException.class,()->tx.execute(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"测试井",0,
                draft(Map.of("boundary",nodeBoundary(),"standardPressurePa",-1.0)))))).getMessage().contains("标况压力"));
        tx.executeWithoutResult(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"测试井",0,input)));
        assertEquals(409,assertThrows(BusinessException.class,()->tx.execute(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"测试井",0,input)))).getCode());
        assertEquals(409,assertThrows(BusinessException.class,()->tx.execute(t->storage.saveSection("boundary",new SectionSaveRequest(7,4,"测试井",1,input)))).getCode());
        assertNull(storage.detail(7,4,"测试井"));
        tx.executeWithoutResult(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"测试井",1,input)));
        assertEquals(409,assertThrows(BusinessException.class,()->tx.execute(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"测试井",1,input)))).getCode());
        assertThrows(BusinessException.class,()->tx.execute(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"不存在的井",0,input))));
        assertEquals(2,storage.detail(6,4,"测试井").revision());
    }
    private Boundary nodeBoundary() {
        return single(1,"2026-09-08T14:00",List.of(new BoundaryNode("in",null,null,null,null),
                new BoundaryNode("out",null,null,null,null)));
    }
    private Input nodeInput(Boundary boundary) {
        var mapper=productionMapper();var data=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(example());
        data.set("boundary",mapper.valueToTree(boundary));
        for(String scalar:List.of("target","inletMpa","outletMpa","rate10k","inletC"))data.putNull(scalar);
        return mapper.treeToValue(data,Input.class);
    }
    @Test void boundarySavesRequireRealUniqueTimesAndRetainArbitraryMinutesInCanonicalJson() {
        configureGas();saveGraph(0,flowGraph(1000));
        var nodes=List.of(new BoundaryNode("in",8.0,null,8.0,45.0),new BoundaryNode("out",null,8.0,null,null));
        var boundary=new Boundary(1,"first",List.of(new BoundaryCase("first","2026/09/08 14:37",nodes),
                new BoundaryCase("second","2026-09-08T14:38:00",nodes)));
        var saved=tx.execute(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"测试井",0,nodeInput(boundary))));
        assertEquals(List.of("2026-09-08T14:37","2026-09-08T14:38"),saved.input().boundary().cases().stream().map(BoundaryCase::operatingAt).toList());
        assertEquals(nodes,saved.input().boundary().cases().getFirst().nodes());
        for(String invalid:java.util.Arrays.asList(null,"","2026/02/29 14:37","2026/09/08 24:00","2026/09/08 14:60")) {
            var draft=single(1,invalid,nodes);
            assertThrows(BusinessException.class,()->tx.execute(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"测试井",1,nodeInput(draft)))));
        }
        var duplicate=new Boundary(1,"first",List.of(boundary.cases().getFirst(),new BoundaryCase("second","2026-09-08T14:37:00",nodes)));
        assertThrows(BusinessException.class,()->tx.execute(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"测试井",1,nodeInput(duplicate)))));
        assertEquals(saved,storage.detail(6,4,"测试井"));

    }
    @Test void nodeBoundarySaveIsIndependentOfStaleEosAndProtectsWellAndTopologyIdentity() {
        configureGas();saveGraph(0,flowGraph(1000));
        jdbc.update("DELETE FROM pipeline_pvt_model WHERE id=10");
        assertTrue(gasModel.detail(6,4,"测试井").compositionChanged());
        var input=draft(Map.of("boundary",nodeBoundary()));
        var saved=tx.execute(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"测试井",0,input)));
        assertEquals(nodeBoundary(),saved.input().boundary());assertNull(saved.input().gasGravity());
        assertThrows(BusinessException.class,()->tx.execute(t->storage.saveSection("boundary",new SectionSaveRequest(7,4,"测试井",0,input))));
        var foreign=single(1,null,List.of(new BoundaryNode("other-well-node",null,null,null,null)));
        assertThrows(BusinessException.class,()->tx.execute(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"测试井",1,draft(Map.of("boundary",foreign))))));
        saveGraph(1,flowGraph(2000));
        assertEquals(409,assertThrows(BusinessException.class,()->tx.execute(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"测试井",1,input)))).getCode());
        assertEquals(saved,storage.detail(6,4,"测试井"));
    }
    private Input draft(Map<String,Object> changes) {
        var mapper=JsonMapper.builder().build();
        @SuppressWarnings("unchecked") var values=(Map<String,Object>)mapper.convertValue(PipelineModelSections.empty(),Map.class);
        values.putAll(changes);return mapper.convertValue(values,Input.class);
    }
    private void saveGraph(int revision,PipelineTopology.Graph graph) {
        tx.executeWithoutResult(t->topology.save(new PipelineTopology.Save(6,4,"测试井",revision,graph)));
    }
    private PipelineTopology.Graph flowGraph(double length) {
        var nodes=List.of(new PipelineTopology.Node("in","well","测试井",Map.of("elevationM",0),0,0),new PipelineTopology.Node("out","station","出口",Map.of("elevationM",0),400,0));
        var edge=new PipelineTopology.Edge("pipe","in","out","水平管",Map.of("lengthM",length,"diameterMm",100,"roughnessMm",.03,"ambientC",15,"heatTransferWm2K",2));
        return new PipelineTopology.Graph(nodes,List.of(edge),Map.of(),Map.of());
    }
    private Input withArrays(Input i,List<Segment> segments,List<Equipment> equipment) {
        return new Input(i.target(),i.thermalMode(),i.frictionMethod(),i.inletMpa(),i.outletMpa(),i.rate10k(),i.inletC(),i.gasGravity(),i.z(),i.viscosityMpaS(),i.cpJkgK(),i.jtKmpa(),i.standardPressurePa(),i.standardTemperatureK(),i.standardZ(),segments,equipment,i.constraints(),i.gasModel(),i.boundary(),i.thermalModel());
    }
    @Test void uniqueTopologyRoundTripAndLockedWellValidation() {
        var topology=new PipelineTopology(jdbc,JsonMapper.builder().build(),new PipelineWellContext(jdbc));
        var nodes=java.util.List.of(
            new PipelineTopology.Node("in","well","测试井",java.util.Map.of("elevationM",12),100,200),
            new PipelineTopology.Node("out","station","出口",java.util.Map.of("elevationM",32),400,200));
        var edge=new PipelineTopology.Edge("pipe","in","out","管段1",java.util.Map.of("lengthM",1000));
        var graph=new PipelineTopology.Graph(nodes,java.util.List.of(edge),java.util.Map.of("inletMpa",6),java.util.Map.of("zoom",1));
        assertNull(topology.detail(6,4,"测试井"));
        var saved=tx.execute(s->topology.save(new PipelineTopology.Save(6,4,"测试井",0,graph)));
        assertEquals(graph,topology.detail(6,4,"测试井").graph());
        assertNull(topology.detail(7,4,"测试井"));
        assertThrows(BusinessException.class,()->tx.execute(s->topology.save(new PipelineTopology.Save(6,4,"测试井",0,graph))));
        var changed=tx.execute(s->topology.save(new PipelineTopology.Save(6,4,"测试井",1,graph)));
        assertEquals(2,changed.revision());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_topology",Integer.class));
        assertThrows(BusinessException.class,()->tx.execute(s->topology.save(new PipelineTopology.Save(6,4,"测试井",1,graph))));
        var wrongWell=new PipelineTopology.Graph(nodes,java.util.List.of(edge),graph.settings(),graph.layout());
        assertThrows(BusinessException.class,()->tx.execute(s->topology.save(new PipelineTopology.Save(6,4,"另一口井",0,wrongWell))));
        var removedWell=new PipelineTopology.Graph(java.util.List.of(nodes.get(1)),java.util.List.of(),graph.settings(),graph.layout());
        assertThrows(BusinessException.class,()->tx.execute(s->topology.save(new PipelineTopology.Save(6,4,"测试井",2,removedWell))));
        var twoWells=new PipelineTopology.Graph(java.util.List.of(nodes.get(0),new PipelineTopology.Node("other","well","其他井",java.util.Map.of(),1,1)),java.util.List.of(),graph.settings(),graph.layout());
        assertThrows(BusinessException.class,()->tx.execute(s->topology.save(new PipelineTopology.Save(6,4,"测试井",2,twoWells))));
        var bad=new PipelineTopology.Graph(nodes,java.util.List.of(new PipelineTopology.Edge("bad","in","missing","错误连接",java.util.Map.of())),graph.settings(),graph.layout());
        assertThrows(BusinessException.class,()->tx.execute(s->topology.save(new PipelineTopology.Save(6,4,"测试井",2,bad))));
        assertEquals(2,topology.detail(6,4,"测试井").revision());
    }
    @Test void temperatureSaveReloadCalculationAndTopologyRevisionGuard() {
        configureGas();
        var mapper=JsonMapper.builder().build();var wells=new PipelineWellContext(jdbc);
        var topology=new PipelineTopology(jdbc,mapper,wells);
        var a=new PipelineTopology.Node("in","well","测试井",java.util.Map.of("elevationM",0),0,0);
        var b=new PipelineTopology.Node("out","station","出口",java.util.Map.of("elevationM",0),400,0);
        var e=new PipelineTopology.Edge("pipe","in","out","管段",java.util.Map.of("lengthM",1000,"diameterMm",100,"roughnessMm",.03));
        var g=new PipelineTopology.Graph(java.util.List.of(a,b),java.util.List.of(e),java.util.Map.of(),java.util.Map.of());
        tx.executeWithoutResult(t->topology.save(new PipelineTopology.Save(6,4,"测试井",0,g)));
        var service=new PipelineTemperature(jdbc,mapper,wells,topology,new PipelineTemperatureCalculator(),new PipelineCalculator(),gasModel,new PipelineGasProperties(),completeTestPvtProperties());
        var settings=new PipelineTemperature.Settings(java.util.List.of(thermalConfig("pipe")),.001,30);
        var request=new PipelineTemperature.Request(6,4,"测试井",0,1,settings);
        assertNull(service.detail(6,4,"测试井"));
        var saved=tx.execute(t->service.save(request));
        assertEquals(saved,service.detail(6,4,"测试井"));
        assertEquals(settings.segments().getFirst().layers(),saved.settings().segments().getFirst().layers());
        assertEquals(10L,saved.settings().segments().getFirst().pvtId());
        assertNull(service.detail(7,4,"测试井"));
        assertThrows(BusinessException.class,()->tx.executeWithoutResult(t->service.save(request)));
        var calculation=new PipelineTemperature.CalculateRequest(6,4,"测试井",1,settings.segments());
        assertNotNull(service.calculate("overall",calculation).getFirst().result());
        var solution=service.solve(new PipelineTemperature.Solve(request,example()));
        assertTrue(solution.iterations()>=2);assertTrue(solution.result().outletC()<45);assertTrue(solution.input().segments().getFirst().heatTransferWm2K()>0);
        tx.executeWithoutResult(t->topology.save(new PipelineTopology.Save(6,4,"测试井",1,g)));
        assertThrows(BusinessException.class,()->service.calculate("overall",calculation));
    }
    @Test void temperatureSavedResultsRoundTripWithoutRecalculatingOrChangingTheirInputStamps() {
        saveGraph(0,flowGraph(1000));var service=temperatureService();
        var config=PipelineTemperatureTests.config("pipe");
        var row=service.calculate("wall",new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(config))).getFirst();
        assertNotNull(row.usedInput());assertNotNull(row.effectiveDiameterMm());
        var history=new PipelineTemperature.SavedResults(new PipelineTemperature.ResultScope(6,4,"测试井"),1,
                Map.of("wall",List.of(new PipelineTemperature.SavedRow(row,"original-wall-input-stamp",null,false))),List.of(),null,"","coefficient");
        var parameters=new PipelineTemperature.Settings(List.of(config),.001,30,history);
        var saved=tx.execute(t->service.save(new PipelineTemperature.Request(6,4,"测试井",0,1,parameters)));
        var reopened=service.detail(6,4,"测试井");
        assertEquals(saved,reopened);assertEquals(history,reopened.settings().savedResults());
        assertEquals("original-wall-input-stamp",reopened.settings().savedResults().coefficients().get("wall").getFirst().stamp());
        assertEquals(row.result(),reopened.settings().savedResults().coefficients().get("wall").getFirst().row().result());
        var legacy=productionMapper().readValue("{\"segments\":[],\"tolerance\":0.001,\"maxIterations\":30}",PipelineTemperature.Settings.class);
        assertNull(legacy.savedResults());assertEquals(new PipelineTemperature.Settings(List.of(),.001,30),legacy);
    }
    @Test void temperatureResultSnapshotsRejectOtherWellsTopologiesAndUnknownOrRepeatedPipes() {
        saveGraph(0,flowGraph(1000));var service=temperatureService();var config=PipelineTemperatureTests.config("pipe");
        var row=service.calculate("wall",new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(config))).getFirst();
        var savedRow=new PipelineTemperature.SavedRow(row,"stamp",null,false);
        var scope=new PipelineTemperature.ResultScope(6,4,"测试井");
        var foreignRow=new PipelineTemperature.Row("another-pipe",row.name(),row.result(),null);
        for(var history:List.of(
                new PipelineTemperature.SavedResults(new PipelineTemperature.ResultScope(7,4,"测试井"),1,Map.of("wall",List.of(savedRow)),List.of(),null,"","coefficient"),
                new PipelineTemperature.SavedResults(scope,2,Map.of("wall",List.of(savedRow)),List.of(),null,"","coefficient"),
                new PipelineTemperature.SavedResults(scope,1,Map.of("wall",List.of(savedRow,savedRow)),List.of(),null,"","coefficient"),
                new PipelineTemperature.SavedResults(scope,1,Map.of("wall",List.of(new PipelineTemperature.SavedRow(foreignRow,"stamp",null,false))),List.of(),null,"","coefficient"))) {
            assertThrows(BusinessException.class,()->service.save(new PipelineTemperature.Request(6,4,"测试井",0,1,new PipelineTemperature.Settings(List.of(config),.001,30,history))));
        }
        assertNull(service.detail(6,4,"测试井"));
    }
    @Test void overallTemperatureRowsExposeTheActualEosPropertiesAndConditionsUsed() {
        saveGraph(0,flowGraph(1000));configureGas();var config=thermalConfig("pipe");
        var row=temperatureService().calculate("overall",new PipelineTemperature.CalculateRequest(6,4,"测试井",1,List.of(config))).getFirst();
        assertNull(row.error());assertNotNull(row.result());assertNotNull(row.pvtProperties());
        var model=gasModel.snapshot(6,4,"测试井");
        var expected=new PipelineGasProperties().calculate(model.method(),model.composition(),config.propertyPressureMpa(),config.propertyTemperatureC());
        assertEquals(expected.densityKgM3(),row.pvtProperties().densityKgM3());
        assertEquals(expected.cpJkgK(),row.pvtProperties().cpJkgK());
        assertEquals(row.pvtProperties().densityKgM3(),row.usedInput().densityKgM3());
        assertEquals(row.pvtProperties().cpJkgK(),row.usedInput().cpJkgK());
        assertEquals(config.actualFlowM3s(),row.usedInput().actualFlowM3s());
        assertEquals(config.propertyPressureMpa(),row.usedInput().propertyPressureMpa());
        assertEquals(config.propertyTemperatureC(),row.usedInput().propertyTemperatureC());
        assertEquals(config.viscosityMpaS(),row.usedInput().viscosityMpaS());
        assertEquals(config.gasConductivityWmK(),row.usedInput().gasConductivityWmK());
        assertNotEquals(config.densityKgM3(),row.usedInput().densityKgM3());
    }
    @Test void materialLayerSaveSerializesOnlyThreeFieldsAndCannotReintroduceLegacySource() {
        configureGas();saveGraph(0,flowGraph(1000));
        var graphBefore=topology.detail(6,4,"测试井");
        var mapper=productionMapper();var service=temperatureService();
        var settings=new PipelineTemperature.Settings(List.of(thermalConfig("pipe")),.001,30);
        var fresh=new PipelineTemperature.Request(6,4,"测试井",0,1,settings);
        var saved=tx.execute(t->service.save(fresh));
        var stored=mapper.readTree(jdbc.queryForObject("SELECT settings_json FROM pipeline_temperature WHERE well_id=1",String.class));
        for(var layer:stored.path("segments").get(0).path("layers")) {
            assertEquals(3,layer.size());
            assertTrue(layer.has("name")&&layer.has("thicknessMm")&&layer.has("conductivityWmK"));
            assertFalse(layer.has("source"));
        }
        assertFalse(stored.path("segments").get(0).hasNonNull("propertySource"));

        // An older browser may still submit removed fields; they must not reach the saved JSON again.
        var legacy=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(
                new PipelineTemperature.Request(6,4,"测试井",saved.revision(),1,settings));
        for(var layer:legacy.path("settings").path("segments").get(0).path("layers"))
            ((tools.jackson.databind.node.ObjectNode)layer).put("source","旧客户端携带的材料来源");
        var submission=mapper.treeToValue(legacy,PipelineTemperature.Request.class);
        var updated=tx.execute(t->service.save(submission));
        assertEquals(saved.settings(),updated.settings());
        assertEquals(updated,service.detail(6,4,"测试井"));
        var rewritten=mapper.readTree(jdbc.queryForObject("SELECT settings_json FROM pipeline_temperature WHERE well_id=1",String.class));
        assertEquals(stored,rewritten);
        for(var layer:mapper.valueToTree(updated).path("settings").path("segments").get(0).path("layers"))
            assertFalse(layer.has("source"));
        var calculation=new PipelineTemperature.CalculateRequest(6,4,"测试井",1,updated.settings().segments());
        for(String kind:List.of("wall","overall")) {
            var result=service.calculate(kind,calculation).getFirst();
            assertNull(result.error(),kind);assertNotNull(result.result(),kind);
        }
        assertEquals(graphBefore,topology.detail(6,4,"测试井"),"Topology edge.source is unrelated to material layers");
    }
    @Test void independentTemperatureCalculationsDoNotValidateUnrelatedSettings() {
        configureGas();
        var mapper=JsonMapper.builder().build();var wells=new PipelineWellContext(jdbc);
        var topology=new PipelineTopology(jdbc,mapper,wells);
        var a=new PipelineTopology.Node("in","well","测试井",java.util.Map.of("elevationM",0),0,0);
        var b=new PipelineTopology.Node("out","station","出口",java.util.Map.of("elevationM",0),400,0);
        var e=new PipelineTopology.Edge("pipe","in","out","管段",java.util.Map.of("lengthM",1000,"diameterMm",100,"roughnessMm",.03));
        var g=new PipelineTopology.Graph(java.util.List.of(a,b),java.util.List.of(e),java.util.Map.of(),java.util.Map.of());
        tx.executeWithoutResult(t->topology.save(new PipelineTopology.Save(6,4,"测试井",0,g)));
        var service=new PipelineTemperature(jdbc,mapper,wells,topology,new PipelineTemperatureCalculator(),new PipelineCalculator(),gasModel,new PipelineGasProperties(),completeTestPvtProperties());
        for(var kind:java.util.List.of("inner","wall","outer")) {
            var c=switch(kind) {
                case "inner" -> submittedInner(10L);
                case "wall" -> PipelineTemperatureTests.wallOnly("pipe");
                default -> PipelineTemperatureTests.outerOnly("pipe");
            };
            var request=new PipelineTemperature.CalculateRequest(6,4,"测试井",1,java.util.List.of(c));
            var result=service.calculate(kind,request).getFirst();
            assertNull(result.error(),kind);assertNotNull(result.result(),kind);
        }
        var c=PipelineTemperatureTests.withAmbient(thermalConfig("pipe"),null);
        var request=new PipelineTemperature.CalculateRequest(6,4,"测试井",1,java.util.List.of(c));
        assertNotNull(service.calculate("overall",request).getFirst().result());
        assertThrows(BusinessException.class,()->service.calculate("unknown",request));
        var solve=new PipelineTemperature.Solve(new PipelineTemperature.Request(6,4,"测试井",0,1,new PipelineTemperature.Settings(request.segments(),.001,30)),example());
        assertTrue(assertThrows(BusinessException.class,()->service.solve(solve)).getMessage().contains("环境温度"));
        var duplicate=new PipelineTemperature.CalculateRequest(6,4,"测试井",1,java.util.List.of(c,c));
        assertThrows(BusinessException.class,()->service.calculate("inner",duplicate));
        var unknown=new PipelineTemperature.CalculateRequest(6,4,"测试井",1,java.util.List.of(PipelineTemperatureTests.innerOnly("missing")));
        assertThrows(BusinessException.class,()->service.calculate("inner",unknown));
        var incomplete=new PipelineTemperature.CalculateRequest(6,4,"测试井",1,java.util.List.of(submittedInner(10L)));
        assertTrue(service.calculate("overall",incomplete).getFirst().error().startsWith("管道导热系数："));

        insertComposition(20,2);
        var foreignPvt=new PipelineTemperatureCalculator.Config(c.edgeId(),c.externalMethod(),15.0,c.burialDepthM(),c.soilConductivityWmK(),c.surfaceCoefficientWm2K(),c.densityKgM3(),c.actualFlowM3s(),c.viscosityMpaS(),c.cpJkgK(),c.gasConductivityWmK(),20L,c.propertyPressureMpa(),c.propertyTemperatureC(),c.propertySource(),c.layers());
        var foreignRequest=new PipelineTemperature.CalculateRequest(6,4,"测试井",1,java.util.List.of(foreignPvt));
        assertNotNull(service.calculate("wall",foreignRequest).getFirst().result());
        assertNotNull(service.calculate("outer",foreignRequest).getFirst().result());
        for(String kind:List.of("inner","overall")) {
            var rejected=service.calculate(kind,foreignRequest).getFirst();
            assertNull(rejected.result());assertTrue(rejected.error().contains("不属于当前井"));
        }
        var saveForeign=new PipelineTemperature.Request(6,4,"测试井",0,1,new PipelineTemperature.Settings(foreignRequest.segments(),.001,30));
        // Shared draft saves retain the selector value without treating it as an authorized property source.
        var savedForeign=tx.execute(t->service.save(saveForeign));
        assertEquals(20L,savedForeign.settings().segments().getFirst().pvtId());
        assertEquals(savedForeign,service.detail(6,4,"测试井"));
        assertTrue(assertThrows(BusinessException.class,()->service.solve(
                new PipelineTemperature.Solve(saveForeign,example()))).getMessage().contains("不属于当前井"));
    }
    @Test void boundaryDraftSaveDoesNotNeedPvtAndIgnoresUnrelatedSubmittedInputs() {
        saveGraph(0,flowGraph(4000));
        jdbc.update("DELETE FROM pipeline_pvt_model");
        var input=draft(Map.of("boundary",nodeBoundary(),"thermalMode","invalid", "constraints",new Constraints("available"),
                "segments",List.of(new Segment("伪造管段",1,1,0,0,0,0))));
        var saved=tx.execute(t->storage.saveSection("boundary",new SectionSaveRequest(6,4,"测试井",0,input)));
        assertEquals(saved,storage.detail(6,4,"测试井"));
        assertEquals(nodeBoundary(),saved.input().boundary());
        assertEquals("heat",saved.input().thermalMode());
        assertEquals("unknown",saved.input().constraints().waterState());
        assertTrue(saved.input().segments().isEmpty());
        assertNull(saved.input().gasModel());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_batch_run",Integer.class));
        for(String scope:List.of("equipment","hydrate","erosion","freeze","flow"))
            assertThrows(BusinessException.class,()->storage.saveSection(scope,new SectionSaveRequest(6,4,"测试井",1,input)));
    }
}
