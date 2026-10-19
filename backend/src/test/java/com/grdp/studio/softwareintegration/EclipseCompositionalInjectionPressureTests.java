package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.EclipseSummaryResultValidator;
import com.grdp.studio.softwareintegration.support.EclipseCompositionalInjectionPressureParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class EclipseCompositionalInjectionPressureTests {
    private final JsonMapper mapper = new JsonMapper();
    private ObjectNode inspection() { return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-reinjection-inspection.json")); }
    private ObjectNode parameters(ObjectNode i) {
        var c = i.path("compositionalReinjectionContext"); var r = c.path("records").get(0);
        var p = mapper.createObjectNode().put("schemaVersion", EclipseCompositionalInjectionPressureParameters.SCHEMA).put("baselineRunId", 1).put("unit", "psia").put("targetBhpUpperLimit", 3550);
        for (var key : new String[]{"sourceFile", "sourceSha256"}) p.set(key, c.get(key));
        p.set("well", r.get("injectionWell")); p.set("lineNumber", r.get("injectionLineNumber")); p.set("originalBhpUpperLimit", r.get("injectionBhpUpperLimit")); return p;
    }
    @Test void exactOriginalInjectionSourceBindsUpperLimitAndRetainsSalesFractionAndProducerLowerLimit() {
        var i = inspection(); var p = parameters(i); var persisted = EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
        assertTrue(EclipseCompositionalInjectionPressureParameters.matchesInspection(p, persisted)); assertEquals(457, p.path("lineNumber").asInt()); assertEquals("I", p.path("well").asText());
        assertEquals(4000, p.path("originalBhpUpperLimit").asInt());
        i.put("schemaVersion", "eclipse-data-inspection/14"); assertFalse(EclipseCompositionalInjectionPressureParameters.matchesInspection(p, i));
    }
    @Test void wrongControlRowsOrNonPositivePressureCannotBecomeAValidInjectionLimit() {
        var i = inspection();
        for (var fault : new String[]{"source", "hash", "well", "producer-row", "sales-row", "unit", "original", "same", "zero", "negative", "high", "string", "baseline", "extra", "producer"}) {
            var p = parameters(i);
            switch (fault) {
                case "source" -> p.put("sourceFile", "../SPE3_GASCOND.DATA"); case "hash" -> p.put("sourceSha256", "0".repeat(64));
                case "well" -> p.put("well", "P"); case "producer-row" -> p.put("lineNumber", 433); case "sales-row" -> p.put("lineNumber", 439);
                case "unit" -> p.put("unit", "barsa"); case "original" -> p.put("originalBhpUpperLimit", 3999); case "same" -> p.put("targetBhpUpperLimit", 4000);
                case "zero" -> p.put("targetBhpUpperLimit", 0); case "negative" -> p.put("targetBhpUpperLimit", -1); case "high" -> p.put("targetBhpUpperLimit", 100_000_001); case "string" -> p.put("targetBhpUpperLimit", "3550");
                case "baseline" -> p.put("baselineRunId", "1"); case "extra" -> p.put("targetFraction", .5); default -> p.put("schemaVersion", "eclipse-compositional-pressure-parameters/1");
            }
            assertFalse(EclipseCompositionalInjectionPressureParameters.matchesInspection(p, i), fault);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"limited", "inactive"})
    void allNativeVectorsAndDiagnosticsAreRetainedForEachOwnPressureReference(String name) {
        var i = inspection(); var all = mapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-injection-pressure-" + name + ".json")); var data = all.path("pressure");
        var result = mapper.createObjectNode().put("schemaVersion", "eclipse-summary-result/2").put("modelKind", "eclipse_300").put("runTask", "eclipse")
            .put("resultContract", "VALID_FULL").put("caseName", i.path("caseName").asText());
        result.set("simulation", i.get("simulation")); result.set("summary", data.get("summary")); result.set("eclEnd", data.get("eclEnd")); result.set("outputFiles", all.get("pressureOutputFiles"));
        var checked = new EclipseSummaryResultValidator(mapper).validateCompositional(i.path("caseName").asText(), result, i);
        assertEquals(result, checked.result()); assertEquals(330, checked.result().path("summary").path("series").size());
    }
}
