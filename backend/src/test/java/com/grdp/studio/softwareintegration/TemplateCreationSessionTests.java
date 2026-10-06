package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.service.TemplateCreationSession;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class TemplateCreationSessionTests {
    private HttpServer server;
    private TemplateCreationSession sessions;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> body = new AtomicReference<>("{\"active\":true,\"identity\":{\"id\":\"team-member\"}}");
    private final AtomicReference<String> cookie = new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/whoami", exchange -> {
            calls.incrementAndGet(); cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        sessions = new TemplateCreationSession(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort() + "/whoami",
                "grdp_identity_session", "http://localhost:5173");
    }
    @AfterEach void cleanup() { server.stop(0); }
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.addHeader("Cookie", "unrelated=private; grdp_identity_session=verified-session");
        request.addHeader("Origin", "http://localhost:5173"); return request;
    }
    @Test void activeIdentityCanAccessSharedProjectsAndOnlySessionCookieIsForwarded() {
        sessions.requireMember(request()); assertThat(cookie.get()).isEqualTo("grdp_identity_session=verified-session");
    }
    @Test void frontendTokenDoesNotAuthorizeAndNoCookieDoesNotContactAuthentication() {
        var request = new MockHttpServletRequest(); request.addHeader("token", "invented");
        assertStatus(request, 401); assertThat(calls.get()).isZero();
    }
    @Test void crossSiteOrUnapprovedOriginIsRejectedBeforeAuthentication() {
        var request = request(); request.addHeader("Sec-Fetch-Site", "cross-site"); assertStatus(request, 403);
        var other = new MockHttpServletRequest(); other.addHeader("Origin", "http://evil.invalid"); assertStatus(other, 403);
        assertThat(calls.get()).isZero();
    }
    @Test void inactiveMissingAndStringActiveIdentityAreRejected() {
        for (String invalid : new String[]{"{\"active\":false,\"identity\":{\"id\":\"x\"}}", "{\"active\":true}", "{\"active\":\"true\",\"identity\":{\"id\":\"x\"}}"}) {
            body.set(invalid); assertStatus(request(), 401);
        }
    }
    @Test void authFailureAndOversizeBodyFailClosed() {
        status.set(401); assertStatus(request(), 401);
        status.set(503); assertStatus(request(), 502);
        status.set(200); body.set(" ".repeat(65537)); assertStatus(request(), 502);
    }
    private void assertStatus(MockHttpServletRequest request, int expected) {
        assertThatThrownBy(() -> sessions.requireMember(request)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(expected));
    }
}
