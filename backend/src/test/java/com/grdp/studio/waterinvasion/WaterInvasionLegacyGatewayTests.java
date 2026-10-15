package com.grdp.studio.waterinvasion;

import com.grdp.studio.config.OriginalPlatformProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class WaterInvasionLegacyGatewayTests {
    private static final int PAGE_SIZE = 2000;
    private static final long START = 1_000L;
    private static final WaterInvasionModels.Scope SCOPE = new WaterInvasionModels.Scope(6, 3, "X-1");
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void readsCompletionLogBeyondFirstFullPage() throws Exception {
        var first = Collections.nCopies(PAGE_SIZE, log("其他井开始", "other"));
        var last = List.of(log("X-1 水侵动态分析开始", "task-1"), log("[水侵动态分析-水体活跃性]: 完成", "task-1"));
        try (var server = new LogServer(page -> page == 1 ? first : last)) {
            assertTrue(gateway(server).completedSince(START, SCOPE, "session=test"));
            assertEquals(List.of(1, 2), server.pages);
        }
    }

    @Test
    void exactlyOneFullPageIsNotAnOverflow() throws Exception {
        var first = Collections.nCopies(PAGE_SIZE, log("其他井开始", "other"));
        try (var server = new LogServer(page -> page == 1 ? first : List.of())) {
            assertFalse(gateway(server).completedSince(START, SCOPE, "session=test"));
            assertEquals(List.of(1, 2), server.pages);
        }
    }

    @Test
    void stopsOnTheFirstPageOnceCompletionIsConfirmed() throws Exception {
        var first = new ArrayList<>(Collections.nCopies(PAGE_SIZE - 2, log("其他井开始", "other")));
        first.add(log("X-1 水侵动态分析开始", "task-1"));
        first.add(log("[水侵动态分析-水体活跃性]: 完成", "task-1"));
        try (var server = new LogServer(page -> first)) {
            assertTrue(gateway(server).completedSince(START, SCOPE, "session=test"));
            assertEquals(List.of(1), server.pages);
        }
    }

    @Test
    void refusesToTrustAnEndpointThatIgnoresPageNumber() throws Exception {
        var first = Collections.nCopies(PAGE_SIZE, log("其他井开始", "other"));
        try (var server = new LogServer(page -> first)) {
            var error = assertThrows(ResponseStatusException.class, () -> gateway(server).completedSince(START, SCOPE, "session=test"));
            assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
            assertTrue(error.getReason().contains("分页未前进"));
            assertEquals(List.of(1, 2), server.pages);
        }
    }

    private WaterInvasionLegacyGateway gateway(LogServer server) {
        var base = "http://127.0.0.1:" + server.port();
        return new WaterInvasionLegacyGateway(new OriginalPlatformProperties(base, Duration.ofSeconds(1), Duration.ofSeconds(5)), json, base, "");
    }

    private static Map<String,Object> log(String message, String pin) {
        return Map.of("module", "projectanalysis.waterinvasionanalysis", "timestamp", START + 1,
            "message", message, "pin", pin, "fields", Map.of("projectId", 6, "gasReservoirId", 3));
    }

    private final class LogServer implements AutoCloseable {
        private final HttpServer server;
        private final List<Integer> pages = new CopyOnWriteArrayList<>();

        private LogServer(IntFunction<List<Map<String,Object>>> rows) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/common/notify/logs", exchange -> {
                var matcher = Pattern.compile("(?:^|&)page=(\\d+)(?:&|$)").matcher(exchange.getRequestURI().getRawQuery());
                int page = matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
                pages.add(page);
                var body = json.writeValueAsString(Map.of("logs", rows.apply(page))).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        }

        private int port() { return server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
