package com.grdp.studio.reservoirloss;

import com.grdp.studio.reservoirloss.dto.SurfaceLossDtos.*;
import com.grdp.studio.reservoirloss.service.SurfaceLossStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import com.grdp.studio.common.GlobalExceptionHandler;
import com.grdp.studio.gaspvt.service.GasPvtService;
import com.grdp.studio.reservoirloss.controller.SurfaceLossController;
import com.grdp.studio.reservoirloss.controller.GeologicalLossController;
import com.grdp.studio.reservoirloss.service.SurfaceLossCalculationService;
import com.grdp.studio.reservoirloss.service.GeologicalLossCalculationService;
import com.grdp.studio.reservoirloss.service.GeologicalLossStorageService;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** 用隔离数据库验证实际建表SQL、主子表事务及项目/库归属，不写入用户业务数据。 */
class SurfaceLossStorageTests {
    JdbcTemplate jdbc;
    SurfaceLossStorageService storage;

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        String sql = Files.readString(Path.of("sql/reservoir_surface_loss.sql"))
                .replaceAll("(?m)^--.*$", "").replace("USE `database`;", "")
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        storage = new SurfaceLossStorageService(jdbc, new DataSourceTransactionManager(source));
    }

    static SurfaceInput formula(double volume) {
        return new SurfaceInput("formula", null, 313.15, 20d, 10d,
                List.of(new SegmentInput(volume), new SegmentInput(50)), 0, .7336,
                14.62, 8.96, 0d, 0, 0, 0, "pvt.xls", .2, 50d, null);
    }

    static Calculation output(double total) {
        return new Calculation(1350L, .9, .95, total,
                1e-4 * total * 293.15 / (.101325 * 313.15) * (20 / .9 - 10 / .95), 10);
    }

    @Test
    void formulaRoundTripUpdateScopeAndCascadeDelete() {
        var saved = storage.save(new SaveRequest(null, 7, 4, formula(100), output(150)));
        var detail = storage.detail(saved.id(), 7, 4);
        assertEquals(2, detail.input().segments().size());
        assertEquals(150, detail.calculation().totalSegmentVolume());
        assertEquals("pvt.xls", detail.input().importedFileName());
        assertEquals(.2, detail.input().condensateVolume());
        assertEquals(50, detail.input().gasOilRatio());
        assertEquals(10, detail.calculation().condensateLossVolume());
        assertTrue(storage.list(7, 5).isEmpty());
        assertThrows(ResponseStatusException.class, () -> storage.detail(saved.id(), 7, 5));
        assertThrows(ResponseStatusException.class, () -> storage.rename(saved.id(), 8, 4, "other"));
        assertThrows(ResponseStatusException.class, () -> storage.delete(saved.id(), 7, 5));
        assertEquals("修改名称", storage.rename(saved.id(), 7, 4, "修改名称").recordName());

        var direct = new SurfaceInput("direct", 12d, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, 10d);
        storage.save(new SaveRequest(saved.id(), 7, 4, direct, new Calculation(null, null, null, null, 12, 10)));
        assertTrue(storage.detail(saved.id(), 7, 4).input().segments().isEmpty());
        assertNull(storage.detail(saved.id(), 7, 4).input().averageTemperatureK());
        assertNull(storage.detail(saved.id(), 7, 4).input().condensateVolume());
        assertNull(storage.detail(saved.id(), 7, 4).input().gasOilRatio());
        assertEquals(10, storage.detail(saved.id(), 7, 4).input().inputCondensateLossVolume());
        storage.save(new SaveRequest(saved.id(), 7, 4, formula(80), output(130)));
        assertNull(storage.detail(saved.id(), 7, 4).input().inputCondensateLossVolume());
        assertEquals(80, storage.detail(saved.id(), 7, 4).input().segments().get(0).segmentVolume());
        storage.delete(saved.id(), 7, 4);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM project_reservoir_surface_loss_segment", Integer.class));
    }

    @Test
    void failedDetailReplacementRollsBackMainAndPreviousSegments() {
        var saved = storage.save(new SaveRequest(null, 7, 4, formula(100), output(150)));
        jdbc.execute("ALTER TABLE project_reservoir_surface_loss_segment ADD CONSTRAINT test_volume CHECK(segment_volume <> 123)");
        assertThrows(DataIntegrityViolationException.class,
                () -> storage.save(new SaveRequest(saved.id(), 7, 4, formula(123), output(173))));
        assertEquals(100, storage.detail(saved.id(), 7, 4).input().segments().get(0).segmentVolume());
        assertEquals(150, storage.detail(saved.id(), 7, 4).calculation().totalSegmentVolume());
    }

    @Test
    void rejectsStaleOrTamperedCalculation() {
        var result = output(150);
        assertThrows(ResponseStatusException.class, () -> storage.save(new SaveRequest(null, 7, 4,
                formula(100), new Calculation(1350L, .9, .95, 150d, result.ventLossVolume() + 10, 10))));
        assertTrue(storage.list(7, 4).isEmpty());
    }

    @Test
    void httpSaveRecalculatesAndRoutesAlongsideGeologicalEndpoints() throws Exception {
        var pvt = mock(GasPvtService.class);
        var controller = new SurfaceLossController(new SurfaceLossCalculationService(new com.grdp.studio.reservoirloss.service.WellboreLossCalculationService(pvt)), storage);
        var mvc = MockMvcBuilders.standaloneSetup(controller,
                new GeologicalLossController(mock(GeologicalLossCalculationService.class), mock(GeologicalLossStorageService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        // 客户端故意提交999，实际保存结果必须由服务端根据直接输入12重新核算。
        String json = """
                {"projectId":7,"gasReservoirId":4,"input":{"calculationMode":"direct","inputLossVolume":12,"inputCondensateLossVolume":10},
                 "calculation":{"ventLossVolume":999,"condensateLossVolume":999}}
                """;
        String body = mvc.perform(post("/reservoir-loss/surface/save")
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = new ObjectMapper().readTree(body).get("data").get("id").longValue();
        mvc.perform(post("/reservoir-loss/surface/calculate")
                .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ventLossVolume").value(12))
                .andExpect(jsonPath("$.data.condensateLossVolume").value(10));
        mvc.perform(post("/reservoir-loss/surface/calculate")
                .contentType(MediaType.APPLICATION_JSON).content(json.replace(",\"inputCondensateLossVolume\":10", "")))
                .andExpect(status().isBadRequest());
        assertEquals(12, storage.detail(id, 7, 4).calculation().ventLossVolume());
        assertEquals(10, storage.detail(id, 7, 4).calculation().condensateLossVolume());
        assertEquals(10, storage.detail(id, 7, 4).input().inputCondensateLossVolume());
        verifyNoInteractions(pvt);
        mvc.perform(get("/reservoir-loss/surface/records").param("projectId", "7").param("gasReservoirId", "4"))
                .andExpect(status().isOk());
        mvc.perform(patch("/reservoir-loss/surface/" + id + "/name").param("projectId", "8").param("gasReservoirId", "4")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"wrong scope\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/reservoir-loss/surface/" + id).param("projectId", "7").param("gasReservoirId", "4"))
                .andExpect(status().isOk());
    }

    @Test
    void upgradeKeepsOldResultsAndBackfillsOnlyDirectInput() throws Exception {
        jdbc.execute("ALTER TABLE project_reservoir_surface_loss DROP COLUMN input_condensate_loss_volume");
        jdbc.execute("ALTER TABLE project_reservoir_surface_loss MODIFY COLUMN condensate_volume DOUBLE NOT NULL");
        jdbc.execute("ALTER TABLE project_reservoir_surface_loss MODIFY COLUMN gas_oil_ratio DOUBLE NOT NULL");
        jdbc.update("""
                INSERT INTO project_reservoir_surface_loss
                  (project_id,gas_reservoir_id,record_no,record_name,calculation_mode,input_loss_volume,
                   vent_loss_volume,condensate_volume,gas_oil_ratio,condensate_loss_volume,updated_at)
                VALUES(9,6,1,'legacy',0,12,12,.2,50,10,'2026-09-01 12:00:00')
                """);
        String sql = Files.readString(Path.of("sql/reservoir_surface_loss_direct_upgrade.sql"))
                .replaceAll("(?m)^--.*$", "").replace("USE `database`;", "")
                // H2不支持MySQL在同一个ALTER中修改多个字段，测试中拆成等价的独立ALTER。
                .replaceAll(",\\s*MODIFY COLUMN", "; ALTER TABLE project_reservoir_surface_loss MODIFY COLUMN");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        long id = storage.list(9, 6).getFirst().id();
        var detail = storage.detail(id, 9, 6);
        assertEquals(10, detail.input().inputCondensateLossVolume());
        assertEquals(10, detail.calculation().condensateLossVolume());
        assertEquals(.2, detail.input().condensateVolume());
        assertEquals(java.time.LocalDateTime.of(2026, 9, 1, 12, 0), detail.summary().updatedAt());
    }
}
