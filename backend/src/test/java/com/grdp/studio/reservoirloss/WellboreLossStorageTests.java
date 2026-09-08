package com.grdp.studio.reservoirloss;

import com.grdp.studio.reservoirloss.dto.WellboreLossDtos.*;
import com.grdp.studio.reservoirloss.service.WellboreLossStorageService;
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
import com.grdp.studio.reservoirloss.controller.WellboreLossController;
import com.grdp.studio.reservoirloss.controller.GeologicalLossController;
import com.grdp.studio.reservoirloss.service.WellboreLossCalculationService;
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
class WellboreLossStorageTests {
    JdbcTemplate jdbc;
    WellboreLossStorageService storage;

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        String sql = Files.readString(Path.of("sql/reservoir_wellbore_loss.sql"))
                .replaceAll("(?m)^--.*$", "").replace("USE `database`;", "")
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        storage = new WellboreLossStorageService(jdbc, new DataSourceTransactionManager(source));
    }

    static WellboreInput formula(double volume) {
        return new WellboreInput("formula", null, 313.15, 20d, 10d,
                List.of(new SegmentInput(volume), new SegmentInput(50)), 0, .7336,
                14.62, 8.96, 0d, 0, 0, 0, "pvt.xls");
    }

    static Calculation output(double total) {
        return new Calculation(1350L, .9, .95, total,
                1e-4 * total * 293.15 / (.101325 * 313.15) * (20 / .9 - 10 / .95));
    }

    @Test
    void formulaRoundTripUpdateScopeAndCascadeDelete() {
        var saved = storage.save(new SaveRequest(null, 7, 4, formula(100), output(150)));
        var detail = storage.detail(saved.id(), 7, 4);
        assertEquals(2, detail.input().segments().size());
        assertEquals(150, detail.calculation().totalSegmentVolume());
        assertEquals("pvt.xls", detail.input().importedFileName());
        assertTrue(storage.list(7, 5).isEmpty());
        assertThrows(ResponseStatusException.class, () -> storage.detail(saved.id(), 7, 5));
        assertThrows(ResponseStatusException.class, () -> storage.rename(saved.id(), 8, 4, "other"));
        assertThrows(ResponseStatusException.class, () -> storage.delete(saved.id(), 7, 5));
        assertEquals("修改名称", storage.rename(saved.id(), 7, 4, "修改名称").recordName());

        var direct = new WellboreInput("direct", 12d, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        storage.save(new SaveRequest(saved.id(), 7, 4, direct, new Calculation(null, null, null, null, 12)));
        assertTrue(storage.detail(saved.id(), 7, 4).input().segments().isEmpty());
        assertNull(storage.detail(saved.id(), 7, 4).input().averageTemperatureK());
        storage.save(new SaveRequest(saved.id(), 7, 4, formula(80), output(130)));
        assertEquals(80, storage.detail(saved.id(), 7, 4).input().segments().get(0).segmentVolume());
        storage.delete(saved.id(), 7, 4);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM project_reservoir_wellbore_loss_segment", Integer.class));
    }

    @Test
    void failedDetailReplacementRollsBackMainAndPreviousSegments() {
        var saved = storage.save(new SaveRequest(null, 7, 4, formula(100), output(150)));
        jdbc.execute("ALTER TABLE project_reservoir_wellbore_loss_segment ADD CONSTRAINT test_volume CHECK(segment_volume <> 123)");
        assertThrows(DataIntegrityViolationException.class,
                () -> storage.save(new SaveRequest(saved.id(), 7, 4, formula(123), output(173))));
        assertEquals(100, storage.detail(saved.id(), 7, 4).input().segments().get(0).segmentVolume());
        assertEquals(150, storage.detail(saved.id(), 7, 4).calculation().totalSegmentVolume());
    }

    @Test
    void rejectsStaleOrTamperedCalculation() {
        var result = output(150);
        assertThrows(ResponseStatusException.class, () -> storage.save(new SaveRequest(null, 7, 4,
                formula(100), new Calculation(1350L, .9, .95, 150d, result.wellboreLossVolume() + 10))));
        assertTrue(storage.list(7, 4).isEmpty());
    }

    @Test
    void httpSaveRecalculatesAndRoutesAlongsideGeologicalEndpoints() throws Exception {
        var pvt = mock(GasPvtService.class);
        var controller = new WellboreLossController(new WellboreLossCalculationService(pvt), storage);
        var mvc = MockMvcBuilders.standaloneSetup(controller,
                new GeologicalLossController(mock(GeologicalLossCalculationService.class), mock(GeologicalLossStorageService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        // 客户端故意提交999，实际保存结果必须由服务端根据直接输入12重新核算。
        String json = """
                {"projectId":7,"gasReservoirId":4,"input":{"calculationMode":"direct","inputLossVolume":12},
                 "calculation":{"wellboreLossVolume":999}}
                """;
        String body = mvc.perform(post("/reservoir-loss/wellbore/save")
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = new ObjectMapper().readTree(body).get("data").get("id").longValue();
        assertEquals(12, storage.detail(id, 7, 4).calculation().wellboreLossVolume());
        verifyNoInteractions(pvt);
        mvc.perform(get("/reservoir-loss/wellbore/records").param("projectId", "7").param("gasReservoirId", "4"))
                .andExpect(status().isOk());
        mvc.perform(patch("/reservoir-loss/wellbore/" + id + "/name").param("projectId", "8").param("gasReservoirId", "4")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"wrong scope\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/reservoir-loss/wellbore/" + id).param("projectId", "7").param("gasReservoirId", "4"))
                .andExpect(status().isOk());
    }
}
