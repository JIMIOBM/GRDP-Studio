package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

/** Allows only the small, display-safe ECLIPSE deck metadata contract. */
public final class EclipseDataInspectionValidator {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SECTION_ORDER = List.of("RUNSPEC", "GRID", "EDIT", "PROPS", "REGIONS", "SOLUTION", "SUMMARY", "SCHEDULE");
    private static final List<String> PHASE_ORDER = List.of("OIL", "WATER", "GAS");
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "caseName", "sections", "unitSystem", "phases", "dimensions");
    private static final Set<String> UNIT_SYSTEMS = Set.of("METRIC", "FIELD", "LAB", "PVT-M");
    private static final int MAX_CASE_NAME_LENGTH = 255;
    private static final int MAX_DIMENSION = 1_000_000;
    private EclipseDataInspectionValidator() {}

    public static String validateAndSerialize(JsonNode value, ObjectMapper objectMapper) {
        JsonNode validated = validate(value, objectMapper);
        try { return objectMapper.writeValueAsString(validated); }
        catch (Exception exception) { throw new IllegalArgumentException("inspection serialization failed", exception); }
    }

    public static JsonNode parsePersisted(String value) {
        if (value == null) return null;
        try { return validate(MAPPER.readTree(value), MAPPER); }
        catch (Exception exception) { return null; }
    }

    private static JsonNode validate(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isObject() || value.size() != ROOT_FIELDS.size()) fail();
        ObjectNode input = (ObjectNode) value;
        input.properties().forEach(field -> { if (!ROOT_FIELDS.contains(field.getKey())) fail(); });
        if (!"eclipse-data-inspection/1".equals(text(input, "schemaVersion"))) fail();
        String caseName = text(input, "caseName");
        if (!validCaseName(caseName)) fail();
        ArrayNode sections = orderedStrings(input.get("sections"), SECTION_ORDER, true);
        JsonNode unit = input.get("unitSystem");
        if (!(unit == null || unit.isNull() || unit.isTextual() && UNIT_SYSTEMS.contains(unit.asText()))) fail();
        ArrayNode phases = orderedStrings(input.get("phases"), PHASE_ORDER, true);
        JsonNode dimensions = input.get("dimensions");
        if (!(dimensions == null || dimensions.isNull() || validDimensions(dimensions))) fail();
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", "eclipse-data-inspection/1");
        result.put("caseName", caseName);
        result.set("sections", sections);
        if (unit == null || unit.isNull()) result.putNull("unitSystem"); else result.put("unitSystem", unit.asText());
        result.set("phases", phases);
        if (dimensions == null || dimensions.isNull()) result.putNull("dimensions"); else result.set("dimensions", dimensions);
        return result;
    }

    private static ArrayNode orderedStrings(JsonNode value, List<String> order, boolean allowEmpty) {
        if (value == null || !value.isArray() || (!allowEmpty && value.isEmpty())) fail();
        ArrayNode result = MAPPER.createArrayNode();
        int previous = -1;
        for (JsonNode item : value) {
            if (!item.isTextual()) fail();
            int current = order.indexOf(item.asText());
            if (current < 0 || current <= previous) fail();
            result.add(item.asText()); previous = current;
        }
        return result;
    }

    private static boolean validDimensions(JsonNode value) {
        if (!value.isObject() || value.size() != 3) return false;
        for (String field : List.of("nx", "ny", "nz")) {
            JsonNode dimension = value.get(field);
            if (dimension == null || !dimension.isInt() || dimension.intValue() <= 0 || dimension.intValue() > MAX_DIMENSION) return false;
        }
        return true;
    }

    private static boolean validCaseName(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_CASE_NAME_LENGTH
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0;
    }

    private static String text(ObjectNode value, String field) {
        JsonNode node = value.get(field); return node != null && node.isTextual() ? node.asText() : null;
    }
    private static void fail() { throw new IllegalArgumentException("Invalid ECLIPSE inspection response"); }
}
