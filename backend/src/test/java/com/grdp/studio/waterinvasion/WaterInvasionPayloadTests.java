package com.grdp.studio.waterinvasion;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WaterInvasionPayloadTests {
    private static final WaterInvasionModels.Scope SCOPE = new WaterInvasionModels.Scope(6, 3, "X-1");

    @Test
    void convertsSnapshotTimestampsToLegacyCalculationDates() {
        var production = Map.<String,Object>of(
            "date", "2004-04-21T00:00:00+08:00", "pressure", 27.5,
            "dailyGasProduction", 12.918, "cumulativeGasProduction", 0.0012918,
            "cumulativeWaterProduction", 0.0003522, "cumulativeOilProduction", 0);
        var identification = Map.<String,Object>of(
            "date", "2004-04-20T16:00:00Z", "pressure", 27.5,
            "cumulativeGasProduction", 0.0012918, "cumulativeWaterProduction", 0.0003522,
            "cumulativeOilProduction", 0);
        var snapshot = Map.<String,Object>of(
            "input", Map.of("gasType", "dry", "waterGasRatioLimit", -1),
            "identifyInputItems", List.of(identification), "influxInputItems", List.of(production));

        var payload = WaterInvasionPayload.fromSnapshot(SCOPE, snapshot, -1);
        assertEquals("2004-04-21", WaterInvasionResultMapper.objects(payload.get("identifyInputItems")).getFirst().get("date"));
        assertEquals("2004-04-21", WaterInvasionResultMapper.objects(payload.get("influxInputItems")).getFirst().get("date"));
        assertEquals(12.918, WaterInvasionResultMapper.objects(payload.get("influxInputItems")).getFirst().get("dailyGasProduction"));
        assertEquals(0, WaterInvasionResultMapper.objects(payload.get("influxInputItems")).getFirst().get("cumulativeOilProduction"));
        assertNull(payload.get("analysisId"));
        var response = Map.<String,Object>of(
            "wellName", "X-1", "input", payload.get("input"),
            "identifyInputItems", List.of(identification), "influxInputItems", List.of(production));
        assertTrue(WaterInvasionPayload.matches(payload, response));
    }

    @Test
    void refusesInvalidHistoricalDatesBeforeSubmittingToLegacyPlatform() {
        var snapshot = Map.<String,Object>of(
            "input", Map.of(), "identifyInputItems", List.of(),
            "influxInputItems", List.of(Map.of("date", "invalid")));

        var error = assertThrows(ResponseStatusException.class,
            () -> WaterInvasionPayload.fromSnapshot(SCOPE, snapshot, -1));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("无效日期"));
    }
}
