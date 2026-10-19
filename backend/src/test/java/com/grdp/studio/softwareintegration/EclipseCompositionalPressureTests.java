package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseCompositionalPressureParameters;
import com.grdp.studio.softwareintegration.support.EclipseCompositionalProductionParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class EclipseCompositionalPressureTests {
    private final JsonMapper mapper = new JsonMapper();
    private ObjectNode inspection() { return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-production-inspection.json")); }
    private ObjectNode parameters(ObjectNode i) {
        var c = i.path("compositionalProductionContext"); var r = c.path("records").get(0);
        var p = mapper.createObjectNode().put("schemaVersion", EclipseCompositionalPressureParameters.SCHEMA).put("baselineRunId", 1)
            .put("unit", "psia").put("targetBhpLowerLimit", 1500);
        for (var key : new String[]{"sourceFile", "sourceSha256"}) p.set(key, c.get(key));
        p.set("well", r.get("well")); p.set("lineNumber", r.get("bhpLineNumber")); p.set("originalBhpLowerLimit", r.get("bhpLimit"));
        return p;
    }
    @Test void pressureBindsTheActualSeparateBhpRowAndKeepsTheGasProtocolClosed() {
        var i = inspection(); var p = parameters(i);
        assertEquals(433, p.path("lineNumber").asInt());
        assertTrue(EclipseCompositionalPressureParameters.matchesInspection(p, i));
        assertFalse(EclipseCompositionalProductionParameters.valid(p));
        assertEquals(6200, i.path("compositionalProductionContext").path("records").get(0).path("originalGasRate").asInt());
    }
    @Test void invalidPhysicalSourceValuesUnitsTypesAndForeignContextsAreRejected() {
        for (var fault : new String[]{"source", "hash", "gas-row", "well", "unit", "original", "same", "zero", "string", "extra", "baseline", "legacy", "thermal", "prior-pressure", "missing"}) {
            var i = inspection(); var p = parameters(i);
            switch (fault) {
                case "source" -> p.put("sourceFile", "../SPE3_GASCOND.DATA"); case "hash" -> p.put("sourceSha256", "0".repeat(64));
                case "gas-row" -> p.set("lineNumber", i.path("compositionalProductionContext").path("records").get(0).get("lineNumber"));
                case "well" -> p.put("well", "I"); case "unit" -> p.put("unit", "barsa"); case "original" -> p.put("originalBhpLowerLimit", 499);
                case "same" -> p.put("targetBhpLowerLimit", 500); case "zero" -> p.put("targetBhpLowerLimit", 0); case "string" -> p.put("targetBhpLowerLimit", "1500");
                case "extra" -> p.put("targetGasRate", 6200); case "baseline" -> p.put("baselineRunId", "1");
                case "legacy" -> p.put("schemaVersion", EclipseCompositionalProductionParameters.SCHEMA);
                case "prior-pressure" -> { ((ObjectNode)i.path("compositionalProductionContext").path("records").get(0)).put("bhpLineNumber", 429); p.put("lineNumber", 429); }
                case "thermal" -> i.put("schemaVersion", "eclipse-data-inspection/15"); default -> i.remove("compositionalProductionContext");
            }
            assertFalse(EclipseCompositionalPressureParameters.matchesInspection(p, i), fault);
        }
    }
}
