package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.EclipseSummaryResultValidator;
import com.grdp.studio.softwareintegration.support.EclipseCompositionalPressureParameters;
import com.grdp.studio.softwareintegration.support.EclipseCompositionalProductionParameters;
import com.grdp.studio.softwareintegration.support.EclipseCompositionalReinjectionParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class EclipseCompositionalReinjectionTests {
    private final JsonMapper mapper = new JsonMapper();
    private ObjectNode inspection() { return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-reinjection-inspection.json")); }
    private ObjectNode parameters(ObjectNode i) {
        var c = i.path("compositionalReinjectionContext"); var r = c.path("records").get(0);
        var p = mapper.createObjectNode().put("schemaVersion", EclipseCompositionalReinjectionParameters.SCHEMA).put("baselineRunId", 1).put("unit", "fraction").put("targetFraction", .5);
        for (var key : new String[]{"sourceFile", "sourceSha256"}) p.set(key, c.get(key));
        for (var key : new String[]{"group", "phase", "lineNumber", "originalFraction"}) p.set(key, r.get(key));
        return p;
    }
    @Test void actualWorkerVersion16RoundTripsBothSourcesAndOriginalTimePlan() {
        var i = inspection(); var p = parameters(i);
        assertTrue(EclipseCompositionalReinjectionParameters.matchesInspection(p, i));
        var persisted = EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
        assertEquals(i, mapper.readTree(mapper.writeValueAsString(persisted))); assertTrue(EclipseCompositionalReinjectionParameters.matchesInspection(p, persisted));
        var r = persisted.path("compositionalReinjectionContext").path("records").get(0);
        assertEquals(1500, r.path("salesTarget").asInt()); assertEquals(3650, r.path("shutAfterDays").asInt());
        assertEquals(4000, r.path("injectionBhpUpperLimit").asInt()); assertEquals(450, r.path("lineNumber").asInt());
        var c = i.path("compositionalProductionContext"); var original = c.path("records").get(0);
        var gas = mapper.createObjectNode().put("schemaVersion", EclipseCompositionalProductionParameters.SCHEMA).put("baselineRunId", 1).put("targetGasRate", 4500);
        for (var key : new String[]{"sourceFile", "sourceSha256"}) gas.set(key, c.get(key));
        for (var key : new String[]{"well", "lineNumber", "unit", "originalGasRate"}) gas.set(key, original.get(key));
        assertTrue(EclipseCompositionalProductionParameters.matchesInspection(gas, i));
        var pressure = mapper.createObjectNode().put("schemaVersion", EclipseCompositionalPressureParameters.SCHEMA).put("baselineRunId", 1).put("unit", "psia").put("targetBhpLowerLimit", 1500);
        for (var key : new String[]{"sourceFile", "sourceSha256"}) pressure.set(key, c.get(key));
        pressure.set("well", original.get("well")); pressure.set("lineNumber", original.get("bhpLineNumber")); pressure.set("originalBhpLowerLimit", original.get("bhpLimit"));
        assertTrue(EclipseCompositionalPressureParameters.matchesInspection(pressure, i));
    }
    @Test void strictGroupFractionProtocolRejectsTypedAndSourceMismatchesButAllowsNumericZero() {
        var i = inspection(); var zero = parameters(i).put("targetFraction", 0);
        assertTrue(EclipseCompositionalReinjectionParameters.matchesInspection(zero, i));
        for (var fault : new String[]{"source", "hash", "group", "phase", "line", "unit", "original", "same", "negative", "high", "string", "baseline", "extra", "legacy"}) {
            var p = parameters(i);
            switch (fault) {
                case "source" -> p.put("sourceFile", "../SPE3_GASCOND.DATA"); case "hash" -> p.put("sourceSha256", "0".repeat(64));
                case "group" -> p.put("group", "OTHER"); case "phase" -> p.put("phase", "WATER"); case "line" -> p.put("lineNumber", 451);
                case "unit" -> p.put("unit", "Mscf/day"); case "original" -> p.put("originalFraction", .9); case "same" -> p.put("targetFraction", 1);
                case "negative" -> p.put("targetFraction", -.1); case "high" -> p.put("targetFraction", 1.01); case "string" -> p.put("targetFraction", "0.5");
                case "baseline" -> p.put("baselineRunId", "1"); case "extra" -> p.put("targetGasRate", 2350); default -> p.put("schemaVersion", EclipseCompositionalProductionParameters.SCHEMA);
            }
            assertFalse(EclipseCompositionalReinjectionParameters.matchesInspection(p, i), fault);
        }
    }
    @Test void invalidGasAccountingSourcesAndFalseShutTimesCannotBePersisted() {
        for (var fault : new String[]{"hash", "size", "fraction", "source-kind", "source-group", "sales", "injector", "pressure", "production", "duplicate-line", "shut-days", "shut-line", "step", "dates", "timeline-source", "extra"}) {
            var i = inspection(); var c = (ObjectNode)i.path("compositionalReinjectionContext"); var r = (ObjectNode)c.path("records").get(0);
            switch (fault) {
                case "hash" -> c.put("sourceSha256", "0".repeat(64)); case "size" -> c.put("sourceSizeBytes", 1);
                case "fraction" -> r.put("originalFraction", 1.01); case "source-kind" -> r.put("gasSourceKind", "MIX"); case "source-group" -> r.put("gasSourceGroup", "OTHER");
                case "sales" -> r.put("salesTarget", -1); case "injector" -> r.put("injectionWell", "UNKNOWN"); case "pressure" -> r.put("injectionBhpUpperLimit", 0);
                case "production" -> r.put("productionGasTarget", 6199); case "duplicate-line" -> r.set("salesLineNumber", r.get("lineNumber"));
                case "shut-days" -> r.put("shutAfterDays", 3651); case "shut-line" -> r.put("shutLineNumber", 475);
                case "step" -> ((ObjectNode)i.path("scheduleTimeline").get(0)).withArray("steps").set(0, mapper.getNodeFactory().stringNode("328.6"));
                case "dates" -> ((ObjectNode)i.path("scheduleTimeline").get(0)).put("kind", "DATES");
                case "timeline-source" -> ((ObjectNode)i.path("scheduleTimeline").get(0)).put("sourceFile", "OTHER.DATA"); default -> r.put("unverified", true);
            }
            assertFalse(EclipseCompositionalReinjectionParameters.validContext(c, i), fault);
            assertThrows(IllegalArgumentException.class, () -> EclipseDataInspectionValidator.validateAndSerialize(i, mapper), fault);
        }
    }
    @Test void version14RemainsClosedAndVersion16RequiresItsQualifiedContext() {
        var i = inspection(); i.put("schemaVersion", "eclipse-data-inspection/14");
        assertThrows(IllegalArgumentException.class, () -> EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
        i.remove("compositionalReinjectionContext"); assertNotNull(EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
        i.put("schemaVersion", "eclipse-data-inspection/16");
        assertThrows(IllegalArgumentException.class, () -> EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
    }
    @Test void allActualHalfReinjectionNativeVectorsRemainValidUnderVersion16() {
        var i = inspection(); var all = mapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-reinjection-native.json")); var nativeData = all.path("half");
        var result = mapper.createObjectNode().put("schemaVersion", "eclipse-summary-result/2").put("modelKind", "eclipse_300").put("runTask", "eclipse")
            .put("resultContract", "VALID_FULL").put("caseName", i.path("caseName").asText());
        result.set("simulation", i.get("simulation")); result.set("summary", nativeData.get("summary")); result.set("eclEnd", nativeData.get("eclEnd")); result.set("outputFiles", all.get("halfOutputFiles"));
        var checked = new EclipseSummaryResultValidator(mapper).validateCompositional(i.path("caseName").asText(), result, i);
        assertEquals(result, checked.result()); assertEquals(330, checked.result().path("summary").path("series").size());
    }
}
