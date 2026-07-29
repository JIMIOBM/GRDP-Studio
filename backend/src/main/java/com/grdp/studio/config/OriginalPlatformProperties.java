package com.grdp.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "grdp.integration.original-platform")
public record OriginalPlatformProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
}
