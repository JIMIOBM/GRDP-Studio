package com.grdp.studio.softwareintegration.support;

import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Expires materialized simulator results while retaining run audit metadata and Artifact history. */
@Component
public class SoftwareIntegrationResultCleanup {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoftwareIntegrationResultCleanup.class);
    private final SoftwareIntegrationRunStore runStore;

    public SoftwareIntegrationResultCleanup(SoftwareIntegrationRunStore runStore) {
        this.runStore = runStore;
    }

    @Scheduled(
            fixedDelayString = "${grdp.software-integration.result-cleanup-delay:3600000}",
            initialDelayString = "${grdp.software-integration.result-cleanup-initial-delay:60000}")
    public void cleanupExpiredResults() {
        try {
            int cleaned = runStore.clearExpiredResults(LocalDateTime.now());
            if (cleaned > 0) LOGGER.info("Expired {} software-integration result payload(s)", cleaned);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to clean expired software-integration result payloads", exception);
        }
    }
}
