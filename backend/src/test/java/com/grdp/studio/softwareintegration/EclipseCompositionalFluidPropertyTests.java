package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.EclipseSummaryResultValidator;
import com.grdp.studio.softwareintegration.support.EclipseCompositionalFluidPropertyParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class EclipseCompositionalFluidPropertyTests {
    private final JsonMapper mapper = new JsonMapper();
    private ObjectNode inspection() { return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-fluid-property-inspection.json")); }
    private ObjectNode parameters(ObjectNode i) {
        var c = i.path("compositionalFluidPropertyContext"); var r = c.path("records").get(2);
        var p = mapper.createObjectNode().put("schemaVersion", EclipseCompositionalFluidPropertyParameters.SCHEMA).put("baselineRunId", 1).put("targetValue", 700);
        for (var k : new String[]{"sourceFile", "sourceSha256"}) p.set(k, c.get(k));
        for (var k : new String[]{"componentIndex", "componentName", "field", "lineNumber", "unit", "originalValue"}) p.set(k, r.get(k)); return p;
    }
    @Test void actualVersion17PersistsAllThreeExactSourcesAndCanPreserveAnOptionalReinjectionEditor() {
        var i = inspection(); var p = parameters(i);
        var restored = EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
        assertEquals(i, mapper.readTree(mapper.writeValueAsString(restored))); assertTrue(EclipseCompositionalFluidPropertyParameters.matchesInspection(p, restored));
        assertEquals("C1", p.path("componentName").asText()); assertEquals(3, p.path("componentIndex").asInt()); assertEquals(122, p.path("lineNumber").asInt());
        assertEquals(667.7817, p.path("originalValue").asDouble()); assertEquals(9, restored.path("compositionalFluidPropertyContext").path("records").size());
        i.remove("compositionalReinjectionContext"); assertNotNull(EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i, mapper)));
    }
    @Test void wrongComponentFieldSourceAndUntypedOrNonPositiveValuesCannotMatch() {
        var i = inspection();
        for (var fault : new String[]{"source", "hash", "line", "index", "name", "field", "unit", "original", "same", "zero", "negative", "high", "string", "baseline", "extra", "pressure"}) {
            var p = parameters(i);
            switch (fault) {
                case "source" -> p.put("sourceFile", "../SPE3_GASCOND.DATA"); case "hash" -> p.put("sourceSha256", "0".repeat(64));
                case "line" -> p.put("lineNumber", 123); case "index" -> p.put("componentIndex", 2); case "name" -> p.put("componentName", "C2");
                case "field" -> p.put("field", "TCRIT"); case "unit" -> p.put("unit", "barsa"); case "original" -> p.put("originalValue", 667);
                case "same" -> p.put("targetValue", 667.7817); case "zero" -> p.put("targetValue", 0); case "negative" -> p.put("targetValue", -1);
                case "high" -> p.put("targetValue", 100_000_001); case "string" -> p.put("targetValue", "700"); case "baseline" -> p.put("baselineRunId", "1");
                case "extra" -> p.put("targetBhpUpperLimit", 700); default -> p.put("schemaVersion", "eclipse-compositional-injection-pressure-parameters/1");
            }
            assertFalse(EclipseCompositionalFluidPropertyParameters.matchesInspection(p, i), fault);
        }
    }
    @Test void criticalArrayMustPreserveEveryOriginalComponentIndexNameFieldValueAndSource() {
        for (var fault : new String[]{"source", "hash", "size", "solver", "count", "eos", "index", "name", "field", "unit", "value", "typed-line", "line-order", "extra", "missing", "names"}) {
            var i = inspection(); var c = (ObjectNode)i.path("compositionalFluidPropertyContext"); var r = (ObjectNode)c.path("records").get(2);
            switch (fault) {
                case "source" -> c.put("sourceFile", "OTHER.DATA"); case "hash" -> c.put("sourceSha256", "0".repeat(64)); case "size" -> c.put("sourceSizeBytes", 1);
                case "solver" -> c.put("solver", 100); case "count" -> c.put("componentCount", 8); case "eos" -> c.put("equationOfState", "SRK");
                case "index" -> r.put("componentIndex", 2); case "name" -> r.put("componentName", "C2"); case "field" -> r.put("field", "TCRIT");
                case "unit" -> r.put("unit", "barsa"); case "value" -> r.put("originalValue", 0); case "typed-line" -> r.put("lineNumber", "122");
                case "line-order" -> ((ObjectNode)c.path("records").get(8)).put("lineNumber", 1); case "extra" -> r.put("other", true);
                case "missing" -> c.withArray("records").remove(8); default -> c.withArray("componentNames").set(2, mapper.getNodeFactory().stringNode("C2"));
            }
            assertFalse(EclipseCompositionalFluidPropertyParameters.validContext(c, i), fault);
            assertThrows(IllegalArgumentException.class, () -> EclipseDataInspectionValidator.validateAndSerialize(i, mapper), fault);
        }
    }
    @Test void originalVersionsRemainClosedAndVersion17CannotDropItsMandatoryPropertySource() {
        var i = inspection(); i.put("schemaVersion", "eclipse-data-inspection/16");
        assertThrows(IllegalArgumentException.class, () -> EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
        i.remove("compositionalFluidPropertyContext"); assertNotNull(EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
        i.put("schemaVersion", "eclipse-data-inspection/17"); assertThrows(IllegalArgumentException.class, () -> EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
    }
    @Test void all330NativeVectorsIncludingDuplicateFieldOilAndPressureColumnsRemainAvailable() {
        var i = inspection(); var all = mapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-fluid-property-native.json")); var data = all.path("pcrit");
        var result = mapper.createObjectNode().put("schemaVersion", "eclipse-summary-result/2").put("modelKind", "eclipse_300").put("runTask", "eclipse")
            .put("resultContract", "VALID_FULL").put("caseName", i.path("caseName").asText());
        result.set("simulation", i.get("simulation")); result.set("summary", data.get("summary")); result.set("eclEnd", data.get("eclEnd")); result.set("outputFiles", all.get("pcritOutputFiles"));
        var checked = new EclipseSummaryResultValidator(mapper).validateCompositional(i.path("caseName").asText(), result, i);
        assertEquals(result, checked.result()); assertEquals(330, checked.result().path("summary").path("series").size());
        int totals = 0; for (var v : checked.result().path("summary").path("series")) if (v.path("keyword").asText().equals("FOPT") && v.path("objectName").isNull()) totals++;
        assertEquals(2, totals);
    }
}
