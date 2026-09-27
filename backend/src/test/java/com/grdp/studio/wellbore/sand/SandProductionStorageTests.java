package com.grdp.studio.wellbore.sand;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.wellbore.sand.dto.SandProductionRequest;
import com.grdp.studio.wellbore.sand.dto.SandProductionSaveRequest;
import com.grdp.studio.wellbore.sand.service.SandProductionCalculator;
import com.grdp.studio.wellbore.sand.service.SandProductionStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SandProductionStorageTests {
    private JdbcTemplate jdbc;
    private SandProductionStorageService storage;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:sand_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE project_well_heads(id BIGINT PRIMARY KEY AUTO_INCREMENT,project_id BIGINT,project_gas_reservoir_id BIGINT,well_name VARCHAR(100))");
        jdbc.update("INSERT INTO project_well_heads(project_id,project_gas_reservoir_id,well_name) VALUES(1,2,'测试井'),(1,2,'另一口井')");
        jdbc.execute("""
                CREATE TABLE project_well_sand_production (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  well_id BIGINT NOT NULL,
                  calculation_no INT NOT NULL,
                  calculation_name VARCHAR(100) NOT NULL,
                  proppant_type VARCHAR(20) NOT NULL,
                  proppant_type_label VARCHAR(50) NOT NULL,
                  proppant_mass_t DOUBLE NOT NULL,
                  proppant_density_g_cm3 DOUBLE NOT NULL,
                  fracture_half_length_m DOUBLE NOT NULL,
                  closure_pressure_mpa DOUBLE NOT NULL,
                  critical_velocity_m_s DOUBLE NOT NULL,
                  velocity_overridden TINYINT(1) NOT NULL DEFAULT 0,
                  proppant_volume_m3 DOUBLE NOT NULL,
                  fracture_area_m2 DOUBLE NOT NULL,
                  critical_rate_1e4_m3d DOUBLE NOT NULL,
                  actual_rate_1e4_m3d DOUBLE NULL,
                  ratio_percent DOUBLE NULL,
                  risk_level VARCHAR(20) NOT NULL,
                  level_key VARCHAR(16) NOT NULL,
                  algorithm_code VARCHAR(64) NOT NULL DEFAULT 'critical_sand_production_v1',
                  input_json JSON NOT NULL,
                  result_json JSON NOT NULL,
                  remark VARCHAR(500) NULL,
                  created_at TIMESTAMP NULL,
                  updated_at TIMESTAMP NULL,
                  PRIMARY KEY(id), UNIQUE KEY uk_sand_production_no(well_id,calculation_no),
                  KEY idx_sand_well_created(well_id,created_at),
                  CONSTRAINT fk_sand_well FOREIGN KEY(well_id) REFERENCES project_well_heads(id) ON UPDATE CASCADE ON DELETE CASCADE
                )
                """);
        storage = new SandProductionStorageService(jdbc, JsonMapper.builder().build(), new SandProductionCalculator());
    }

    @AfterEach
    void teardown() {
        if (jdbc != null) jdbc.execute("DROP ALL OBJECTS");
    }

    private SandProductionRequest request(String wellName, Double actualRate) {
        return new SandProductionRequest(1L, 2L, wellName, "composite-sand",
                1.34, null, 50.0, 20.0, null, actualRate);
    }

    @Test
    void saveReturnsDetailAndAssignsSequentialNumbers() {
        Map<String, Object> first = storage.save(new SandProductionSaveRequest(null, "备注A", request("测试井", null)));
        assertEquals(1, ((Number) first.get("calculationNo")).intValue());
        assertEquals("出砂方案1", first.get("calculationName"));
        assertEquals("composite-sand", first.get("proppantType"));
        assertEquals(1.0, ((Number) first.get("proppantVolumeM3")).doubleValue(), 1e-9);
        assertEquals(0.02, ((Number) first.get("fractureAreaM2")).doubleValue(), 1e-9);
        assertEquals(0.27, ((Number) first.get("criticalVelocityMS")).doubleValue(), 1e-9);
        assertEquals("未判断", first.get("riskLevel"));
        assertEquals("备注A", first.get("remark"));

        Map<String, Object> second = storage.save(new SandProductionSaveRequest("方案B", null, request("测试井", 0.5)));
        assertEquals(2, ((Number) second.get("calculationNo")).intValue());
        assertEquals("方案B", second.get("calculationName"));
        assertEquals("易出砂", second.get("riskLevel"));
        assertEquals("danger", second.get("levelKey"));
    }

    @Test
    void listFiltersByWellAndOrdersByNumberDesc() {
        storage.save(new SandProductionSaveRequest(null, null, request("测试井", null)));
        storage.save(new SandProductionSaveRequest(null, null, request("测试井", null)));
        storage.save(new SandProductionSaveRequest(null, null, request("另一口井", null)));

        List<Map<String, Object>> rows = storage.list(1L, 2L, "测试井");
        assertEquals(2, rows.size());
        assertEquals(2, ((Number) rows.get(0).get("calculationNo")).intValue());
        assertEquals(1, ((Number) rows.get(1).get("calculationNo")).intValue());
        assertEquals(1, storage.list(1L, 2L, "另一口井").size());
    }

    @Test
    void detailRoundTripsInputAndResultSnapshots() {
        Map<String, Object> saved = storage.save(new SandProductionSaveRequest(null, null, request("测试井", 0.5)));
        long id = ((Number) saved.get("id")).longValue();

        Map<String, Object> detail = storage.detail(id, 1L, 2L, "测试井");
        assertNotNull(detail.get("input_json"));
        assertNotNull(detail.get("result_json"));
        assertEquals(0.5, ((Number) detail.get("actualRate1e4M3d")).doubleValue());
        assertNotNull(detail.get("ratioPercent"));
    }

    @Test
    void deleteRemovesOnlyTheTargetRowAndRejectsUnknown() {
        Map<String, Object> saved = storage.save(new SandProductionSaveRequest(null, null, request("测试井", null)));
        long id = ((Number) saved.get("id")).longValue();

        assertThrows(BusinessException.class, () -> storage.detail(id, 1L, 2L, "另一口井"));
        storage.delete(id, 1L, 2L, "测试井");
        assertEquals(0, storage.list(1L, 2L, "测试井").size());
        assertThrows(BusinessException.class, () -> storage.delete(id, 1L, 2L, "测试井"));
    }

    @Test
    void unknownWellIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> storage.list(1L, 2L, "不存在的井"));
        assertEquals(404, ex.getCode());
    }
}
