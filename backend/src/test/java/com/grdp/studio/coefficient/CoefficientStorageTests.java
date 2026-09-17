package com.grdp.studio.coefficient;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CoefficientStorageTests {
    CoefficientStorage storage;
    JdbcTemplate jdbc;
    SingleConnectionDataSource dataSource;
    @BeforeEach void setup() throws Exception {
        // H2的CHECK表达式引用建表会话，测试全程保留该连接。
        dataSource = new SingleConnectionDataSource("jdbc:h2:mem:coefficient_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE project_summaries(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE project_gas_reservoir(id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO project_summaries VALUES(6),(7)");
        jdbc.update("INSERT INTO project_gas_reservoir VALUES(4)");
        jdbc.execute("CREATE TABLE project_well_heads(id BIGINT PRIMARY KEY,project_id BIGINT,project_gas_reservoir_id BIGINT,well_name VARCHAR(100))");
        jdbc.update("INSERT INTO project_well_heads VALUES(1,7,4,'A1-3'),(2,6,4,'A1-3'),(3,7,4,'B1')");
        jdbc.execute("CREATE TABLE project_well_pvt(id BIGINT PRIMARY KEY,well_id BIGINT)");
        jdbc.update("INSERT INTO project_well_pvt VALUES(8,1),(9,2)");
        storage = new CoefficientStorage(jdbc, JsonMapper.builder().build());
        // 使用交付脚本建隔离测试表，只移除 H2 不需要的 MySQL 引擎选项。
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("sql/productivity_coefficient.sql"))
            .replaceAll("(?m)^--.*$", "").replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        jdbc.execute(sql);
        jdbc.execute(sql); // 重复部署不能删除数据或报表已存在。
    }
    @AfterEach void cleanup() { dataSource.destroy(); }
    CoefficientStorage.Save request(Long id, String method, long pvt) {
        var p = method.equals("指数式")
            ? new CoefficientStorage.Parameters(56d,120d,null,null,null,null,24d,.6,26d,.6,24d,9d)
            : new CoefficientStorage.Parameters(56d,120d,24d,24d,28d,28d,null,null,null,null,24d,9d);
        double result = method.equals("指数式") ? 26 * Math.pow(56 - 0.101325, .6) : 100d;
        return new CoefficientStorage.Save(id,7,4,"A1-3",null,method,"production","压力法",pvt,p,Map.of("pvtName","PVT性质1"),result);
    }
    @Test void saveAndRestoreBothMethodsWithoutCurvePoints() {
        var exp = storage.save(request(null,"指数式",8));
        var bin = storage.save(request(null,"二项式",8));
        assertEquals("指数式1", exp.name()); assertEquals("二项式1", bin.name());
        assertEquals(26, exp.parameters().correctedC()); assertNull(exp.parameters().a());
        assertEquals(28, bin.parameters().correctedA()); assertNull(bin.parameters().c());
        assertEquals(2, storage.list(7,4,"A1-3").size());
        assertEquals("PVT性质1", storage.detail(exp.id(),7,4,"A1-3").pvtSnapshot().get("pvtName"));
    }
    @Test void updatingDoesNotCreateDuplicateAndNewRecordsGetNextNumber() {
        var original = storage.save(request(null,"指数式",8));
        assertEquals(original.id(), storage.save(request(original.id(),"指数式",8)).id());
        assertEquals(1, storage.list(7,4,"A1-3").size());
        assertEquals("指数式2", storage.save(request(null,"指数式",8)).name());
    }
    @Test void rejectsCrossProjectWellAndPvt() {
        var original = storage.save(request(null,"指数式",8));
        assertThrows(BusinessException.class, () -> storage.detail(original.id(),6,4,"A1-3"));
        assertThrows(BusinessException.class, () -> storage.detail(original.id(),7,4,"B1"));
        assertThrows(BusinessException.class, () -> storage.save(request(null,"指数式",9)));
        assertTrue(storage.list(6,4,"A1-3").isEmpty());
    }
    @Test void rejectsChangingMethodOrMissingRecord() {
        var original = storage.save(request(null,"指数式",8));
        assertThrows(BusinessException.class, () -> storage.save(request(original.id(),"二项式",8)));
        assertThrows(BusinessException.class, () -> storage.save(request(999L,"指数式",8)));
    }
    @Test void deletingSourcePvtKeepsHistoricalSnapshotAndNullsReference() {
        var original = storage.save(request(null,"指数式",8));
        jdbc.update("DELETE FROM project_well_pvt WHERE id=8");
        var restored = storage.detail(original.id(),7,4,"A1-3");
        assertNull(restored.pvtId());
        assertEquals("PVT性质1", restored.pvtSnapshot().get("pvtName"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> jdbc.update("DELETE FROM project_well_heads WHERE id=1"));
    }
    @Test void rejectsMissingResultAndPseudoPressureWithoutPvtRows() {
        var valid = request(null,"指数式",8);
        assertThrows(BusinessException.class, () -> storage.save(new CoefficientStorage.Save(
            null,7,4,"A1-3",null,"指数式","production","压力法",8L,
            valid.parameters(),valid.pvtSnapshot(),null)));
        assertThrows(BusinessException.class, () -> storage.save(new CoefficientStorage.Save(
            null,7,4,"A1-3",null,"指数式","production","拟压力",8L,
            valid.parameters(),valid.pvtSnapshot(),100d)));
        assertTrue(storage.list(7,4,"A1-3").isEmpty());
    }

    CoefficientStorage.Save exponential(String well, String operation, String method, CoefficientStorage.Parameters parameters, Map<String,Object> snapshot, Double result) {
        return new CoefficientStorage.Save(null,7,4,well,null,"指数式",operation,method,null,parameters,snapshot,result);
    }
    double expected(String method) {
        double difference = switch (method) {
            case "压力平方法" -> 56 * 56 - 0.101325 * 0.101325;
            case "拟压力" -> 60 * (56 - 0.101325);
            default -> 56 - 0.101325;
        };
        return 26 * Math.pow(difference,.6);
    }
    Map<String,Object> snapshot() {
        return Map.of("gasResultRows",List.of(List.of(0,0,0,0),List.of(60,0,0,3600)));
    }
    @Test void allFiveWellsSaveBothOperationsAndThreeMethodsWithoutCrossWellRecords() {
        var p = request(null,"指数式",8).parameters();
        for (int i = 1; i <= 5; i++) {
            String name = "X-" + i;
            jdbc.update("INSERT INTO project_well_heads VALUES(?,7,4,?)",100 + i,name);
            for (String operation : List.of("production","injection")) {
                for (String method : List.of("压力法","压力平方法","拟压力")) {
                    var saved = storage.save(exponential(name,operation,method,p,snapshot(),expected(method)));
                    assertEquals(expected(method),saved.result(),1e-10);
                    assertEquals(p,storage.detail(saved.id(),7,4,name).parameters());
                    assertThrows(BusinessException.class,() -> storage.detail(saved.id(),7,4,"A1-3"));
                }
            }
            assertEquals(6,storage.list(7,4,name).size());
        }
        assertTrue(storage.list(7,4,"A1-3").isEmpty());
    }
    @Test void rejectsInvalidExponentialPointAndExtremeParametersBeforeSaving() {
        var p = request(null,"指数式",8).parameters();
        var invalid = List.of(
            new CoefficientStorage.Parameters(56d,null,null,null,null,null,24d,.6,26d,.6,24d,9d),
            new CoefficientStorage.Parameters(56d,-273.15,null,null,null,null,24d,.6,26d,.6,24d,9d),
            new CoefficientStorage.Parameters(1d,120d,null,null,null,null,24d,.6,26d,.6,.5,9d),
            new CoefficientStorage.Parameters(56d,120d,null,null,null,null,24d,24d,26d,.6,24d,9d),
            new CoefficientStorage.Parameters(56d,120d,null,null,null,null,24d,.6,26d,.6,24d,null),
            new CoefficientStorage.Parameters(56d,120d,null,null,null,null,24d,.6,26d,.6,null,9d),
            new CoefficientStorage.Parameters(56d,120d,null,null,null,null,24d,.6,26d,.6,24d,0d),
            new CoefficientStorage.Parameters(56d,120d,null,null,null,null,24d,.6,26d,.6,56d,9d),
            new CoefficientStorage.Parameters(56d,120d,null,null,null,null,24d,.6,26d,.6,0d,9d),
            new CoefficientStorage.Parameters(56d,120d,null,null,null,null,24d,.6,26d,.6,24d,1e100),
            new CoefficientStorage.Parameters(56d,120d,null,null,null,null,Double.MAX_VALUE,.6,26d,.6,24d,9d));
        for (var input : invalid)
            assertThrows(BusinessException.class,() -> storage.save(exponential("A1-3","production","压力法",input,snapshot(),expected("压力法"))));
        assertThrows(BusinessException.class,() -> storage.save(exponential("A1-3","production","压力法",p,snapshot(),100d)));
        assertTrue(storage.list(7,4,"A1-3").isEmpty());
    }
    @Test void zeroRateIsOnlyAllowedAtStartingPressureAndInjectionPointMustBeAboveIt() {
        for (String operation : List.of("production","injection")) {
            double start = operation.equals("injection") ? 5.6 : 56;
            var p = new CoefficientStorage.Parameters(56d,120d,null,null,null,null,24d,.6,26d,.6,start,0d);
            assertNotNull(storage.save(exponential("A1-3",operation,"压力法",p,snapshot(),expected("压力法"))));
        }
        var p = new CoefficientStorage.Parameters(56d,120d,null,null,null,null,24d,.6,26d,.6,5.6,9d);
        assertThrows(BusinessException.class,() -> storage.save(exponential("A1-3","injection","压力法",p,snapshot(),expected("压力法"))));
    }
    @Test void validatesPseudoSnapshotMonotonicityCoverageAndNumericRows() {
        var p = request(null,"指数式",8).parameters();
        for (var rows : List.of(
            List.of(List.of(0,0,0,0),List.of(60,0,0,0)),
            List.of(List.of(0,0,0,0),List.of(60,0,0,-1)),
            List.of(List.of(0,0,0,0),List.of(10,0,0,100)),
            List.of(List.of(" ",0,0," "),List.of("0x3c",0,0,3600)))) {
            assertThrows(BusinessException.class,() -> storage.save(exponential("A1-3","production","拟压力",p,Map.of("gasResultRows",rows),expected("拟压力"))));
        }
        var rows = List.of(Map.of("pressure","6e1","pseudoPressure","3.6e3"));
        assertNotNull(storage.save(exponential("A1-3","production","拟压力",p,Map.of("gasResultRows",rows),expected("拟压力"))));
    }
}
