package com.grdp.studio.waterinvasion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 只清理旧平台的水侵通知日志；未完成或受保护任务开始后的日志必须保留。 */
@Component
public class WaterInvasionLogRetention {
    private static final Logger LOGGER = LoggerFactory.getLogger(WaterInvasionLogRetention.class);
    private static final long RETENTION_MILLIS = Duration.ofHours(24).toMillis();
    private static final int BATCH_SIZE = 1000;
    private static final int MAX_BATCHES = 100;
    private final JdbcTemplate jdbc;

    public WaterInvasionLogRetention(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "600000", initialDelayString = "60000")
    public void cleanup() {
        try {
            int removed = clean(System.currentTimeMillis());
            if (removed > 0) LOGGER.info("Removed {} expired water-invasion notification logs", removed);
        } catch (RuntimeException error) {
            LOGGER.warn("Could not clean expired water-invasion notification logs", error);
        }
    }

    int clean(long nowMillis) {
        long cutoff = nowMillis - RETENTION_MILLIS;
        LocalDateTime oldestWellTask = jdbc.queryForObject(
            "SELECT MIN(started_at) FROM project_well_water_invasion WHERE active_well_id IS NOT NULL AND started_at IS NOT NULL",
            LocalDateTime.class);
        LocalDateTime oldestStorageTask = jdbc.queryForObject(
            "SELECT MIN(started_at) FROM project_storage_water_invasion WHERE " +
                "(active_storage_id IS NOT NULL OR active_carrier_well_id IS NOT NULL) AND started_at IS NOT NULL",
            LocalDateTime.class);
        for (LocalDateTime started : new LocalDateTime[]{oldestWellTask, oldestStorageTask}) {
            if (started != null) cutoff = Math.min(cutoff, started.toInstant(ZoneOffset.UTC).toEpochMilli());
        }

        int removed = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int count = jdbc.update("DELETE FROM common_notify_logs WHERE module IN (?, ?) AND timestamp < ? LIMIT " + BATCH_SIZE,
                "projectanalysis.waterinvasionanalysis", "WellWaterInvasionAnalysisCalc", cutoff);
            removed += count;
            if (count < BATCH_SIZE) break;
        }
        return removed;
    }
}
