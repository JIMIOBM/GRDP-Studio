package com.grdp.studio.integration;

import com.grdp.studio.common.BusinessException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 调用 Go 原平台服务的统一入口。
 *
 * <p>登录仍由现有前端开发代理处理；这里仅供后续业务服务调用计算接口。
 *
 * <p>失败时会把原平台返回的**响应体**一并带进异常消息：原平台的业务错误是
 * {@code {"status":400,"msg":"H₂S摩尔范围[0, 1]"}} 这种形式，只报 HTTP 状态码
 * 会让每个调用方都失去唯一能说明原因的线索。
 */
@Service
public class OriginalPlatformClient {

    private final RestClient restClient;

    public OriginalPlatformClient(RestClient originalPlatformRestClient) {
        this.restClient = originalPlatformRestClient;
    }

    public <T> T get(String relativePath, Class<T> responseType) {
        return get(relativePath, responseType, Map.of());
    }

    public <T> T get(String relativePath, Class<T> responseType, Map<String, String> headers) {
        String path = validateRelativePath(relativePath);
        return restClient.get()
                .uri(path)
                .headers(httpHeaders -> applyHeaders(httpHeaders, headers))
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        (request, response) -> {
                            throw new BusinessException(502, describeFailure(response));
                        }
                )
                .body(responseType);
    }

    public <B, T> T post(String relativePath, B body, Class<T> responseType) {
        return post(relativePath, body, responseType, Map.of());
    }

    public <B, T> T post(
            String relativePath,
            B body,
            Class<T> responseType,
            Map<String, String> headers
    ) {
        String path = validateRelativePath(relativePath);
        return restClient.post()
                .uri(path)
                .headers(httpHeaders -> applyHeaders(httpHeaders, headers))
                .body(body)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        (request, response) -> {
                            throw new BusinessException(502, describeFailure(response));
                        }
                )
                .body(responseType);
    }

    /** 把状态码和原平台自己的说明合成一条可诊断的消息。 */
    private static String describeFailure(ClientHttpResponse response) {
        // getStatusCode() 本身也可能抛 IOException，所以整段都要兜住。
        String status = "?";
        try {
            status = String.valueOf(response.getStatusCode().value());
        } catch (Exception ignored) {
            // 拿不到状态码就用占位符，不影响抛出异常
        }
        String message = "原平台接口调用失败，HTTP " + status;
        try (InputStream stream = response.getBody()) {
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!text.isEmpty()) {
                message += "：" + (text.length() > 500 ? text.substring(0, 500) + "…" : text);
            }
        } catch (Exception ignored) {
            // 读不到响应体不影响报错本身；原平台 401 就是空 body。
        }
        return message;
    }

    private void applyHeaders(HttpHeaders target, Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        headers.forEach((name, value) -> {
            if (name != null && value != null && !value.isBlank()) {
                target.set(name, value);
            }
        });
    }

    private String validateRelativePath(String relativePath) {
        String path = relativePath == null ? "" : relativePath.trim();
        if (path.isEmpty() || URI.create(path).isAbsolute() || path.startsWith("//")) {
            throw new IllegalArgumentException("必须提供原平台的相对接口路径");
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
