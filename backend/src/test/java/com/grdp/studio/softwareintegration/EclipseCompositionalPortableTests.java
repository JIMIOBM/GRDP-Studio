package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.EclipseSummaryResultValidator;
import com.grdp.studio.softwareintegration.support.EclipseCompositionalForecastParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class EclipseCompositionalPortableTests {
    private final JsonMapper mapper = new JsonMapper();
    private ObjectNode load(String name) { return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/" + name + ".json")); }
    private ObjectNode inspection() { return load("eclipse-compositional-restart-inspection"); }
    @Test void actualWorker18RoundTripsTheCompleteSourceAndSaveManifestAndAcceptsItsNativeFuture() {
        var i = inspection(); var c = i.path("compositionalRestartContext");
        assertTrue(EclipseCompositionalForecastParameters.validPortableContext(c, i));
        var persisted = EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
        assertEquals(i, mapper.readTree(mapper.writeValueAsString(persisted)));
        var result = load("eclipse-compositional-restart-native-result");
        assertNotNull(new EclipseSummaryResultValidator(mapper).validateCompositional("SPE3_GASCOND.DATA", result, i));
        assertTrue(result.path("grid").isNull()); assertEquals(330, result.path("summary").path("series").size());
        assertEquals(64, result.path("summary").path("series").get(0).path("points").size());
    }
    @Test void closedPortableContextRejectsMissingForeignOrRetypedInputAndStateBindings() {
        for (var fault : new String[]{"source", "hash", "size", "history-path", "target", "file-hash", "missing", "extra", "dims", "names"}) {
            var i = inspection(); var c = (ObjectNode)i.path("compositionalRestartContext");
            switch (fault) {
                case "source" -> c.put("sourceFile", "OTHER.DATA"); case "hash" -> c.put("sourceSha256", "0".repeat(64));
                case "size" -> c.put("sourceSizeBytes", 1); case "history-path" -> c.put("historySourceFile", "../SPE3_GASCOND.DATA");
                case "target" -> c.put("targetGasRate", "4500"); case "file-hash" -> ((ObjectNode)i.path("packageFiles").get(2)).put("sha256", "0".repeat(64));
                case "missing" -> i.withArray("packageFiles").remove(0); case "extra" -> c.put("ignored", true);
                case "dims" -> ((ObjectNode)i.path("dimensions")).put("nx", 8);
                default -> ((ObjectNode)i.path("simulation")).withArray("componentNames").set(2, mapper.getNodeFactory().stringNode("METHANE"));
            }
            assertFalse(EclipseCompositionalForecastParameters.validPortableContext(c, i), fault);
            assertThrows(IllegalArgumentException.class, () -> EclipseDataInspectionValidator.validateAndSerialize(i, mapper), fault);
        }
    }
    @Test void olderVersionsCannotAcceptPortableContextOrEnableInitialStateEditors() {
        for (var version : new String[]{"14", "16", "17"}) {
            var i = inspection(); i.put("schemaVersion", "eclipse-data-inspection/" + version);
            assertThrows(IllegalArgumentException.class, () -> EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
        }
        var i = inspection(); i.set("compositionalProductionContext", load("eclipse-compositional-fluid-property-inspection").path("compositionalProductionContext"));
        assertThrows(IllegalArgumentException.class, () -> EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
    }
    @Test void restartedResultMustPreserveHistoryOriginAndCompleteFutureRatherThanResetOrTruncate() {
        for (var fault : new String[]{"origin", "rebase", "truncate"}) {
            var r = load("eclipse-compositional-restart-native-result");
            if (fault.equals("origin")) ((ObjectNode)r.path("summary")).put("startDate", "2000-01-01");
            for (var s : r.path("summary").path("series")) {
                if (fault.equals("truncate")) ((tools.jackson.databind.node.ArrayNode)s.path("points")).remove(s.path("points").size() - 1);
                if (fault.equals("rebase")) for (var p : s.path("points")) ((ObjectNode)p).put("timeDays", p.path("timeDays").asDouble() - 3285);
            }
            assertThrows(EclipseSummaryResultValidator.ResultValidationException.class, () -> new EclipseSummaryResultValidator(mapper).validateCompositional("SPE3_GASCOND.DATA", r, inspection()), fault);
        }
    }
}
