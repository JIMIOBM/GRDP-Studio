package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseCompositionalForecastParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class EclipseCompositionalForecastTests {
    private final JsonMapper mapper = new JsonMapper();
    private ObjectNode load(String name) { return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/" + name + ".json")); }
    private ObjectNode catalog() { return load("eclipse-compositional-save-catalog"); }
    private ObjectNode inspection() { return load("eclipse-compositional-fluid-property-inspection"); }
    private ObjectNode parameters() {
        var p = mapper.createObjectNode().put("schemaVersion", EclipseCompositionalForecastParameters.SCHEMA).put("historyRunId", 1)
            .put("sourceFile", "SPE3_GASCOND.DATA").put("sourceSha256", catalog().path("sourceSha256").asText())
            .put("saveCatalogSha256", "a".repeat(64)).put("saveReport", 10);
        p.putNull("targetGasRate"); return p;
    }
    @Test void actualWorkerCatalogBindsNativeFilesOriginalClockGridComponentsAndQualifiedSource() {
        var c = catalog(); var i = inspection();
        assertTrue(EclipseCompositionalForecastParameters.validCatalog(c));
        assertTrue(EclipseCompositionalForecastParameters.matchesInspection(c, i));
        assertTrue(EclipseCompositionalForecastParameters.matchesResult(c, load("eclipse-compositional-save-native-result")));
        var p = parameters(); assertTrue(EclipseCompositionalForecastParameters.matchesRequest(p, c, i));
        p.put("targetGasRate", 4500); assertTrue(EclipseCompositionalForecastParameters.matchesRequest(p, c, i));
        p.put("targetGasRate", 6200); assertFalse(EclipseCompositionalForecastParameters.matchesRequest(p, c, i));
    }
    @Test void foreignOrMalformedCatalogCannotSelectAnUnverifiedHistoricalCheckpoint() {
        for (var fault : new String[]{"schema", "source", "hash", "size", "solver", "release", "units", "eos", "count", "duplicate-name", "grid", "active", "time", "date", "end", "case", "report", "files", "name", "artifact", "file-hash", "extra"}) {
            var c = catalog();
            switch (fault) {
                case "schema" -> c.put("schemaVersion", "eclipse-restart-catalog/2"); case "source" -> c.put("sourceFile", "../SPE3_GASCOND.DATA");
                case "hash" -> c.put("sourceSha256", "bad"); case "size" -> c.put("sourceSizeBytes", 0); case "solver" -> c.put("solver", 100);
                case "release" -> c.put("solverVersion", "2023.1"); case "units" -> c.put("unitSystem", "METRIC"); case "eos" -> c.put("equationOfState", "SRK");
                case "count" -> c.put("componentCount", 8); case "duplicate-name" -> c.withArray("componentNames").set(2, mapper.getNodeFactory().stringNode("N2"));
                case "grid" -> ((ObjectNode)c.path("dimensions")).put("nx", 0); case "active" -> c.put("activeCells", 325);
                case "time" -> c.put("timeDays", "3285"); case "date" -> c.put("date", "1998-12-29"); case "end" -> c.put("endTimeDays", 3285);
                case "case" -> c.put("caseName", "OTHER"); case "report" -> c.put("reportNumber", 11); case "files" -> c.withArray("files").remove(2);
                case "name" -> ((ObjectNode)c.path("files").get(1)).put("name", "../SPE3_GASCOND.V0010");
                case "artifact" -> ((ObjectNode)c.path("files").get(1)).put("artifactName", "eclipse-output-OTHER.V0010");
                case "file-hash" -> ((ObjectNode)c.path("files").get(1)).put("sha256", "bad"); default -> c.put("hidden", true);
            }
            assertFalse(EclipseCompositionalForecastParameters.validCatalog(c), fault);
        }
    }
    @Test void otherwiseValidForeignSourceComponentOrderOrGeometryCannotMatchCurrentInspection() {
        for (var fault : new String[]{"hash", "names", "grid", "size"}) {
            var c = catalog();
            switch (fault) {
                case "hash" -> c.put("sourceSha256", "0".repeat(64)); case "size" -> c.put("sourceSizeBytes", 8774);
                case "names" -> c.withArray("componentNames").set(2, mapper.getNodeFactory().stringNode("METHANE"));
                default -> ((ObjectNode)c.path("dimensions")).put("nx", 10);
            }
            assertTrue(EclipseCompositionalForecastParameters.validCatalog(c), fault);
            assertFalse(EclipseCompositionalForecastParameters.matchesInspection(c, inspection()), fault);
        }
    }
    @Test void parametersRejectCoercionForeignReportUnsafeSourceAndInvalidRate() {
        for (var fault : new String[]{"history", "source", "hash", "catalog", "report", "rate-string", "zero", "negative", "high", "extra", "e100"}) {
            var p = parameters();
            switch (fault) {
                case "history" -> p.put("historyRunId", "1"); case "source" -> p.put("sourceFile", "../SPE3_GASCOND.DATA");
                case "hash" -> p.put("sourceSha256", "bad"); case "catalog" -> p.put("saveCatalogSha256", "bad"); case "report" -> p.put("saveReport", 0);
                case "rate-string" -> p.put("targetGasRate", "4500"); case "zero" -> p.put("targetGasRate", 0); case "negative" -> p.put("targetGasRate", -1);
                case "high" -> p.put("targetGasRate", 100000001); case "extra" -> p.put("restartArtifactName", "BASE.UNRST");
                default -> p.put("schemaVersion", "eclipse-restart-forecast-parameters/2");
            }
            assertFalse(EclipseCompositionalForecastParameters.valid(p), fault);
        }
    }
    @Test void publishedStateCannotBeAttachedToOtherSolverClockGridComponentsOrOutputChecksums() {
        for (var fault : new String[]{"solver", "clock", "grid", "names", "hash", "partial"}) {
            var r = load("eclipse-compositional-save-native-result");
            switch (fault) {
                case "solver" -> ((ObjectNode)r.path("simulation")).put("solver", 100);
                case "clock" -> ((ObjectNode)r.path("summary")).put("startDate", "2000-01-01");
                case "grid" -> ((ObjectNode)r.path("grid")).put("nx", 8);
                case "names" -> ((ObjectNode)r.path("simulation")).withArray("componentNames").set(2, mapper.getNodeFactory().stringNode("METHANE"));
                case "hash" -> {
                    for (var file : r.path("outputFiles")) if ("SPE3_GASCOND.V0010".equals(file.path("name").asText()))
                        ((ObjectNode)file).put("sha256", "0".repeat(64));
                }
                default -> ((ObjectNode)r.path("eclEnd")).put("problems", 1);
            }
            assertFalse(EclipseCompositionalForecastParameters.matchesResult(catalog(), r), fault);
        }
    }
}
