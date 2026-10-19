package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import com.grdp.studio.softwareintegration.support.EclipseProductionTargetParameters;
import com.grdp.studio.softwareintegration.support.EclipsePackageProductionTargetParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class EclipsePackageProductionTargetTests {
    private final JsonMapper mapper = new JsonMapper();
    private JsonNode inspection() { return mapper.readTree(getClass().getResourceAsStream("/eclipse-include-production-inspection.json")); }
    private ObjectNode request(JsonNode i) {
        var target = i.path("packageProductionTargetContext").path("targets").path(0);
        var p = mapper.createObjectNode();
        p.put("schemaVersion", EclipsePackageProductionTargetParameters.SCHEMA); p.put("baselineRunId", 1);
        for (var key : new String[]{"well", "sourceFile", "sourceSha256", "lineNumber", "originalOilRate"}) p.set(key, target.get(key));
        p.put("phase", "INITIAL"); p.put("unit", "STB/day"); p.put("targetOilRate", 10000);
        return p;
    }
    @Test void roundTripsActualNestedInspectionAndBindsExactPhysicalSource() {
        var i = inspection(); var p = request(i);
        assertEquals(4, i.path("packageProductionTargetContext").path("targets").size());
        assertEquals("PRODUCERS.INC", p.path("sourceFile").asText());
        assertTrue(EclipseProductionTargetParameters.valid(p));
        assertTrue(EclipseProductionTargetParameters.matchesInspection(p, i));
        var persisted = EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
        // Reparse serialized JSON so validator-created long nodes compare by their JSON values.
        assertEquals(i, mapper.readTree(mapper.writeValueAsString(persisted)));
        assertTrue(EclipseProductionTargetParameters.matchesInspection(p, persisted));
    }
    @Test void staleSourceHashLineOriginalOrWellCannotMatchSavedInspection() {
        var i = inspection(); var good = request(i);
        for (var field : new String[]{"sourceFile", "sourceSha256", "lineNumber", "originalOilRate", "well"}) {
            var p = good.deepCopy();
            switch (field) {
                case "lineNumber" -> p.put(field, 999);
                case "originalOilRate" -> p.put(field, 19999);
                case "sourceFile" -> p.put(field, "INITIAL.INC");
                case "well" -> p.put(field, "UNKNOWN");
                default -> p.put(field, "0".repeat(64));
            }
            assertFalse(EclipseProductionTargetParameters.matchesInspection(p, i), field);
        }
    }
    @Test void alteredContextCannotSurvivePersistedInspectionValidation() {
        for (var field : new String[]{"sourceFile", "sourceSha256", "lineNumber", "originalOilRate", "well"}) {
            var i = (ObjectNode) inspection(); var t = (ObjectNode) i.path("packageProductionTargetContext").path("targets").path(0);
            switch (field) {
                case "lineNumber" -> t.put(field, 999);
                case "originalOilRate" -> t.put(field, 19999);
                case "sourceFile" -> t.put(field, "INITIAL.INC");
                case "well" -> t.put(field, "UNKNOWN");
                default -> t.put(field, "0".repeat(64));
            }
            assertThrows(IllegalArgumentException.class, () -> EclipseDataInspectionValidator.validateAndSerialize(i, mapper), field);
        }
    }
    @Test void rejectsUnsafePathsExtraFieldsSchemaDowngradeAndWrongUnits() {
        var i = inspection(); var good = request(i);
        for (var path : new String[]{"../PRODUCERS.INC", "C:/PRODUCERS.INC", "controls//PRODUCERS.INC", "controls/./PRODUCERS.INC", "controls\\PRODUCERS.INC"}) {
            var p = good.deepCopy(); p.put("sourceFile", path); assertFalse(EclipseProductionTargetParameters.valid(p));
        }
        var p = good.deepCopy(); p.put("schemaVersion", "eclipse-production-target-parameters/1"); assertFalse(EclipseProductionTargetParameters.valid(p));
        p = good.deepCopy(); p.put("unit", "sm3/day"); assertFalse(EclipseProductionTargetParameters.valid(p));
        p = good.deepCopy(); p.put("extra", 1); assertFalse(EclipseProductionTargetParameters.valid(p));
    }
}
