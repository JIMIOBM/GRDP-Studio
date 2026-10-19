package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.EclipseSummaryResultValidator;
import com.grdp.studio.softwareintegration.support.EclipseCompositionalGasSalesParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class EclipseCompositionalGasSalesTests {
    private final JsonMapper mapper = new JsonMapper();
    private ObjectNode inspection() { return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-reinjection-inspection.json")); }
    private ObjectNode parameters(ObjectNode i) {
        var c = i.path("compositionalReinjectionContext"); var r = c.path("records").get(0);
        var p = mapper.createObjectNode().put("schemaVersion", EclipseCompositionalGasSalesParameters.SCHEMA).put("baselineRunId", 1).put("unit", "Mscf/day").put("targetSalesTarget", 3000);
        for (var key : new String[]{"sourceFile", "sourceSha256"}) p.set(key, c.get(key));
        for (var key : new String[]{"group", "phase"}) p.set(key, r.get(key));
        p.set("lineNumber", r.get("salesLineNumber")); p.set("originalSalesTarget", r.get("salesTarget")); return p;
    }
    @Test void unchangedVersion16ContextCarriesExactSalesAndRetainedReinjectionControls() {
        var i = inspection(); var p = parameters(i);
        var persisted = EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
        assertTrue(EclipseCompositionalGasSalesParameters.matchesInspection(p, persisted));
        assertEquals(439, p.path("lineNumber").asInt()); assertEquals(1500, p.path("originalSalesTarget").asInt());
        var r = persisted.path("compositionalReinjectionContext").path("records").get(0);
        assertEquals(1, r.path("originalFraction").asDouble()); assertEquals(3650, r.path("shutAfterDays").asDouble());
        i.put("schemaVersion", "eclipse-data-inspection/14");
        assertFalse(EclipseCompositionalGasSalesParameters.matchesInspection(p, i));
    }
    @Test void strictSalesProtocolRejectsForeignFractionSourceAndUntypedValuesAndAllowsNumericZero() {
        var i = inspection(); assertTrue(EclipseCompositionalGasSalesParameters.matchesInspection(parameters(i).put("targetSalesTarget", 0), i));
        for (var fault : new String[]{"source", "hash", "group", "phase", "line", "unit", "original", "same", "negative", "high", "string", "baseline", "extra", "reinjection"}) {
            var p = parameters(i);
            switch (fault) {
                case "source" -> p.put("sourceFile", "../SPE3_GASCOND.DATA"); case "hash" -> p.put("sourceSha256", "0".repeat(64));
                case "group" -> p.put("group", "OTHER"); case "phase" -> p.put("phase", "WATER"); case "line" -> p.put("lineNumber", 450);
                case "unit" -> p.put("unit", "fraction"); case "original" -> p.put("originalSalesTarget", 1499); case "same" -> p.put("targetSalesTarget", 1500);
                case "negative" -> p.put("targetSalesTarget", -1); case "high" -> p.put("targetSalesTarget", 100_000_001); case "string" -> p.put("targetSalesTarget", "3000");
                case "baseline" -> p.put("baselineRunId", "1"); case "extra" -> p.put("targetFraction", .5); default -> p.put("schemaVersion", "eclipse-compositional-reinjection-parameters/1");
            }
            assertFalse(EclipseCompositionalGasSalesParameters.matchesInspection(p, i), fault);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"normal", "unavailable"})
    void completeActualNativeResultsAndDiagnosticsRemainUnmodified(String name) {
        var i = inspection(); var all = mapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-sales-" + name + ".json")); var data = all.path("sales");
        var result = mapper.createObjectNode().put("schemaVersion", "eclipse-summary-result/2").put("modelKind", "eclipse_300").put("runTask", "eclipse")
            .put("resultContract", "VALID_FULL").put("caseName", i.path("caseName").asText());
        result.set("simulation", i.get("simulation")); result.set("summary", data.get("summary")); result.set("eclEnd", data.get("eclEnd")); result.set("outputFiles", all.get("salesOutputFiles"));
        var checked = new EclipseSummaryResultValidator(mapper).validateCompositional(i.path("caseName").asText(), result, i);
        assertEquals(result, checked.result()); assertEquals(330, checked.result().path("summary").path("series").size());
        assertEquals(3, checked.result().path("eclEnd").path("warnings").asInt());
    }
}
