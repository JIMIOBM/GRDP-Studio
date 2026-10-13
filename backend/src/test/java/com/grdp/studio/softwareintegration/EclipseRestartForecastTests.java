package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseRestartForecastParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class EclipseRestartForecastTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode source() throws Exception {
        try (var input = getClass().getResourceAsStream("/eclipse-restart-source-catalog.json")) {
            assertThat(input).isNotNull(); return (ObjectNode)mapper.readTree(input);
        }
    }
    private ObjectNode parameters(ObjectNode source) {
        var catalog = source.path("catalog"); var report = catalog.path("reports").get(80);
        return mapper.createObjectNode().put("schemaVersion", EclipseRestartForecastParameters.SCHEMA).put("historyRunId", 1)
                .put("forecastDataFile", "FOREA.DATA").put("restartArtifactName", catalog.path("artifactName").asText())
                .put("restartArtifactSha256", catalog.path("artifactSha256").asText()).put("restartReport", 80)
                .put("restartTimeDays", report.path("timeDays").asLong()).put("restartDate", report.path("date").asText())
                .put("physicsSha256", source.path("physicsSha256").asText());
    }
    private ObjectNode inspection(ObjectNode source) {
        var i = mapper.createObjectNode(); var files = i.putArray("packageFiles");
        for (var candidate : source.path("forecastInputs")) files.addObject().put("relativePath", candidate.path("relativePath").asText()).put("sha256", candidate.path("sha256").asText());
        return i;
    }
    private boolean matches(ObjectNode p, ObjectNode s, ObjectNode i) {
        var c = s.path("catalog"); return EclipseRestartForecastParameters.matchesCatalog(p, s, i,
                c.path("artifactName").asText(), c.path("artifactSha256").asText(), c.path("artifactSizeBytes").asLong());
    }
    @Test void actualWorkerCatalogBinds81ReportsAndBothNativeForecasts() throws Exception {
        var s = source(); assertThat(EclipseRestartForecastParameters.validCatalog(s)).isTrue();
        assertThat(s.path("catalog").path("reports").size()).isEqualTo(81); assertThat(s.path("forecastInputs").size()).isEqualTo(2);
        var p = parameters(s); assertThat(matches(p, s, inspection(s))).isTrue();
        p.put("forecastDataFile", "FOREB.DATA"); assertThat(matches(p, s, inspection(s))).isTrue();
    }
    @Test void wrongReportDateTimeCasePhysicsAndInputCannotBind() throws Exception {
        for (var key : new String[] {"restartReport", "restartDate", "restartTimeDays", "restartArtifactName", "restartArtifactSha256", "physicsSha256", "forecastDataFile"}) {
            var s = source(); var p = parameters(s);
            switch (key) { case "restartReport" -> p.put(key, 79); case "restartTimeDays" -> p.put(key, 2191);
                case "restartDate" -> p.put(key, "1975-12-30"); case "restartArtifactName" -> p.put(key, "eclipse-output-OTHER.FUNRST");
                case "forecastDataFile" -> p.put(key, "OTHER.DATA"); default -> p.put(key, "b".repeat(64)); }
            assertThat(matches(p, s, inspection(s))).as(key).isFalse();
        }
    }
    @Test void modelPackageInputHashMustMatchCatalog() throws Exception {
        var s = source(); var i = inspection(s); ((ObjectNode)i.path("packageFiles").get(0)).put("sha256", "b".repeat(64));
        assertThat(matches(parameters(s), s, i)).isFalse();
    }
    @Test void malformedReportCountsCalendarOrderingMetadataAndRangesReject() throws Exception {
        for (var key : new String[] {"reportNumber", "timeDays", "date", "unitSystem", "activeCells", "phaseMask", "solver", "byteOffset", "sizeBytes", "sha256", "extra"}) {
            var s = source(); var r = (ObjectNode)s.path("catalog").path("reports").get(80);
            switch (key) { case "reportNumber" -> r.put(key, 79); case "timeDays" -> r.put(key, 2190.5);
                case "date" -> r.put(key, "1975-12-30"); case "unitSystem" -> r.put(key, "METRIC");
                case "activeCells" -> r.put(key, 99999); case "phaseMask" -> r.put(key, 6); case "solver" -> r.put(key, 300);
                case "byteOffset" -> r.put(key, r.path(key).asLong() + 1); case "sizeBytes" -> r.put(key, 0);
                case "sha256" -> r.put(key, "bad"); default -> r.put(key, true); }
            assertThat(EclipseRestartForecastParameters.validCatalog(s)).as(key).isFalse();
        }
    }
    @Test void incompatibleOrUnboundForecastInputsAreNeverSelectable() throws Exception {
        for (var key : new String[] {"relativePath", "restartReport", "restartCase", "futureDates", "extra"}) {
            var s = source(); var f = (ObjectNode)s.path("forecastInputs").get(0);
            switch (key) { case "relativePath" -> f.put(key, "../FOREA.DATA"); case "restartReport" -> f.put(key, 81);
                case "restartCase" -> f.put(key, "OTHER"); case "futureDates" -> f.putArray(key).add("1975-12-31"); default -> f.put(key, true); }
            assertThat(EclipseRestartForecastParameters.validCatalog(s)).as(key).isFalse();
        }
    }
    @Test void wrongParameterTypesExtraFieldsAndInvalidDatesReject() throws Exception {
        for (var key : new String[] {"restartTimeDays", "restartReport", "restartDate", "extra", "schemaVersion"}) {
            var p = parameters(source()); switch (key) { case "restartTimeDays" -> p.put(key, "2190"); case "restartReport" -> p.put(key, 80.5);
                case "restartDate" -> p.put(key, "1975-02-31"); case "schemaVersion" -> p.put(key, "eclipse-history-forecast-parameters/1"); default -> p.put(key, 1); }
            assertThat(EclipseRestartForecastParameters.valid(p)).as(key).isFalse();
        }
    }
}
