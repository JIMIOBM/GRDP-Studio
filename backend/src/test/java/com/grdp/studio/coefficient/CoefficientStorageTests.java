package com.grdp.studio.coefficient;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
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
        // 独立内存测试表；正式建表文本单独交付，不依赖开发者本地SQL文件。
        // 此处验证存储契约与外键行为，不代替MySQL部署脚本验收。
        jdbc.execute("""
            CREATE TABLE project_well_productivity_coefficient (
              id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              project_id BIGINT NOT NULL, gas_reservoir_id BIGINT NOT NULL,
              well_id BIGINT NOT NULL, record_no INT NOT NULL,
              record_name VARCHAR(100) NOT NULL, method_type VARCHAR(20) NOT NULL,
              operation_type VARCHAR(20) NOT NULL, pressure_method VARCHAR(30) NOT NULL,
              pvt_id BIGINT NULL, parameters_json LONGTEXT NOT NULL,
              pvt_snapshot_json LONGTEXT NOT NULL, result_value DOUBLE NOT NULL,
              result_type VARCHAR(30) NOT NULL, calculation_version VARCHAR(30) NOT NULL,
              units VARCHAR(100) NOT NULL,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              UNIQUE (project_id,gas_reservoir_id,well_id,method_type,record_no),
              FOREIGN KEY (project_id) REFERENCES project_summaries(id) ON DELETE RESTRICT,
              FOREIGN KEY (gas_reservoir_id) REFERENCES project_gas_reservoir(id) ON DELETE RESTRICT,
              FOREIGN KEY (well_id) REFERENCES project_well_heads(id) ON DELETE RESTRICT,
              FOREIGN KEY (pvt_id) REFERENCES project_well_pvt(id) ON DELETE SET NULL,
              CHECK (record_no > 0), CHECK (CHAR_LENGTH(TRIM(record_name)) > 0),
              CHECK (method_type IN ('二项式','指数式')),
              CHECK (operation_type IN ('production','injection')),
              CHECK (pressure_method IN ('拟压力','压力平方法','压力法')),
              CHECK (result_value >= 0),
              CHECK ((method_type='二项式' AND operation_type='injection' AND result_type='injection-limit')
                OR ((method_type='指数式' OR operation_type='production') AND result_type='open-flow'))
            )
            """);
    }
    @AfterEach void cleanup() { dataSource.destroy(); }
    CoefficientStorage.Save request(Long id, String method, long pvt) {
        var p = method.equals("指数式")
            ? new CoefficientStorage.Parameters(56d,120d,null,null,null,null,24d,.6,26d,.6,24d,9d)
            : new CoefficientStorage.Parameters(56d,120d,24d,24d,28d,28d,null,null,null,null,24d,9d);
        return new CoefficientStorage.Save(id,7,4,"A1-3",null,method,"production","压力法",pvt,p,Map.of("pvtName","PVT性质1"),100d);
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
}
