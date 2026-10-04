package com.grdp.studio.integration;

import com.grdp.studio.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;

/**
 * 调用 Go 原平台服务的统一入口。
 *
 * 登录仍由现有前端开发代理处理；这里仅供后续业务服务调用计算接口。
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
                            throw new BusinessException(
                                    502,
                                    "原平台接口调用失败，HTTP " + response.getStatusCode().value()
                            );
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
                            throw new BusinessException(
                                    502,
                                    "原平台接口调用失败，HTTP " + response.getStatusCode().value()
                            );
                        }
                )
                .body(responseType);
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
