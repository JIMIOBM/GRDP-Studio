package com.grdp.studio.softwareintegration.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("grdp.software-integration")
public class SoftwareIntegrationProperties {

    private String storageRoot = "C:/GRDP-Data";
    private String workerBaseUrl = "http://127.0.0.1:5150";
    private boolean dispatcherEnabled = true;
    private long maxUploadBytes = 524_288_000L;
    private int defaultRunTimeoutSeconds = 600;
    private Duration workerConnectTimeout = Duration.ofSeconds(10);
    private Duration workerReadTimeout = Duration.ofSeconds(30);
    private Duration acceptanceRecoveryWindow = Duration.ofSeconds(30);
    private Duration workerBusyBackoff = Duration.ofSeconds(2);
    private long maxArtifactBytes = 1_073_741_824L;
    private long maxArtifactTotalBytes = 2_147_483_648L;
    private int maxArchiveEntries = 4096;
    private int maxArchiveDepth = 32;
    private long maxArchiveEntryBytes = 1_073_741_824L;
    private long maxArchiveExpandedBytes = 2_147_483_648L;
    private Duration validationSweepDelay = Duration.ofSeconds(30);
    private Duration validationSweepInitialDelay = Duration.ofSeconds(10);
    private Duration validationRecoveryAfter = Duration.ofMinutes(10);
    private Duration validationLease = Duration.ofMinutes(5);
    private Duration validationRetryBackoff = Duration.ofSeconds(30);
    private int validationMaxAttempts = 5;
    private int projectRetentionDays = 30;
    private Duration projectCleanupDelay = Duration.ofHours(1);
    private Duration projectCleanupInitialDelay = Duration.ofMinutes(1);

    public String getStorageRoot() { return storageRoot; }
    public void setStorageRoot(String storageRoot) { this.storageRoot = storageRoot; }
    public String getWorkerBaseUrl() { return workerBaseUrl; }
    public void setWorkerBaseUrl(String workerBaseUrl) { this.workerBaseUrl = workerBaseUrl; }
    public boolean isDispatcherEnabled() { return dispatcherEnabled; }
    public void setDispatcherEnabled(boolean dispatcherEnabled) { this.dispatcherEnabled = dispatcherEnabled; }
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
    public int getMaxArchiveEntries() { return maxArchiveEntries; }
    public void setMaxArchiveEntries(int maxArchiveEntries) { this.maxArchiveEntries = maxArchiveEntries; }
    public int getMaxArchiveDepth() { return maxArchiveDepth; }
    public void setMaxArchiveDepth(int maxArchiveDepth) { this.maxArchiveDepth = maxArchiveDepth; }
    public long getMaxArchiveEntryBytes() { return maxArchiveEntryBytes; }
    public void setMaxArchiveEntryBytes(long maxArchiveEntryBytes) { this.maxArchiveEntryBytes = maxArchiveEntryBytes; }
    public long getMaxArchiveExpandedBytes() { return maxArchiveExpandedBytes; }
    public void setMaxArchiveExpandedBytes(long maxArchiveExpandedBytes) { this.maxArchiveExpandedBytes = maxArchiveExpandedBytes; }
    public Duration getValidationSweepDelay() { return validationSweepDelay; }
    public void setValidationSweepDelay(Duration validationSweepDelay) { this.validationSweepDelay = validationSweepDelay; }
    public Duration getValidationSweepInitialDelay() { return validationSweepInitialDelay; }
    public void setValidationSweepInitialDelay(Duration validationSweepInitialDelay) { this.validationSweepInitialDelay = validationSweepInitialDelay; }
    public Duration getValidationRecoveryAfter() { return validationRecoveryAfter; }
    public void setValidationRecoveryAfter(Duration validationRecoveryAfter) { this.validationRecoveryAfter = validationRecoveryAfter; }
    public Duration getValidationLease() { return validationLease; }
    public void setValidationLease(Duration validationLease) { this.validationLease = validationLease; }
    public Duration getValidationRetryBackoff() { return validationRetryBackoff; }
    public void setValidationRetryBackoff(Duration validationRetryBackoff) { this.validationRetryBackoff = validationRetryBackoff; }
    public int getValidationMaxAttempts() { return validationMaxAttempts; }
    public void setValidationMaxAttempts(int validationMaxAttempts) { this.validationMaxAttempts = validationMaxAttempts; }
    public int getProjectRetentionDays() { return projectRetentionDays; }
    public void setProjectRetentionDays(int projectRetentionDays) { this.projectRetentionDays = projectRetentionDays; }
    public Duration getProjectCleanupDelay() { return projectCleanupDelay; }
    public void setProjectCleanupDelay(Duration projectCleanupDelay) { this.projectCleanupDelay = projectCleanupDelay; }
    public Duration getProjectCleanupInitialDelay() { return projectCleanupInitialDelay; }
    public void setProjectCleanupInitialDelay(Duration projectCleanupInitialDelay) { this.projectCleanupInitialDelay = projectCleanupInitialDelay; }
}
