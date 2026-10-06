package com.grdp.studio.softwareintegration.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/** Team sharing means verified logged-in identities, not trusting a frontend token/user ID. */
@Component
public class TemplateCreationSession {
    private final URI whoami;
    private final Set<String> cookieNames;
    private final Set<String> origins;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public TemplateCreationSession(ObjectMapper mapper,
            @Value("${GRDP_TEMPLATE_SESSION_URL:http://127.0.0.1:9919/services/ory/kratos/sessions/whoami}") String url,
            @Value("${GRDP_TEMPLATE_SESSION_COOKIES:ahksoil_identity_session,grdp_identity_session}") String names,
            @Value("${grdp.cors.allowed-origins:http://127.0.0.1:5173,http://localhost:5173}") String allowedOrigins) {
        this.mapper = mapper; this.whoami = URI.create(url);
        this.cookieNames = Arrays.stream(names.split(",")).map(String::trim).collect(Collectors.toSet());
        this.origins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).collect(Collectors.toSet());
    }

    public void requireMember(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site")) || origin != null && !origins.contains(origin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不允许跨站操作共享项目");
        }
        String cookie = Arrays.stream(String.valueOf(request.getHeader("Cookie")).split(";"))
                .map(String::trim).filter(pair -> {
                    int separator = pair.indexOf('=');
                    return separator > 0 && separator < pair.length() - 1 && cookieNames.contains(pair.substring(0, separator));
                }).collect(Collectors.joining("; "));
        if (cookie.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录平台");
        try {
            var pending = http.sendAsync(HttpRequest.newBuilder(whoami).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json").header("Cookie", cookie).GET().build(),
                    ignored -> new SessionBody());
            HttpResponse<byte[]> response;
            try { response = pending.get(10, TimeUnit.SECONDS); }
            catch (Exception exception) { pending.cancel(true); throw exception; }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
            }
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "登录校验服务不可用");
            }
            var session = mapper.readTree(response.body());
            if (!session.path("active").isBoolean() || !session.path("active").booleanValue()
                    || !session.path("identity").path("id").isString()
                    || session.path("identity").path("id").asText().isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录会话未激活");
            }
        } catch (ResponseStatusException exception) { throw exception; }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "登录校验被中断");
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法验证登录，请检查认证服务配置");
        }
    }

    /** Limit before buffering; the outer future deadline also covers a stalled response body. */
    private static final class SessionBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > 64 * 1024 - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Session response exceeds limit")); return;
                }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable exception) { result.completeExceptionally(exception); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
