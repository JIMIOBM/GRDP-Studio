package com.grdp.studio.softwareintegration.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("grdp.software-integration")
public class SoftwareIntegrationProperties {

    private String storageRoot = "C:/GRDP-Data";
    private String workerBaseUrl = "http://127.0.0.1:5150";
    private long maxUploadBytes = 524_288_000L;
    private int defaultRunTimeoutSeconds = 600;
    private Duration workerConnectTimeout = Duration.ofSeconds(10);
    private Duration workerReadTimeout = Duration.ofSeconds(30);
    private Duration acceptanceRecoveryWindow = Duration.ofSeconds(30);
    private Duration workerBusyBackoff = Duration.ofSeconds(2);
    private long maxArtifactBytes = 1_073_741_824L;
    private long maxArtifactTotalBytes = 2_147_483_648L;

    public String getStorageRoot() { return storageRoot; }
    public void setStorageRoot(String storageRoot) { this.storageRoot = storageRoot; }
    public String getWorkerBaseUrl() { return workerBaseUrl; }
    public void setWorkerBaseUrl(String workerBaseUrl) { this.workerBaseUrl = workerBaseUrl; }
    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }
    public int getDefaultRunTimeoutSeconds() { return defaultRunTimeoutSeconds; }
    public void setDefaultRunTimeoutSeconds(int defaultRunTimeoutSeconds) { this.defaultRunTimeoutSeconds = defaultRunTimeoutSeconds; }
    public Duration getWorkerConnectTimeout() { return workerConnectTimeout; }
    public void setWorkerConnectTimeout(Duration workerConnectTimeout) { this.workerConnectTimeout = workerConnectTimeout; }
    public Duration getWorkerReadTimeout() { return workerReadTimeout; }
    public void setWorkerReadTimeout(Duration workerReadTimeout) { this.workerReadTimeout = workerReadTimeout; }
    public Duration getAcceptanceRecoveryWindow() { return acceptanceRecoveryWindow; }
    public void setAcceptanceRecoveryWindow(Duration acceptanceRecoveryWindow) { this.acceptanceRecoveryWindow = acceptanceRecoveryWindow; }
    public Duration getWorkerBusyBackoff() { return workerBusyBackoff; }
    public void setWorkerBusyBackoff(Duration workerBusyBackoff) { this.workerBusyBackoff = workerBusyBackoff; }
    public long getMaxArtifactBytes() { return maxArtifactBytes; }
    public void setMaxArtifactBytes(long maxArtifactBytes) { this.maxArtifactBytes = maxArtifactBytes; }
    public long getMaxArtifactTotalBytes() { return maxArtifactTotalBytes; }
    public void setMaxArtifactTotalBytes(long maxArtifactTotalBytes) { this.maxArtifactTotalBytes = maxArtifactTotalBytes; }
}
