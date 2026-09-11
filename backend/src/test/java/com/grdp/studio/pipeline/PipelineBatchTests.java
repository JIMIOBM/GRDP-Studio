package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PipelineBatchTests {
    private JdbcTemplate jdbc;private ObjectMapper json;private PipelineBatch batch;private PipelineTopology topology;
    private PipelineGasModel gas;private PipelineTemperature temperature;private PipelineNetworkCalculator network;
    private PipelineWellContext wells;private DataSourceTransactionManager transactions;private Clock clock;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:batch_"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        jdbc=new JdbcTemplate(ds);json=JsonMapper.builder().build();transactions=new DataSourceTransactionManager(ds);
        jdbc.execute("CREATE TABLE project_well_heads(id BIGINT PRIMARY KEY,project_id BIGINT,project_gas_reservoir_id BIGINT,well_name VARCHAR(100))");
        jdbc.update("INSERT INTO project_well_heads VALUES(1,6,4,'测试井'),(2,7,4,'测试井'),(3,6,4,'另一口井')");
        jdbc.execute("CREATE TABLE project_well_pvt(id BIGINT PRIMARY KEY,well_id BIGINT,pvt_name VARCHAR(100),pvt_no INT)");
        jdbc.execute("CREATE TABLE project_well_pvt_gas_input(pvt_id BIGINT PRIMARY KEY,hydrogen_sulfide DOUBLE,carbon_dioxide DOUBLE,nitrogen DOUBLE,gas_type VARCHAR(100),specific_gravity DOUBLE,condensate_oil_density DOUBLE)");
        jdbc.execute("CREATE TABLE project_well_pvt_gas_result(pvt_id BIGINT,pressure DOUBLE,temperature DOUBLE,density DOUBLE,viscosity DOUBLE)");
        String sql=Files.readString(Path.of("sql/pipeline_capacity.sql"),StandardCharsets.UTF_8)
                .replaceAll("(?s)-- BEGIN REMOVE_BOUNDARY_USAGE.*?-- END REMOVE_BOUNDARY_USAGE","")
                .replaceAll("(?m)^--.*$","").replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='[^']*'","")
                .replaceAll("\\bJSON\\b","CLOB");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(ds);
        wells=new PipelineWellContext(jdbc);topology=new PipelineTopology(jdbc,json,wells);
        gas=new PipelineGasModel(jdbc,json,wells,new PipelinePvtComposition(jdbc,wells),new PipelineGasProperties());
        temperature=mock(PipelineTemperature.class);network=mock(PipelineNetworkCalculator.class);clock=mock(Clock.class);
        when(clock.millis()).thenReturn(1_000L);
        batch=newBatch(16);
        jdbc.update("INSERT INTO pipeline_pvt_model(id,well_id,revision,pvt_name,method,composition_json) VALUES(10,1,1,'批次组成','PR',?)",
                json.writeValueAsString(List.of(new PipelineGasProperties.Fraction("CH4",1))));
        topology.save(new PipelineTopology.Save(6,4,"测试井",0,graph()));
        when(network.calculate(any(),any(),any(),any(),any(),anyInt())).thenAnswer(invocation->{
            BoundaryCase c=invocation.getArgument(4);
            if(c.id().equals("bad"))throw new BusinessException(400,"下游分输量超过井口供气量");
            return solved(c);
        });
    }
    @AfterEach void teardown(){if(jdbc!=null)jdbc.execute("DROP ALL OBJECTS");}
    private PipelineBatch newBatch(int capacity) {
        return new PipelineBatch(jdbc,json,wells,topology,gas,temperature,network,transactions,clock,60_000,capacity,4*1024*1024);
    }
    private PipelineTopology.Graph graph() {
        return new PipelineTopology.Graph(List.of(
                new PipelineTopology.Node("w","well","测试井",Map.of("elevationM",0.0),0,0),
                new PipelineTopology.Node("s","station","出口",Map.of("elevationM",10.0),100,0)),
                List.of(new PipelineTopology.Edge("e","w","s","管道1",Map.of("lengthM",100.0,"diameterMm",100.0,"roughnessMm",.03,"ambientC",99.0,"heatTransferWm2K",88.0))),Map.of(),Map.of());
    }
    private BoundaryCase condition(String id,String at) {
        return new BoundaryCase(id,at,List.of(new BoundaryNode("w",8.0,null,8.0,40.0),new BoundaryNode("s",null,8.0,7.0,null)));
    }
    private Input input(String mode,BoundaryCase... cases) {
        return new Input("outlet",mode,"colebrook",999.0,888.0,777.0,666.0,null,null,null,null,.12,101325.0,293.15,1.0,
                List.of(),List.of(),null,null,new Boundary(1,cases.length==0?null:cases[0].id(),Arrays.asList(cases)));
    }
    private PipelineBatch.Calculate request(Input in) {return new PipelineBatch.Calculate(6,4,"测试井",0,1,in);}
    private Input withWater(Input in,String water) {
        return new Input(in.target(),in.thermalMode(),in.frictionMethod(),in.inletMpa(),in.outletMpa(),in.rate10k(),in.inletC(),
                in.gasGravity(),in.z(),in.viscosityMpaS(),in.cpJkgK(),in.jtKmpa(),in.standardPressurePa(),in.standardTemperatureK(),
                in.standardZ(),in.segments(),in.equipment(),new Constraints(water),in.gasModel(),in.boundary(),in.thermalModel());
    }
    private PipelineBatch.Save saveRequest(PipelineBatch.Detail d) {return new PipelineBatch.Save(6,4,"测试井",d.calculationToken());}
    private PipelineNetworkCalculator.NetworkResult solved(BoundaryCase c) {
        return new PipelineNetworkCalculator.NetworkResult(List.of(new PipelineNetworkCalculator.PipeResult("e","管道1","w","s",8,7,40,39,8,15,3)),List.of(),List.of(),List.of("计算工况："+c.id()));
    }
    private int count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @Test void calculatesEveryCaseInTimeOrderWithoutWritingAndContinuesAfterOneFailure() {
        var input=input("isothermal",condition("later","2026-09-10T03:00"),condition("bad","2026-09-10T02:00"),condition("early","2026-09-10T01:00"));
        var detail=batch.calculate(request(input));
        assertNull(detail.id());assertEquals(0,detail.revision());assertNotNull(detail.calculationToken());
        assertEquals(List.of("early","bad","later"),detail.result().cases().stream().map(PipelineBatch.CaseResult::caseId).toList());
        assertEquals(2,detail.result().successCount());assertEquals(1,detail.result().failureCount());
        assertEquals("下游分输量超过井口供气量",detail.result().cases().get(1).error());
        assertTrue(detail.result().cases().get(1).pipes().isEmpty());
        assertEquals(3,detail.input().boundary().cases().size());assertEquals(input.boundary(),detail.input().boundary());
        assertNull(detail.input().inletMpa());assertNull(detail.input().rate10k());assertNull(detail.input().thermalModel());
        assertEquals("PR",detail.input().gasModel().method());assertEquals(0,detail.input().segments().getFirst().heatTransferWm2K());
        assertEquals(0,count("pipeline_batch_run"));assertEquals(0,count("pipeline_model"));
        verify(network,times(3)).calculate(eq(detail.graph()),any(),isNull(),eq(detail.input().gasModel()),any(),eq(1));
        verifyNoInteractions(temperature);
    }
    @Test void batchLengthComesFromRegisteredCasesAcrossDaysAndNeverFromTheActiveCaseOrAFixedDay() {
        var start=java.time.LocalDateTime.of(2026,9,10,0,0);
        var cases=java.util.stream.IntStream.range(0,27).mapToObj(i->condition("hour-"+i,start.plusHours(i).toString())).toArray(BoundaryCase[]::new);
        var d=batch.calculate(request(input("isothermal",cases)));
        assertEquals(27,d.result().successCount());assertEquals("2026-09-11T02:00",d.result().cases().getLast().operatingAt());
        verify(network,times(27)).calculate(any(),any(),any(),any(),any(),anyInt());
        var tooMany=java.util.stream.IntStream.range(0,745).mapToObj(i->condition("hour-"+i,start.plusHours(i).toString())).toArray(BoundaryCase[]::new);
        assertThrows(BusinessException.class,()->batch.calculate(request(input("isothermal",tooMany))));
        verifyNoMoreInteractions(network);
    }
    @Test void invalidMissingAndDuplicateTimestampsAreReportedWithoutFabricatedDatesOrCallingSolver() {
        var d=batch.calculate(request(input("isothermal",condition("valid","2026-09-10T01:00"),condition("missing",null),
                condition("date","2026-02-30T02:00"),condition("minute","2026-09-10T02:30"),
                condition("duplicate1","2026-09-10T03:00"),condition("duplicate2","2026-09-10 03:00:00"))));
        assertEquals(2,d.result().successCount());assertEquals(4,d.result().failureCount());
        assertNull(d.result().cases().stream().filter(c->c.caseId().equals("missing")).findFirst().orElseThrow().operatingAt());
        assertTrue(d.result().cases().stream().filter(c->c.caseId().startsWith("duplicate")).allMatch(c->c.error().contains("时间重复")));
        assertEquals("2026-09-10T03:00",d.input().boundary().cases().getLast().operatingAt());
        assertEquals("2026-09-10T03:00",d.result().cases().stream().filter(c->c.caseId().equals("duplicate2")).findFirst().orElseThrow().operatingAt());
        assertEquals("2026-09-10T02:30",d.result().cases().stream().filter(c->c.caseId().equals("minute")).findFirst().orElseThrow().operatingAt());
        verify(network,times(2)).calculate(any(),any(),any(),any(),any(),anyInt());
        assertThrows(BusinessException.class,()->batch.save(saveRequest(d)));
        assertEquals(0,count("pipeline_model"));assertEquals(0,count("pipeline_batch_run"));
        var one=batch.calculate(request(input("isothermal",condition("one",null))));
        assertEquals(0,one.result().successCount());assertThrows(BusinessException.class,()->batch.save(saveRequest(one)));
    }
    @Test void minuteResolutionAndDisplayFormatSurviveSortingCalculationSavingAndReloading() {
        var d=batch.calculate(request(input("isothermal",condition("tomorrow","2026/09/11 00:07"),
                condition("second","2026-09-10T23:58:00"),condition("first","2026/09/10 23:37"))));
        assertEquals(3,d.result().successCount());assertEquals(0,d.result().failureCount());
        assertEquals(List.of("2026-09-10T23:37","2026-09-10T23:58","2026-09-11T00:07"),
                d.result().cases().stream().map(PipelineBatch.CaseResult::operatingAt).toList());
        assertEquals(List.of("2026-09-11T00:07","2026-09-10T23:58","2026-09-10T23:37"),
                d.input().boundary().cases().stream().map(BoundaryCase::operatingAt).toList());
        var saved=batch.save(saveRequest(d));var reloaded=batch.latest(6,4,"测试井");
        assertEquals(saved.input(),reloaded.input());assertEquals(saved.result(),reloaded.result());
        var stored=json.readValue(jdbc.queryForObject("SELECT input_json FROM pipeline_model",String.class),Input.class);
        assertEquals(d.input().boundary(),stored.boundary());
    }
    @Test void slashAndIsoTimesAtTheSameMinuteAreBothReportedAsDuplicate() {
        var d=batch.calculate(request(input("isothermal",condition("one","2026/09/10 12:37"),
                condition("two","2026-09-10T12:37:00"))));
        assertEquals(0,d.result().successCount());assertEquals(2,d.result().failureCount());
        assertTrue(d.result().cases().stream().allMatch(c->c.error().contains("时间重复")));
        verifyNoInteractions(network);
    }
    @Test void saveCommitsModelGeometryCompleteBatchAndOriginalResultsAndIsIdempotent() {
        var calculated=batch.calculate(request(input("isothermal",condition("ok","2026-09-10T01:00"),condition("bad","2026-09-10T02:00"))));
        var saved=batch.save(saveRequest(calculated));
        assertNotNull(saved.id());assertEquals(1,saved.revision());assertEquals(calculated.result(),saved.result());
        assertEquals(calculated.input(),saved.input());assertEquals(1,count("pipeline_model"));
        assertEquals(0.0,saved.input().segments().getFirst().heatTransferWm2K());
        assertEquals(saved.input(),json.readValue(jdbc.queryForObject("SELECT input_json FROM pipeline_model",String.class),Input.class));
        assertEquals(saved,batch.save(saveRequest(calculated)));assertEquals(1,count("pipeline_batch_run"));
        var reloaded=batch.latest(6,4,"测试井");
        assertEquals(saved.result(),reloaded.result());assertEquals(saved.input(),reloaded.input());assertEquals(saved.graph(),reloaded.graph());
        assertEquals(1,reloaded.revision());assertNull(reloaded.calculationToken());assertNull(batch.latest(6,4,"另一口井"));
        verify(network,times(2)).calculate(any(),any(),any(),any(),any(),anyInt());
    }
    @Test void equipmentAndHydrateRisksRemainSuccessfulCasesAndSurviveSavingWithExactMarginsAndIdentity() {
        jdbc.update("UPDATE pipeline_pvt_model SET composition_json=? WHERE well_id=1",json.writeValueAsString(List.of(
                new PipelineGasProperties.Fraction("CH4",.94),new PipelineGasProperties.Fraction("C2H6",.04),new PipelineGasProperties.Fraction("C3H8",.01),
                new PipelineGasProperties.Fraction("N2",.005),new PipelineGasProperties.Fraction("CO2",.005))));
        var hydrateModel=PipelineHydrateModel.prepare(gas.snapshot(6,4,"测试井"));
        double equilibrium=hydrateModel.at(6).temperatureC();
        var hydrate=new PipelineNetworkCalculator.HydrateResult("e","管道1",50,"管内采样点",6,10,equilibrium,10-equilibrium,"risk","进入温压区",21,21);
        var device=new PipelineNetworkCalculator.DeviceResult("s:0","s",1,"压缩机","compressor","e",7,8.4,39,61,8,600,8.0,500.0,-.4,-100.0,"fail");
        doAnswer(invocation->{
            BoundaryCase c=invocation.getArgument(4);var normal=solved(c);
            return new PipelineNetworkCalculator.NetworkResult(normal.pipes(),List.of(device),List.of(hydrate),normal.notes());
        }).when(network).calculate(any(),any(),any(),any(),any(),anyInt());
        var calculated=batch.calculate(request(withWater(input("isothermal",condition("a","2026-09-10T01:17"),condition("b","2026-09-10T02:49")),"available")));
        assertEquals(PipelineBatch.VERSION,calculated.result().algorithmVersion());
        assertEquals(hydrateModel.metadata(),calculated.result().hydrateModel());
        assertEquals(2,calculated.result().successCount());assertEquals(0,calculated.result().failureCount());
        assertTrue(calculated.result().cases().stream().allMatch(c->c.status().equals("success")&&c.equipment().equals(List.of(device))));
        var saved=batch.save(saveRequest(calculated));var reloaded=batch.latest(6,4,"测试井");
        assertEquals(saved.result(),reloaded.result());assertEquals(device,reloaded.result().cases().getFirst().equipment().getFirst());
        assertEquals(hydrate,reloaded.result().cases().getFirst().hydrate().getFirst());assertEquals("available",reloaded.input().constraints().waterState());
        verify(network,times(2)).calculate(any(),any(),any(),any(),any(),anyInt());
    }
    @Test void unknownWaterIsExplicitInTheSnapshotAndUnsupportedStatesAreRejectedBeforeSolving() {
        var original=input("isothermal",condition("one","2026-09-10T01:17"));
        var unspecified=batch.calculate(request(original));
        assertEquals(new Constraints("unknown"),unspecified.input().constraints());
        var explicitNull=batch.calculate(request(withWater(original,null)));
        assertEquals(new Constraints("unknown"),explicitNull.input().constraints());
        assertThrows(BusinessException.class,()->batch.calculate(request(withWater(original,"dry"))));
        assertThrows(BusinessException.class,()->batch.calculate(request(withWater(original,"present"))));
        verify(network,times(2)).calculate(any(),any(),any(),any(),any(),anyInt());
    }
    @Test void existingModelSaveUpdatesAllDraftParametersAndKeepsRevisionConsistent() {
        var original=input("isothermal",condition("old","2026-09-10T01:00"));
        jdbc.update("INSERT INTO pipeline_model(well_id,revision,topology_revision,input_json) VALUES(1,4,1,?)",json.writeValueAsString(original));
        var changed=input("isothermal",condition("new","2026-09-10T02:00"));
        var calculated=batch.calculate(new PipelineBatch.Calculate(6,4,"测试井",4,1,changed));
        var saved=batch.save(saveRequest(calculated));
        assertEquals(5,saved.revision());assertEquals(5,jdbc.queryForObject("SELECT revision FROM pipeline_model",Integer.class));
        assertEquals(5,jdbc.queryForObject("SELECT model_revision FROM pipeline_batch_run",Integer.class));
        assertEquals("new",saved.input().boundary().cases().getFirst().id());
    }
    @Test void simultaneousRepeatedSaveCommitsOnlyOneModelRevisionAndOneBatch() throws Exception {
        var calculated=batch.calculate(request(input("isothermal",condition("ok","2026-09-10T01:00"))));
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<PipelineBatch.Detail> save=()->batch.save(saveRequest(calculated));
            var responses=executor.invokeAll(List.of(save,save));
            assertEquals(responses.getFirst().get(),responses.getLast().get());
        }
        assertEquals(1,count("pipeline_batch_run"));assertEquals(1,jdbc.queryForObject("SELECT revision FROM pipeline_model",Integer.class));
        verify(network,times(1)).calculate(any(),any(),any(),any(),any(),anyInt());
    }
    @Test void ownershipModelRevisionAndSourceChangesRejectStaleWorkWithoutWrites() {
        var calculate=request(input("isothermal",condition("ok","2026-09-10T01:00")));
        assertThrows(BusinessException.class,()->batch.calculate(new PipelineBatch.Calculate(7,4,"测试井",0,1,calculate.input())));
        assertThrows(BusinessException.class,()->batch.calculate(new PipelineBatch.Calculate(6,4,"测试井",1,1,calculate.input())));
        var d=batch.calculate(calculate);
        assertThrows(BusinessException.class,()->batch.save(new PipelineBatch.Save(6,4,"另一口井",d.calculationToken())));
        jdbc.update("UPDATE pipeline_pvt_model SET revision=2 WHERE well_id=1");
        assertThrows(BusinessException.class,()->batch.save(saveRequest(d)));assertEquals(0,count("pipeline_model"));
        jdbc.update("UPDATE pipeline_pvt_model SET revision=1 WHERE well_id=1");
        jdbc.update("UPDATE pipeline_topology SET revision=2 WHERE well_id=1");
        assertThrows(BusinessException.class,()->batch.save(saveRequest(d)));assertEquals(0,count("pipeline_batch_run"));
        jdbc.update("UPDATE pipeline_topology SET revision=1 WHERE well_id=1");
        jdbc.update("INSERT INTO pipeline_model(well_id,revision,topology_revision,input_json) VALUES(1,1,1,?)",json.writeValueAsString(calculate.input()));
        assertThrows(BusinessException.class,()->batch.save(saveRequest(d)));assertEquals(0,count("pipeline_batch_run"));
    }
    @Test void heatUsesOneSavedSnapshotAndRejectsMissingOrChangedTemperatureSource() {
        var request=request(input("heat",condition("first","2026-09-10T01:00"),condition("second","2026-09-10T02:00")));
        assertThrows(BusinessException.class,()->batch.calculate(request));
        var settings=new PipelineTemperature.FlowSettings(List.of(),.001,30);
        var snapshot=new PipelineTemperature.Snapshot(1,1,settings);
        when(temperature.snapshot(6,4,"测试井",1)).thenReturn(snapshot);
        var d=batch.calculate(request);assertEquals(snapshot,d.input().thermalModel());
        verify(network,times(2)).calculate(any(),any(),eq(snapshot),any(),any(),eq(1));
        when(temperature.snapshot(6,4,"测试井",1)).thenReturn(new PipelineTemperature.Snapshot(2,1,settings));
        assertThrows(BusinessException.class,()->batch.save(saveRequest(d)));assertEquals(0,count("pipeline_model"));
    }
    @Test void aFailedInsertRollsBackModelAndGeometryAndTheTokenCanRetryWithoutRecalculation() {
        var d=batch.calculate(request(input("isothermal",condition("ok","2026-09-10T01:00"))));
        jdbc.execute("ALTER TABLE pipeline_batch_run ADD CONSTRAINT batch_failure CHECK(model_revision<0)");
        assertThrows(RuntimeException.class,()->batch.save(saveRequest(d)));
        assertEquals(0,count("pipeline_model"));assertEquals(0,count("pipeline_batch_run"));
        jdbc.execute("ALTER TABLE pipeline_batch_run DROP CONSTRAINT batch_failure");
        assertNotNull(batch.save(saveRequest(d)).id());
        verify(network,times(1)).calculate(any(),any(),any(),any(),any(),anyInt());
    }
    @Test void cacheIsBoundedAndExpiredTokensRequireARealRecalculation() {
        batch=newBatch(1);var request=request(input("isothermal",condition("ok","2026-09-10T01:00")));
        var first=batch.calculate(request);var second=batch.calculate(request);
        assertThrows(BusinessException.class,()->batch.save(saveRequest(first)));
        when(clock.millis()).thenReturn(61_001L);
        assertThrows(BusinessException.class,()->batch.save(saveRequest(second)));assertEquals(0,count("pipeline_model"));
    }
    @Test void callerMutationCannotChangeCachedSourcesAndUnexpectedCaseFailureDoesNotAbortOtherCases() {
        doAnswer(invocation->{
            BoundaryCase c=invocation.getArgument(4);if(c.id().equals("bad"))throw new IllegalStateException("internal");return solved(c);
        }).when(network).calculate(any(),any(),any(),any(),any(),anyInt());
        var d=batch.calculate(request(input("isothermal",condition("bad","2026-09-10T01:00"),condition("ok","2026-09-10T02:00"))));
        assertEquals(1,d.result().successCount());assertEquals(1,d.result().failureCount());
        d.graph().edges().getFirst().parameters().put("lengthM",999.0);
        var saved=batch.save(saveRequest(d));
        assertEquals(100.0,((Number)saved.graph().edges().getFirst().parameters().get("lengthM")).doubleValue());
        assertEquals(100.0,saved.input().segments().getFirst().lengthM());
    }
}
