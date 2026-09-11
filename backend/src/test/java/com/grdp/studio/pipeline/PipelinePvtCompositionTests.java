package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineGasProperties.Fraction;
import static com.grdp.studio.pipeline.PipelinePvtModel.ComponentInput;
import static org.junit.jupiter.api.Assertions.*;

class PipelinePvtCompositionTests {
    private PipelinePvtComposition service;
    private PipelinePvtModel models;
    private JdbcTemplate jdbc;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:composition_"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
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
        var wells=new PipelineWellContext(jdbc);service=new PipelinePvtComposition(jdbc,wells);models=new PipelinePvtModel(jdbc,wells);
    }
    @AfterEach void teardown() {if(jdbc!=null)jdbc.execute("DROP ALL OBJECTS");}
    private List<ComponentInput> mixture() {return List.of(new ComponentInput("CH4",.98),new ComponentInput("CO2",.012),new ComponentInput("N2",.008));}
    private PipelinePvtModel.Save request(int revision,String method,List<ComponentInput> composition) {
        return new PipelinePvtModel.Save(6,4,"测试井",revision,"测试天然气",method,composition);
    }
    @Test void fullCompositionRoundTripIsTheOnlySourceAndNeverChangesLegacyPvt() {
        var original=jdbc.queryForList("SELECT * FROM project_well_pvt_gas_input");
        assertNull(models.detail(6,4,"测试井"));assertNull(service.current(6,4,"测试井"));
        assertThrows(BusinessException.class,()->service.detail(6,4,"测试井",11));
        var saved=models.save(request(0,"PR",mixture()));
        assertEquals(saved,models.detail(6,4,"测试井"));assertEquals(1,saved.revision());assertTrue(saved.pvtId()>0);
        assertEquals(original,jdbc.queryForList("SELECT * FROM project_well_pvt_gas_input"));
        assertEquals("测试天然气",saved.pvtName());assertEquals("PR",saved.method());assertEquals("",saved.issue());
        var detail=service.detail(6,4,"测试井",saved.pvtId());
        assertEquals(List.of(new Fraction("CH4",.98),new Fraction("CO2",.012),new Fraction("N2",.008)),detail.composition());
        assertEquals(saved.compositionRevision(),detail.revision());assertEquals(1,detail.modelRevision());
        assertEquals(saved.pvtName(),detail.pvtName());assertDoesNotThrow(()->service.requireComplete(detail));
        jdbc.update("UPDATE project_well_pvt_gas_input SET hydrogen_sulfide=98,carbon_dioxide=1.2,nitrogen=.8 WHERE pvt_id=11");
        assertEquals(detail,service.detail(6,4,"测试井",saved.pvtId()));
        assertNotEquals(original,jdbc.queryForList("SELECT * FROM project_well_pvt_gas_input"));
        var afterEdit=jdbc.queryForList("SELECT * FROM project_well_pvt_gas_input");
        service.requireComplete(service.current(6,4,"测试井"));models.detail(6,4,"测试井");
        assertEquals(afterEdit,jdbc.queryForList("SELECT * FROM project_well_pvt_gas_input"));
    }
    @Test void incompleteAndExcessTotalsAreRejectedWithoutAddingMethaneOrNormalizing() {
        for(var composition:List.of(List.of(new ComponentInput("CO2",.012),new ComponentInput("N2",.008)),
                List.of(new ComponentInput("CH4",.99),new ComponentInput("CO2",.02)))) {
            var error=assertThrows(BusinessException.class,()->models.save(request(0,"PR",composition)));
            assertEquals(400,error.getCode());assertTrue(error.getMessage().contains("100%"));
            assertNull(models.detail(6,4,"测试井"));
        }
        var rounded=List.of(new ComponentInput("CH4",.9799995),new ComponentInput("CO2",.012),new ComponentInput("N2",.008));
        var saved=models.save(request(0,"PR",rounded));
        assertEquals(.9799995,saved.composition().getFirst().moleFraction());
        assertEquals(.9999995,saved.composition().stream().mapToDouble(Fraction::moleFraction).sum(),1e-12);
    }
    @Test void invalidMissingUnknownAndDuplicateComponentsCannotReachStorage() {
        var invalid=new ArrayList<List<ComponentInput>>();
        invalid.add(null);invalid.add(List.of());invalid.add(Arrays.asList((ComponentInput)null));
        invalid.add(List.of(new ComponentInput("CH4",null),new ComponentInput("N2",1.0)));
        invalid.add(List.of(new ComponentInput(null,1.0)));invalid.add(List.of(new ComponentInput("UNKNOWN",1.0)));
        invalid.add(List.of(new ComponentInput("CH4",.5),new ComponentInput("CH4",.5)));
        for(double value:List.of(-.01,1.01,Double.NaN,Double.POSITIVE_INFINITY))invalid.add(List.of(new ComponentInput("CH4",value)));
        for(var values:invalid)assertThrows(BusinessException.class,()->models.save(request(0,"PR",values)));
        assertNull(models.detail(6,4,"测试井"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_pvt_model",Integer.class));
    }
    @Test void jsonNullAndAbsentFractionsRemainMissingRatherThanBecomingZero() {
        var mapper=JsonMapper.builder().build();
        for(String component:List.of("{\"code\":\"CH4\",\"moleFraction\":null}","{\"code\":\"CH4\"}")) {
            String json="{\"projectId\":6,\"gasReservoirId\":4,\"wellName\":\"测试井\",\"revision\":0,\"pvtName\":\"测试\",\"method\":\"PR\",\"composition\":["+component+",{\"code\":\"N2\",\"moleFraction\":1}]}";
            var request=mapper.readValue(json,PipelinePvtModel.Save.class);
            assertNull(request.composition().getFirst().moleFraction());
            assertThrows(BusinessException.class,()->models.save(request));
        }
    }
    @Test void zeroFractionsAreExplicitAndCatalogIncludesAllSupportedComponents() {
        var entries=new PipelineGasProperties().catalog().stream().map(c->new ComponentInput(c.code(),c.code().equals("CH4")?1.0:0.0)).toList();
        var saved=models.save(request(0,"PR",entries));
        assertEquals(entries.size(),saved.composition().size());assertEquals(entries.size()-1,saved.composition().stream().filter(f->f.moleFraction()==0).count());
        assertTrue(saved.composition().stream().anyMatch(f->f.code().equals("CH4")&&f.moleFraction()==1));
    }
    @Test void optimisticVersionsPreserveOneModelPerWellAndRejectStaleWrites() {
        var first=models.save(request(0,"PR",mixture()));
        assertEquals(409,assertThrows(BusinessException.class,()->models.save(request(0,"SRK",mixture()))).getCode());
        var second=models.save(request(1,"SRK",mixture()));
        assertEquals(first.pvtId(),second.pvtId());assertEquals(2,second.revision());assertEquals("SRK",second.method());
        assertEquals(409,assertThrows(BusinessException.class,()->models.save(request(1,"BWRS",mixture()))).getCode());
        assertEquals(second,models.detail(6,4,"测试井"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_pvt_model",Integer.class));
    }
    @Test void fingerprintsTrackEveryFractionAndMethodButIgnoreOrderingAndDisplayName() {
        var first=models.save(request(0,"PR",mixture()));var reverse=new ArrayList<>(mixture());Collections.reverse(reverse);
        var renamed=models.save(new PipelinePvtModel.Save(6,4,"测试井",1,"重命名","PR",reverse));
        assertEquals(first.compositionRevision(),renamed.compositionRevision());
        var method=models.save(request(2,"SRK",mixture()));assertNotEquals(first.compositionRevision(),method.compositionRevision());
        var changed=models.save(request(3,"SRK",List.of(new ComponentInput("CH4",.97),new ComponentInput("CO2",.022),new ComponentInput("N2",.008))));
        assertNotEquals(method.compositionRevision(),changed.compositionRevision());
        assertEquals(first.compositionRevision(),models.save(request(4,"PR",mixture())).compositionRevision());
    }
    @Test void crossWellCrossProjectLegacyAndDeletedIdsCannotResolveAsCurrentComposition() {
        var one=models.save(request(0,"PR",mixture()));
        var other=models.save(new PipelinePvtModel.Save(7,4,"测试井",0,"其他项目","PR",mixture()));
        assertNotEquals(one.pvtId(),other.pvtId());assertNull(models.detail(6,4,"另一口井"));
        for(long id:List.of(other.pvtId(),11L,99L))assertThrows(BusinessException.class,()->service.detail(6,4,"测试井",id));
        assertThrows(BusinessException.class,()->service.detail(7,4,"测试井",one.pvtId()));
        assertThrows(BusinessException.class,()->models.save(new PipelinePvtModel.Save(6,5,"测试井",0,"错误上下文","PR",mixture())));
        jdbc.update("DELETE FROM pipeline_pvt_model WHERE id=?",one.pvtId());
        assertNull(service.current(6,4,"测试井"));assertThrows(BusinessException.class,()->service.detail(6,4,"测试井",one.pvtId()));
    }
    @Test void damagedStoredJsonIsReadableAsAnIssueAndCannotBecomeAnImplicitCompleteMixture() {
        var saved=models.save(request(0,"PR",mixture()));
        for(String raw:List.of("not-json","{}","[]","[{\"code\":\"CH4\",\"moleFraction\":null},{\"code\":\"N2\",\"moleFraction\":1}]",
                "[{\"code\":\"CH4\",\"moleFraction\":0.8}]","[{\"code\":\"CH4\",\"moleFraction\":0.5},{\"code\":\"CH4\",\"moleFraction\":0.5}]")) {
            jdbc.update("UPDATE pipeline_pvt_model SET composition_json=? WHERE id=?",raw,saved.pvtId());
            var detail=service.current(6,4,"测试井");assertFalse(detail.issue().isBlank());
            assertThrows(BusinessException.class,()->service.requireComplete(detail));
            assertEquals(raw,jdbc.queryForObject("SELECT composition_json FROM pipeline_pvt_model WHERE id=?",String.class,saved.pvtId()));
        }
    }
    @Test void invalidNameMethodAndVersionsDoNotWriteAPvtModel() {
        for(String method:Arrays.asList(null,"","pr","OTHER"))assertThrows(BusinessException.class,()->models.save(request(0,method,mixture())));
        for(String name:Arrays.asList(null," ","x".repeat(101)))assertThrows(BusinessException.class,()->models.save(new PipelinePvtModel.Save(6,4,"测试井",0,name,"PR",mixture())));
        assertThrows(BusinessException.class,()->models.save(request(-1,"PR",mixture())));
        assertEquals(409,assertThrows(BusinessException.class,()->models.save(request(1,"PR",mixture()))).getCode());
        assertNull(models.detail(6,4,"测试井"));
    }
}
