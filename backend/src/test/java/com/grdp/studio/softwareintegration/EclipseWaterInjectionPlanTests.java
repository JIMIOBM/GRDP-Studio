package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseControlPlanParameters;
import com.grdp.studio.softwareintegration.support.EclipseWaterInjectionPlanParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class EclipseWaterInjectionPlanTests {
    private final JsonMapper mapper = new JsonMapper();
    private ObjectNode inspection() { return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-water-injection-plan-inspection.json")); }
    private ObjectNode request(JsonNode inspection) {
        var p = mapper.createObjectNode().put("schemaVersion", EclipseWaterInjectionPlanParameters.SCHEMA).put("baselineRunId", 1); var edits = p.putArray("edits");
        for (int index : new int[] {2, 5, 6}) {
            var record = inspection.path("waterInjectionControlPlanContext").path("records").get(index); var e = edits.addObject();
            for (var key : new String[] {"well", "sourceFile", "sourceSha256", "lineNumber", "date", "timeDays", "unit", "originalInjectionRate"}) e.set(key, record.get(key));
            e.put("targetInjectionRate", index == 2 ? .5 : index == 5 ? .7 : .9);
        }
        return p;
    }
    @Test void actualWorkerContextKeepsAllEightRecordsAndRoundTripsWithoutLosingSources() {
        var i = inspection(); var context = i.path("waterInjectionControlPlanContext"); var p = request(i);
        assertEquals(8, context.path("records").size()); assertEquals(6, i.path("packageFiles").size());
        assertTrue(EclipseWaterInjectionPlanParameters.validContext(context, i)); assertTrue(EclipseControlPlanParameters.valid(p));
        assertTrue(EclipseControlPlanParameters.matchesInspection(p, i));
        var persisted = EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
        assertEquals(i, mapper.readTree(mapper.writeValueAsString(persisted)));
        assertTrue(EclipseControlPlanParameters.matchesInspection(p, persisted));
    }
    @Test void staleHashRecordDateTimeOrUnitCannotMatchPhysicalInputs() {
        var i = inspection();
        for (var field : new String[] {"sourceFile", "sourceSha256", "lineNumber", "date", "timeDays", "unit", "originalInjectionRate"}) {
            var p = request(i); var edit = (ObjectNode)p.path("edits").get(1);
            switch (field) { case "sourceFile" -> edit.put(field, "controls/YEAR1.INC"); case "lineNumber" -> edit.put(field, 999);
                case "date" -> edit.put(field, "1984-10-19"); case "timeDays" -> edit.put(field, 731); case "unit" -> edit.put(field, "sm3/day");
                case "originalInjectionRate" -> edit.put(field, .99); default -> edit.put(field, "0".repeat(64)); }
            assertFalse(EclipseControlPlanParameters.matchesInspection(p, i), field);
        }
    }
    @Test void forgedSourceMetadataOrClockCannotSurviveInspectionValidation() {
        for (var fault : new String[] {"hash", "line", "date", "unit", "rate", "bhp", "missing", "order", "extra"}) {
            var i = inspection(); var context = (ObjectNode)i.path("waterInjectionControlPlanContext"); var record = (ObjectNode)context.path("records").get(2);
            switch (fault) { case "hash" -> record.put("sourceSha256", "0".repeat(64)); case "line" -> record.put("lineNumber", 999);
                case "date" -> record.put("date", "1983-10-20"); case "unit" -> record.put("unit", "sm3/day"); case "rate" -> record.put("originalInjectionRate", .99);
                case "bhp" -> record.put("bhpLimit", 2400); case "missing" -> ((tools.jackson.databind.node.ArrayNode)context.path("records")).remove(2);
                case "order" -> record.put("timeDays", 731); default -> context.put("extra", 1); }
            assertThrows(IllegalArgumentException.class, () -> EclipseDataInspectionValidator.validateAndSerialize(i, mapper), fault);
        }
    }
    @Test void strictJsonTypesUnsafePathsExtraFieldsAndSchemaDowngradeReject() {
        var i = inspection();
        for (var path : new String[] {"../YEAR1.INC", "C:/YEAR1.INC", "controls//YEAR1.INC", "controls/./YEAR1.INC", "controls\\YEAR1.INC"}) {
            var p = request(i); ((ObjectNode)p.path("edits").get(0)).put("sourceFile", path); assertFalse(EclipseControlPlanParameters.valid(p));
        }
        var p = request(i); ((ObjectNode)p.path("edits").get(0)).put("targetInjectionRate", "250"); assertFalse(EclipseControlPlanParameters.valid(p));
        p = request(i); p.put("extra", true); assertFalse(EclipseControlPlanParameters.valid(p));
        p = request(i); p.put("schemaVersion", "eclipse-control-plan-parameters/1"); assertFalse(EclipseControlPlanParameters.valid(p));
        p = request(i); var edits = (tools.jackson.databind.node.ArrayNode)p.path("edits"); var first = edits.get(0).deepCopy(); edits.set(0, edits.get(2).deepCopy()); edits.set(2, first);
        assertFalse(EclipseControlPlanParameters.matchesInspection(p, i));
    }
}
