package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.HashSet;
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
    private static final Set<String> ROOT_FIELDS_V3 = Set.of("schemaVersion", "caseName", "sections", "unitSystem", "phases", "dimensions", "wellNames", "scheduleTimeline", "packageFiles");
    private static final Set<String> ROOT_FIELDS_V4 = Set.of("schemaVersion", "caseName", "sections", "unitSystem", "phases", "dimensions", "wellNames", "scheduleTimeline", "packageFiles", "scheduleMetadata");
    private static final Set<String> PACKAGE_FILE_FIELDS = Set.of("relativePath", "sizeBytes", "sha256");
    private static final Set<String> SCHEDULE_METADATA_FIELDS = Set.of("wells", "groups", "records", "completions");
    private static final Set<String> SCHEDULE_WELL_FIELDS = Set.of("name", "group", "sourceFile", "lineNumber");
    private static final Set<String> SCHEDULE_GROUP_FIELDS = Set.of("name", "parent", "sourceFile", "lineNumber");
    private static final Set<String> SCHEDULE_RECORD_FIELDS = Set.of("keyword", "values", "sourceFile", "lineNumber");
    private static final Set<String> SCHEDULE_COMPLETION_FIELDS = Set.of("keyword", "well", "i", "j", "k1", "k2", "status", "sourceFile", "lineNumber");
    private static final Set<String> SCHEDULE_RECORD_KEYWORDS = Set.of("WELSPECS", "WELSPECL", "GRUPTREE", "COMPDAT", "COMPDATM", "WELOPEN", "WCONHIST", "WCONINJE", "WCONPROD");
    private static final Set<String> DATE_FIELDS = Set.of("day", "month", "year", "time");
    private static final Set<String> DATES_EVENT_FIELDS = Set.of("kind", "records");
    private static final Set<String> TSTEP_EVENT_FIELDS = Set.of("kind", "steps");
    private static final Set<String> DATES_EVENT_FIELDS_V4 = Set.of("kind", "records", "sourceFile", "lineNumber");
    private static final Set<String> TSTEP_EVENT_FIELDS_V4 = Set.of("kind", "steps", "sourceFile", "lineNumber");
    private static final Set<String> UNIT_SYSTEMS = Set.of("METRIC", "FIELD", "LAB", "PVT-M");
    private static final Set<String> MONTHS = Set.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
    private static final int MAX_CASE_NAME_LENGTH = 255;
    private static final int MAX_DIMENSION = 1_000_000;
    private static final int MAX_WELL_NAMES = 1_000;
    private static final int MAX_SCHEDULE_EVENTS = 1_000;
    private static final int MAX_DATE_RECORDS = 4_000;
    private static final int MAX_TSTEP_STEPS = 8_000;
    private static final int MAX_SCHEDULE_METADATA_WELLS = 1_000;
    private static final int MAX_SCHEDULE_METADATA_GROUPS = 1_000;
    private static final int MAX_SCHEDULE_METADATA_RECORDS = 4_000;
    private static final int MAX_SCHEDULE_METADATA_VALUES = 128;
    private static final int MAX_SCHEDULE_COMPLETIONS = 4_000;
    private static final int MAX_WELL_NAME_SCALARS = 128;
    private static final Pattern DAY = Pattern.compile("(?:[1-9]|[12][0-9]|3[01])");
    private static final Pattern YEAR = Pattern.compile("[0-9]{4}");
    private static final Pattern TIME = Pattern.compile("(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?");
    private static final Pattern DECIMAL = Pattern.compile("\\+?[0-9]+(?:\\.[0-9]+)?(?:[Ee][+-]?[0-9]+)?");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
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
        if ("eclipse-data-inspection/3".equals(schemaVersion)) return validateV3(input, mapper);
        if ("eclipse-data-inspection/4".equals(schemaVersion)) return validateV4(input, mapper);
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

    private static JsonNode validateV3(ObjectNode input, ObjectMapper mapper) {
        if (input.size() != ROOT_FIELDS_V3.size()) fail();
        input.properties().forEach(field -> { if (!ROOT_FIELDS_V3.contains(field.getKey())) fail(); });
        ObjectNode result = (ObjectNode) validateV1Fields(input, mapper, "eclipse-data-inspection/3");
        result.set("wellNames", wellNames(input.get("wellNames"), mapper));
        result.set("scheduleTimeline", scheduleTimeline(input.get("scheduleTimeline"), mapper));
        result.set("packageFiles", packageFiles(input.get("packageFiles"), mapper));
        return result;
    }

    private static JsonNode validateV4(ObjectNode input, ObjectMapper mapper) {
        if (input.size() != ROOT_FIELDS_V4.size()) fail();
        input.properties().forEach(field -> { if (!ROOT_FIELDS_V4.contains(field.getKey())) fail(); });
        ObjectNode result = (ObjectNode) validateV1Fields(input, mapper, "eclipse-data-inspection/4");
        result.set("wellNames", wellNames(input.get("wellNames"), mapper));
        result.set("scheduleTimeline", scheduleTimeline(input.get("scheduleTimeline"), mapper, true));
        result.set("packageFiles", packageFiles(input.get("packageFiles"), mapper));
        result.set("scheduleMetadata", scheduleMetadata(input.get("scheduleMetadata"), mapper));
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
        return scheduleTimeline(value, mapper, false);
    }

    private static ArrayNode scheduleTimeline(JsonNode value, ObjectMapper mapper, boolean withSource) {
        if (value == null || !value.isArray() || value.size() > MAX_SCHEDULE_EVENTS) fail();
        ArrayNode result = mapper.createArrayNode();
        int totalDateRecords = 0;
        int totalTstepSteps = 0;
        for (JsonNode event : value) {
            ObjectNode validatedEvent = scheduleEvent(event, mapper, withSource);
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

    private static ArrayNode packageFiles(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > 4096) fail();
        Set<String> paths = new HashSet<>();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode file : value) {
            if (file == null || !file.isObject() || file.size() != PACKAGE_FILE_FIELDS.size()) fail();
            file.properties().forEach(field -> { if (!PACKAGE_FILE_FIELDS.contains(field.getKey())) fail(); });
            String relativePath = text((ObjectNode) file, "relativePath");
            JsonNode size = file.get("sizeBytes");
            String sha256 = text((ObjectNode) file, "sha256");
            if (!validRelativePath(relativePath) || !paths.add(relativePath)
                    || size == null || !size.isIntegralNumber() || !size.canConvertToLong()
                    || size.longValue() < 0 || size.longValue() > 64L * 1024 * 1024
                    || sha256 == null || !SHA256.matcher(sha256).matches()) fail();
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put("relativePath", relativePath);
            normalized.put("sizeBytes", size.longValue());
            normalized.put("sha256", sha256);
            result.add(normalized);
        }
        return result;
    }

    private static ObjectNode scheduleMetadata(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isObject() || value.size() != SCHEDULE_METADATA_FIELDS.size()) fail();
        ObjectNode input = (ObjectNode) value;
        input.properties().forEach(field -> { if (!SCHEDULE_METADATA_FIELDS.contains(field.getKey())) fail(); });
        ArrayNode wells = scheduleWells(input.get("wells"), mapper);
        ArrayNode groups = scheduleGroups(input.get("groups"), mapper);
        ArrayNode records = scheduleRecords(input.get("records"), mapper);
        ArrayNode completions = scheduleCompletions(input.get("completions"), mapper);
        ObjectNode result = mapper.createObjectNode();
        result.set("wells", wells);
        result.set("groups", groups);
        result.set("records", records);
        result.set("completions", completions);
        return result;
    }

    private static ArrayNode scheduleWells(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isArray() || value.size() > MAX_SCHEDULE_METADATA_WELLS) fail();
        Set<String> names = new HashSet<>();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode item : value) {
            if (item == null || !item.isObject() || item.size() != SCHEDULE_WELL_FIELDS.size()) fail();
            item.properties().forEach(field -> { if (!SCHEDULE_WELL_FIELDS.contains(field.getKey())) fail(); });
            ObjectNode input = (ObjectNode) item;
            String name = text(input, "name");
            JsonNode group = input.get("group");
            String sourceFile = text(input, "sourceFile");
            JsonNode lineNumber = input.get("lineNumber");
            if (!validWellName(name) || !names.add(name) || group == null || !(group.isNull() || group.isTextual() && validWellName(group.asText()))
                    || !validSource(sourceFile, lineNumber)) fail();
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put("name", name);
            if (group.isNull()) normalized.putNull("group"); else normalized.put("group", group.asText());
            normalized.put("sourceFile", sourceFile);
            normalized.put("lineNumber", lineNumber.intValue());
            result.add(normalized);
        }
        return result;
    }

    private static ArrayNode scheduleGroups(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isArray() || value.size() > MAX_SCHEDULE_METADATA_GROUPS) fail();
        Set<String> names = new HashSet<>();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode item : value) {
            if (item == null || !item.isObject() || item.size() != SCHEDULE_GROUP_FIELDS.size()) fail();
            item.properties().forEach(field -> { if (!SCHEDULE_GROUP_FIELDS.contains(field.getKey())) fail(); });
            ObjectNode input = (ObjectNode) item;
            String name = text(input, "name");
            JsonNode parent = input.get("parent");
            String sourceFile = text(input, "sourceFile");
            JsonNode lineNumber = input.get("lineNumber");
            if (!validWellName(name) || !names.add(name) || parent == null || !(parent.isNull() || parent.isTextual() && validWellName(parent.asText()))
                    || !validSource(sourceFile, lineNumber)) fail();
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put("name", name);
            if (parent.isNull()) normalized.putNull("parent"); else normalized.put("parent", parent.asText());
            normalized.put("sourceFile", sourceFile);
            normalized.put("lineNumber", lineNumber.intValue());
            result.add(normalized);
        }
        return result;
    }

    private static ArrayNode scheduleRecords(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isArray() || value.size() > MAX_SCHEDULE_METADATA_RECORDS) fail();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode item : value) {
            if (item == null || !item.isObject() || item.size() != SCHEDULE_RECORD_FIELDS.size()) fail();
            item.properties().forEach(field -> { if (!SCHEDULE_RECORD_FIELDS.contains(field.getKey())) fail(); });
            ObjectNode input = (ObjectNode) item;
            String keyword = text(input, "keyword");
            JsonNode values = input.get("values");
            String sourceFile = text(input, "sourceFile");
            JsonNode lineNumber = input.get("lineNumber");
            if (keyword == null || !SCHEDULE_RECORD_KEYWORDS.contains(keyword) || values == null || !values.isArray()
                    || values.size() == 0 || values.size() > MAX_SCHEDULE_METADATA_VALUES || !validSource(sourceFile, lineNumber)) fail();
            ArrayNode normalizedValues = mapper.createArrayNode();
            for (JsonNode itemValue : values) {
                if (!itemValue.isTextual() || !validMetadataValue(itemValue.asText())) fail();
                normalizedValues.add(itemValue.asText());
            }
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put("keyword", keyword);
            normalized.set("values", normalizedValues);
            normalized.put("sourceFile", sourceFile);
            normalized.put("lineNumber", lineNumber.intValue());
            result.add(normalized);
        }
        return result;
    }

    private static ArrayNode scheduleCompletions(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isArray() || value.size() > MAX_SCHEDULE_COMPLETIONS) fail();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode item : value) {
            if (item == null || !item.isObject() || item.size() != SCHEDULE_COMPLETION_FIELDS.size()) fail();
            item.properties().forEach(field -> { if (!SCHEDULE_COMPLETION_FIELDS.contains(field.getKey())) fail(); });
            ObjectNode input = (ObjectNode) item;
            String keyword = text(input, "keyword");
            String well = text(input, "well");
            String i = text(input, "i");
            String j = text(input, "j");
            String k1 = text(input, "k1");
            String k2 = text(input, "k2");
            String status = text(input, "status");
            String sourceFile = text(input, "sourceFile");
            JsonNode lineNumber = input.get("lineNumber");
            if (keyword == null || !(keyword.equals("COMPDAT") || keyword.equals("COMPDATM")) || !validWellName(well)
                    || !validMetadataValue(i) || !validMetadataValue(j) || !validMetadataValue(k1)
                    || !validMetadataValue(k2) || !validMetadataValue(status) || !validSource(sourceFile, lineNumber)) fail();
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put("keyword", keyword);
            normalized.put("well", well);
            normalized.put("i", i);
            normalized.put("j", j);
            normalized.put("k1", k1);
            normalized.put("k2", k2);
            normalized.put("status", status);
            normalized.put("sourceFile", sourceFile);
            normalized.put("lineNumber", lineNumber.intValue());
            result.add(normalized);
        }
        return result;
    }

    private static ObjectNode scheduleEvent(JsonNode value, ObjectMapper mapper) {
        return scheduleEvent(value, mapper, false);
    }

    private static ObjectNode scheduleEvent(JsonNode value, ObjectMapper mapper, boolean withSource) {
        if (!value.isObject()) fail();
        ObjectNode input = (ObjectNode) value;
        String kind = text(input, "kind");
        if ("DATES".equals(kind)) {
            Set<String> fields = withSource ? DATES_EVENT_FIELDS_V4 : DATES_EVENT_FIELDS;
            if (input.size() != fields.size()) fail();
            input.properties().forEach(field -> { if (!fields.contains(field.getKey())) fail(); });
            JsonNode records = input.get("records");
            if (records == null || !records.isArray() || records.size() > MAX_DATE_RECORDS) fail();
            ArrayNode outputRecords = mapper.createArrayNode();
            for (JsonNode record : records) outputRecords.add(dateRecord(record, mapper));
            ObjectNode result = mapper.createObjectNode();
            result.put("kind", "DATES");
            result.set("records", outputRecords);
            if (withSource) addSource(result, input);
            return result;
        }
        if ("TSTEP".equals(kind)) {
            Set<String> fields = withSource ? TSTEP_EVENT_FIELDS_V4 : TSTEP_EVENT_FIELDS;
            if (input.size() != fields.size()) fail();
            input.properties().forEach(field -> { if (!fields.contains(field.getKey())) fail(); });
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
            if (withSource) addSource(result, input);
            return result;
        }
        fail();
        return null;
    }

    private static void addSource(ObjectNode result, ObjectNode input) {
        String sourceFile = text(input, "sourceFile");
        JsonNode lineNumber = input.get("lineNumber");
        if (!validSource(sourceFile, lineNumber)) fail();
        result.put("sourceFile", sourceFile);
        result.put("lineNumber", lineNumber.intValue());
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

    private static boolean validRelativePath(String value) {
        return value != null && !value.isEmpty() && value.length() <= 512
                && value.charAt(0) != '/' && value.charAt(value.length() - 1) != '/'
                && !value.contains("\\") && !value.contains(":") && !value.contains("..")
                && !value.contains("//") && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean validWellName(String value) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > MAX_WELL_NAME_SCALARS
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.contains("://") || value.contains("..")) return false;
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

    private static boolean validMetadataValue(String value) {
        if (value == null || value.isEmpty() || value.length() > 1024 || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.contains("://") || value.contains("..") || value.chars().anyMatch(Character::isISOControl)) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return !(normalized.contains("net.pipe") || normalized.contains("password") || normalized.contains("passwd")
                || normalized.contains("secret") || normalized.contains("token") || normalized.contains("credential")
                || normalized.contains("cookie") || normalized.contains("authorization") || normalized.contains("bearer")
                || normalized.contains("license") || normalized.contains("api_key") || normalized.contains("apikey"));
    }

    private static boolean validSource(String sourceFile, JsonNode lineNumber) {
        return validRelativePath(sourceFile) && lineNumber != null && lineNumber.isIntegralNumber()
                && lineNumber.canConvertToInt() && lineNumber.intValue() > 0 && lineNumber.intValue() <= 2_000_000;
    }

    private static String text(ObjectNode value, String field) {
        JsonNode node = value.get(field); return node != null && node.isTextual() ? node.asText() : null;
    }
    private static void fail() { throw new IllegalArgumentException("Invalid ECLIPSE inspection response"); }
}
