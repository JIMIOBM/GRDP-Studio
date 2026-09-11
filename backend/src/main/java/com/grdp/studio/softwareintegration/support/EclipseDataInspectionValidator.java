package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Allows only the small, display-safe ECLIPSE deck metadata contract. */
public final class EclipseDataInspectionValidator {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SECTION_ORDER = List.of("RUNSPEC", "GRID", "EDIT", "PROPS", "REGIONS", "SOLUTION", "SUMMARY", "SCHEDULE");
    private static final List<String> PHASE_ORDER = List.of("OIL", "WATER", "GAS");
    private static final Set<String> ROOT_FIELDS_V1 = Set.of("schemaVersion", "caseName", "sections", "unitSystem", "phases", "dimensions");
    private static final Set<String> ROOT_FIELDS_V2 = Set.of("schemaVersion", "caseName", "sections", "unitSystem", "phases", "dimensions", "wellNames", "scheduleTimeline");
    private static final Set<String> DATE_FIELDS = Set.of("day", "month", "year", "time");
    private static final Set<String> DATES_EVENT_FIELDS = Set.of("kind", "records");
    private static final Set<String> TSTEP_EVENT_FIELDS = Set.of("kind", "steps");
    private static final Set<String> UNIT_SYSTEMS = Set.of("METRIC", "FIELD", "LAB", "PVT-M");
    private static final Set<String> MONTHS = Set.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
    private static final int MAX_CASE_NAME_LENGTH = 255;
    private static final int MAX_DIMENSION = 1_000_000;
    private static final int MAX_WELL_NAMES = 1_000;
    private static final int MAX_SCHEDULE_EVENTS = 1_000;
    private static final int MAX_DATE_RECORDS = 4_000;
    private static final int MAX_TSTEP_STEPS = 8_000;
    private static final int MAX_WELL_NAME_SCALARS = 128;
    private static final Pattern DAY = Pattern.compile("(?:[1-9]|[12][0-9]|3[01])");
    private static final Pattern YEAR = Pattern.compile("[0-9]{4}");
    private static final Pattern TIME = Pattern.compile("(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?");
    private static final Pattern DECIMAL = Pattern.compile("\\+?[0-9]+(?:\\.[0-9]+)?(?:[Ee][+-]?[0-9]+)?");
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
        if (value == null || !value.isObject()) fail();
        ObjectNode input = (ObjectNode) value;
        String schemaVersion = text(input, "schemaVersion");
        if ("eclipse-data-inspection/1".equals(schemaVersion)) return validateV1(input, mapper);
        if ("eclipse-data-inspection/2".equals(schemaVersion)) return validateV2(input, mapper);
        fail();
        return null;
    }

    private static JsonNode validateV1(ObjectNode input, ObjectMapper mapper) {
        if (input.size() != ROOT_FIELDS_V1.size()) fail();
        input.properties().forEach(field -> { if (!ROOT_FIELDS_V1.contains(field.getKey())) fail(); });
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

    private static JsonNode validateV2(ObjectNode input, ObjectMapper mapper) {
        if (input.size() != ROOT_FIELDS_V2.size()) fail();
        input.properties().forEach(field -> { if (!ROOT_FIELDS_V2.contains(field.getKey())) fail(); });
        ObjectNode result = (ObjectNode) validateV1Fields(input, mapper, "eclipse-data-inspection/2");
        result.set("wellNames", wellNames(input.get("wellNames"), mapper));
        result.set("scheduleTimeline", scheduleTimeline(input.get("scheduleTimeline"), mapper));
        return result;
    }

    private static JsonNode validateV1Fields(ObjectNode input, ObjectMapper mapper, String schemaVersion) {
        String caseName = text(input, "caseName");
        if (!validCaseName(caseName)) fail();
        ArrayNode sections = orderedStrings(input.get("sections"), SECTION_ORDER, true);
        JsonNode unit = input.get("unitSystem");
        if (!(unit == null || unit.isNull() || unit.isTextual() && UNIT_SYSTEMS.contains(unit.asText()))) fail();
        ArrayNode phases = orderedStrings(input.get("phases"), PHASE_ORDER, true);
        JsonNode dimensions = input.get("dimensions");
        if (!(dimensions == null || dimensions.isNull() || validDimensions(dimensions))) fail();
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", schemaVersion);
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

    private static ArrayNode wellNames(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isArray() || value.size() > MAX_WELL_NAMES) fail();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode item : value) {
            if (!item.isTextual() || !validWellName(item.asText())) fail();
            result.add(item.asText());
        }
        return result;
    }

    private static ArrayNode scheduleTimeline(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isArray() || value.size() > MAX_SCHEDULE_EVENTS) fail();
        ArrayNode result = mapper.createArrayNode();
        int totalDateRecords = 0;
        int totalTstepSteps = 0;
        for (JsonNode event : value) {
            ObjectNode validatedEvent = scheduleEvent(event, mapper);
            if ("DATES".equals(validatedEvent.get("kind").asText())) {
                totalDateRecords += validatedEvent.get("records").size();
                if (totalDateRecords > MAX_DATE_RECORDS) fail();
            } else {
                totalTstepSteps += validatedEvent.get("steps").size();
                if (totalTstepSteps > MAX_TSTEP_STEPS) fail();
            }
            result.add(validatedEvent);
        }
        return result;
    }

    private static ObjectNode scheduleEvent(JsonNode value, ObjectMapper mapper) {
        if (!value.isObject()) fail();
        ObjectNode input = (ObjectNode) value;
        String kind = text(input, "kind");
        if ("DATES".equals(kind)) {
            if (input.size() != DATES_EVENT_FIELDS.size()) fail();
            input.properties().forEach(field -> { if (!DATES_EVENT_FIELDS.contains(field.getKey())) fail(); });
            JsonNode records = input.get("records");
            if (records == null || !records.isArray() || records.size() > MAX_DATE_RECORDS) fail();
            ArrayNode outputRecords = mapper.createArrayNode();
            for (JsonNode record : records) outputRecords.add(dateRecord(record, mapper));
            ObjectNode result = mapper.createObjectNode();
            result.put("kind", "DATES");
            result.set("records", outputRecords);
            return result;
        }
        if ("TSTEP".equals(kind)) {
            if (input.size() != TSTEP_EVENT_FIELDS.size()) fail();
            input.properties().forEach(field -> { if (!TSTEP_EVENT_FIELDS.contains(field.getKey())) fail(); });
            JsonNode steps = input.get("steps");
            if (steps == null || !steps.isArray() || steps.size() > MAX_TSTEP_STEPS) fail();
            ArrayNode outputSteps = mapper.createArrayNode();
            for (JsonNode step : steps) {
                if (!step.isTextual() || !validTstep(step.asText())) fail();
                outputSteps.add(step.asText());
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("kind", "TSTEP");
            result.set("steps", outputSteps);
            return result;
        }
        fail();
        return null;
    }

    private static ObjectNode dateRecord(JsonNode value, ObjectMapper mapper) {
        if (!value.isObject() || value.size() != DATE_FIELDS.size()) fail();
        ObjectNode input = (ObjectNode) value;
        input.properties().forEach(field -> { if (!DATE_FIELDS.contains(field.getKey())) fail(); });
        String day = text(input, "day");
        String month = text(input, "month");
        String year = text(input, "year");
        JsonNode time = input.get("time");
        if (day == null || !DAY.matcher(day).matches() || month == null || !MONTHS.contains(month)
                || year == null || !YEAR.matcher(year).matches()
                || !(time != null && (time.isNull() || time.isTextual() && TIME.matcher(time.asText()).matches()))) fail();
        ObjectNode result = mapper.createObjectNode();
        result.put("day", day);
        result.put("month", month);
        result.put("year", year);
        if (time.isNull()) result.putNull("time"); else result.put("time", time.asText());
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

    private static boolean validTstep(String value) {
        if (!DECIMAL.matcher(value).matches()) return false;
        double parsed = Double.parseDouble(value);
        return Double.isFinite(parsed) && parsed >= 0;
    }

    private static boolean validCaseName(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_CASE_NAME_LENGTH
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0;
    }

    private static boolean validWellName(String value) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > MAX_WELL_NAME_SCALARS
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || value.contains("..")) return false;
        for (int index = 0; index < value.length();) {
            char character = value.charAt(index);
            if (Character.isSurrogate(character) && (index + 1 >= value.length() || !Character.isSurrogatePair(character, value.charAt(index + 1)))) return false;
            int codePoint = value.codePointAt(index);
            if (Character.isISOControl(codePoint)) return false;
            index += Character.charCount(codePoint);
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return !(normalized.contains("net.pipe") || normalized.contains("password") || normalized.contains("passwd")
                || normalized.contains("secret") || normalized.contains("token") || normalized.contains("credential")
                || normalized.contains("cookie") || normalized.contains("authorization") || normalized.contains("bearer")
                || normalized.contains("license") || normalized.contains("api_key") || normalized.contains("apikey")
                || normalized.contains("server") || normalized.contains("environment"));
    }

    private static String text(ObjectNode value, String field) {
        JsonNode node = value.get(field); return node != null && node.isTextual() ? node.asText() : null;
    }
    private static void fail() { throw new IllegalArgumentException("Invalid ECLIPSE inspection response"); }
}
