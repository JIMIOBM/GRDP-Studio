package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.pvtstorage.dto.PvtRecordDetail;
import com.grdp.studio.pvtstorage.dto.PvtRecordSummary;
import com.grdp.studio.pvtstorage.service.PvtStorageService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PipelineErosionLiquidSourceTests {
    private final ObjectMapper json = new ObjectMapper();
    private final PvtStorageService storage = mock(PvtStorageService.class);

    @BeforeEach void currentWellSourceList() {
        when(storage.list(1, 2, "井1")).thenReturn(List.of(new PvtRecordSummary(9, 1, "地层水样", "saved", "manual", "water")));
    }

    private PvtRecordDetail waterOnly(double salinity, String settings) {
        return new PvtRecordDetail(new PvtRecordSummary(9, 1, "地层水样", "saved", "manual", "water"),
                null, new PvtRecordDetail.WaterInput(20d, 39d, salinity), null,
                settings == null ? Map.of() : Map.of("water", settings), List.of(), List.of(), List.of());
    }

    private PipelineErosionLiquidSource source(RestClient client) {
        return new PipelineErosionLiquidSource(storage, client, json);
    }

    @Test void waterOnlyPvtIsValidWithoutAnyGasInputAndLocalStateDoesNotAffectSnapshot() {
        when(storage.getDetail(9, 1, 2, "井1")).thenReturn(waterOnly(25000, null));
        var service = source(RestClient.create());
        var snapshot = service.snapshot(1, 2, " 井1 ", 9L);
        assertNull(snapshot.issue()); assertEquals(25000, snapshot.salinityMgL());
        assertEquals(20, snapshot.originalPressureMpa());
        assertEquals(0, snapshot.volumeFactorMethod()); assertEquals(0, snapshot.compressibilityMethod());
        assertTrue(service.verifySnapshot(snapshot));
        when(storage.getDetail(9, 1, 2, "井1")).thenReturn(waterOnly(30000, null));
        assertFalse(service.verifySnapshot(snapshot), "Changing the liquid source invalidates the calculation");
    }

    @Test void cosmeticNamesAndUnrelatedGasSettingsDoNotInvalidateLiquidDensity() {
        var original = waterOnly(25000, "{\"volumeFactorMethod\":\"Standing方法\",\"compressibilityMethod\":\"Dodson-Standing方法\"}");
        when(storage.getDetail(9, 1, 2, "井1")).thenReturn(original);
        var service = source(RestClient.create()); var snapshot = service.snapshot(1, 2, "井1", 9L);
        assertEquals(1, snapshot.volumeFactorMethod()); assertEquals(1, snapshot.compressibilityMethod());
        var renamed = new PvtRecordDetail(new PvtRecordSummary(9, 1, "重命名水样", "saved", "manual", "gas"),
                new PvtRecordDetail.GasInput("干气", .7, 0d, 1d, 2d, null), original.waterInput(), null,
                Map.of("gas", "{\"viscosityMethod\":\"Sutton 方法\"}", "water", original.settings().get("water")),
                List.of(), List.of(), List.of());
        when(storage.getDetail(9, 1, 2, "井1")).thenReturn(renamed);
        assertTrue(service.verifySnapshot(snapshot));
    }

    @Test void missingIncompleteOrForeignSourcesRemainExplicitlyUnevaluated() {
        var service = source(RestClient.create());
        var absent = service.snapshot(1, 2, "井1", null);
        assertNotNull(absent.issue()); verifyNoInteractions(storage);
        assertThrows(BusinessException.class, () -> service.open(absent, null, null, null).apply(5d, 40d));
        when(storage.getDetail(9, 1, 2, "井1")).thenReturn(waterOnly(-1, null));
        assertTrue(service.snapshot(1, 2, "井1", 9L).issue().contains("矿化度"));
        when(storage.getDetail(9, 1, 2, "井1")).thenThrow(new BusinessException(403, "PVT不属于当前井"));
        assertTrue(service.snapshot(1, 2, "井1", 9L).issue().contains("当前井"));
        doReturn(waterOnly(0, "{broken")).when(storage).getDetail(9, 1, 2, "井1");
        assertTrue(service.snapshot(1, 2, "井1", 9L).issue().contains("格式无效"));
    }

    @Test void remoteWaterDensityUsesActualLocalPressureTemperatureAndAuthWithOneToolboxAndExactCache() throws Exception {
        when(storage.getDetail(9, 1, 2, "井1")).thenReturn(waterOnly(25000, null));
        AtomicInteger creates = new AtomicInteger(), calculations = new AtomicInteger();
        AtomicReference<Map<String, Object>> latest = new AtomicReference<>();
        List<String> headerErrors = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/toolbox", exchange -> {
            for (var expected : Map.of("token", "test-token", "Cookie", "test-cookie=1", "Process-Env", "test", "x-project-id", "1").entrySet()) {
                if (!expected.getValue().equals(exchange.getRequestHeaders().getFirst(expected.getKey()))) headerErrors.add(expected.getKey());
            }
            String path = exchange.getRequestURI().getPath(), body;
            if (path.equals("/api/toolbox")) {
                creates.incrementAndGet(); body = "{\"id\":99}";
            } else if (path.endsWith("/calc")) {
                calculations.incrementAndGet();
                var request = json.readTree(exchange.getRequestBody().readAllBytes());
                latest.set(json.readValue(request.get("input").asString(), Map.class)); body = "{}";
            } else {
                var input = latest.get();
                double density = 1000 + ((Number) input.get("pressure")).doubleValue() - ((Number) input.get("temperature")).doubleValue();
                body = "{\"result\":{\"waterDensity\":" + density + "}}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var service = source(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build());
            var session = service.open(service.snapshot(1, 2, "井1", 9L), "test-token", "test-cookie=1", "test");
            assertEquals(965, session.apply(5d, 40d));
            assertEquals(965, session.apply(5d, 40d));
            assertEquals(955, session.apply(6d, 51d));
            assertEquals(1, creates.get()); assertEquals(2, calculations.get()); assertTrue(headerErrors.isEmpty(), headerErrors.toString());
            assertEquals(25000d, ((Number) latest.get().get("salinity")).doubleValue());
            assertEquals(20d, ((Number) latest.get().get("originalPressure")).doubleValue());
        } finally { server.stop(0); }
    }

    @Test void authenticationOutageStopsRepeatedCallsButPointDomainErrorsDoNotPoisonOtherStates() throws Exception {
        when(storage.getDetail(9, 1, 2, "井1")).thenReturn(waterOnly(0, null));
        AtomicInteger calls = new AtomicInteger(); AtomicInteger status = new AtomicInteger(401);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/toolbox", exchange -> {
            calls.incrementAndGet();
            String body = exchange.getRequestURI().getPath().endsWith("/99") ? "{\"density\":1000}" : "{\"id\":99}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var service = source(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build());
            var snapshot = service.snapshot(1, 2, "井1", 9L);
            var unavailable = service.open(snapshot, null, null, null);
            assertThrows(BusinessException.class, () -> unavailable.apply(5d, 40d));
            assertThrows(BusinessException.class, () -> unavailable.apply(6d, 30d));
            assertEquals(1, calls.get());
            status.set(400);
            var pointFailure = service.open(snapshot, null, null, null);
            assertThrows(BusinessException.class, () -> pointFailure.apply(5d, 40d));
            status.set(200);
            assertEquals(1000, pointFailure.apply(6d, 30d));
            assertEquals(5, calls.get());
        } finally { server.stop(0); }
    }
}
