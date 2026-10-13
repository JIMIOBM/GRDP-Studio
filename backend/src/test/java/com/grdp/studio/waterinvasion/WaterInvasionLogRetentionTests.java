package com.grdp.studio.waterinvasion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaterInvasionLogRetentionTests {
    private static final long NOW = Instant.parse("2026-10-13T06:00:00Z").toEpochMilli();
    private JdbcTemplate jdbc;
    private WaterInvasionLogRetention retention;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:water_log_retention;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS common_notify_logs");
        jdbc.execute("DROP TABLE IF EXISTS project_well_water_invasion");
        jdbc.execute("DROP TABLE IF EXISTS project_storage_water_invasion");
        jdbc.execute("CREATE TABLE common_notify_logs (id BIGINT PRIMARY KEY, module VARCHAR(255), timestamp BIGINT)");
        jdbc.execute("CREATE TABLE project_well_water_invasion (started_at TIMESTAMP, active_well_id BIGINT)");
        jdbc.execute("CREATE TABLE project_storage_water_invasion (started_at TIMESTAMP, active_storage_id BIGINT, active_carrier_well_id BIGINT)");
        retention = new WaterInvasionLogRetention(jdbc);
    }

    @Test
    void deletesOnlyExpiredWaterInvasionModules() {
        log(1, "projectanalysis.waterinvasionanalysis", 25);
        log(2, "WellWaterInvasionAnalysisCalc", 26);
        log(3, "other.module", 30);
        log(4, "WellWaterInvasionAnalysisCalc", 23);

        assertEquals(2, retention.clean(NOW));
        assertEquals(2, count("SELECT COUNT(*) FROM common_notify_logs"));
        assertEquals(1, count("SELECT COUNT(*) FROM common_notify_logs WHERE id=3"));
        assertEquals(1, count("SELECT COUNT(*) FROM common_notify_logs WHERE id=4"));
    }

    @Test
    void keepsLogsNeededByProtectedWellTask() {
        jdbc.update("INSERT INTO project_well_water_invasion VALUES (?, ?)", utcHoursAgo(30), 13L);
        log(1, "projectanalysis.waterinvasionanalysis", 31);
        log(2, "projectanalysis.waterinvasionanalysis", 29);

        assertEquals(1, retention.clean(NOW));
        assertEquals(1, count("SELECT COUNT(*) FROM common_notify_logs WHERE id=2"));
    }

    @Test
    void keepsLogsNeededByProtectedStorageTask() {
        jdbc.update("INSERT INTO project_storage_water_invasion VALUES (?, ?, ?)", utcHoursAgo(28), 5L, 13L);
        log(1, "WellWaterInvasionAnalysisCalc", 29);
        log(2, "WellWaterInvasionAnalysisCalc", 27);

        assertEquals(1, retention.clean(NOW));
        assertEquals(1, count("SELECT COUNT(*) FROM common_notify_logs WHERE id=2"));
    }

    private void log(long id, String module, long hoursAgo) {
        jdbc.update("INSERT INTO common_notify_logs VALUES (?, ?, ?)", id, module,
            NOW - Duration.ofHours(hoursAgo).toMillis());
    }

    private LocalDateTime utcHoursAgo(long hours) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(NOW - Duration.ofHours(hours).toMillis()), ZoneOffset.UTC);
    }

    private int count(String query) {
        return jdbc.queryForObject(query, Integer.class);
    }
}
