package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the small, display-safe Network Study boundary metadata contract. */
public final class PipesimNetworkInspectionValidator {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ROOT_FIELDS_V1 = Set.of("schemaVersion", "studies");
    private static final Set<String> ROOT_FIELDS_V2 = Set.of("schemaVersion", "studies", "packageFiles");
    private static final Set<String> ROOT_FIELDS_V3 = Set.of("schemaVersion", "studies", "chokes", "packageFiles");
    private static final Set<String> ROOT_FIELDS_V4 = Set.of("schemaVersion", "studies", "chokes", "packageFiles", "wellContexts", "flowlineContexts");
    private static final Set<String> ROOT_FIELDS_V5 = Set.of("schemaVersion", "studies", "chokes", "packageFiles", "wellContexts", "flowlineContexts", "flowlines");
    private static final Set<String> STUDY_FIELDS = Set.of("study", "boundaries");
    private static final Set<String> CHOKE_FIELDS = Set.of("name", "beanSize", "unit");
    private static final Set<String> FLOWLINE_FIELDS = Set.of("name", "innerDiameter", "unit");
    private static final Set<String> BOUNDARY_FIELDS = Set.of("node", "boundaryNodeType", "isActive", "isSurfaceCondition",
            "flowRateType", "pressure", "temperature", "gasFlowRate", "liquidFlowRate", "massFlowRate");
    private static final Set<String> FLOW_RATE_TYPES = Set.of("GasFlowRate", "LiquidFlowRate", "MassFlowRate");
    private static final Pattern SAFE_TEXT = Pattern.compile("[^\\p{Cntrl}]{1,255}");

    private PipesimNetworkInspectionValidator() {}

    public static boolean supports(String modelKind) { return "network".equals(modelKind); }

    public static String validateAndSerialize(JsonNode input, ObjectMapper mapper) {
        if (input == null || input.isNull()) return null;
        JsonNode validated = validate(input, mapper);
        try { return mapper.writeValueAsString(validated); }
        catch (Exception exception) { throw new IllegalArgumentException("network inspection serialization failed", exception); }
    }

    public static JsonNode parsePersisted(String value) {
        if (value == null || value.isBlank()) return null;
        try { return validate(MAPPER.readTree(value), MAPPER); }
        catch (Exception exception) { return null; }
    }

    public static JsonNode validateNode(JsonNode input) {
        if (input == null) return null;
        try { return validate(input, MAPPER); }
        catch (RuntimeException exception) { return null; }
    }

    private static JsonNode validate(JsonNode input, ObjectMapper mapper) {
        String schemaVersion = text(input, "schemaVersion");
        boolean v2 = "pipesim-network-inspection/2".equals(schemaVersion);
        boolean v3 = "pipesim-network-inspection/3".equals(schemaVersion);
        boolean v4 = "pipesim-network-inspection/4".equals(schemaVersion);
        boolean v5 = "pipesim-network-inspection/5".equals(schemaVersion);
        Set<String> rootFields = v5 ? ROOT_FIELDS_V5 : v4 ? ROOT_FIELDS_V4 : v3 ? ROOT_FIELDS_V3 : v2 ? ROOT_FIELDS_V2 : ROOT_FIELDS_V1;
        if (!input.isObject() || input.size() != rootFields.size() || !hasOnly(input, rootFields)
                || !("pipesim-network-inspection/1".equals(schemaVersion) || v2 || v3 || v4 || v5)) fail();
        JsonNode packageFiles = input.get("packageFiles");
        if ((v2 || v3 || v4 || v5) && packageFiles == null) fail();
        JsonNode studies = input.get("studies");
        if (studies == null || !studies.isArray() || studies.isEmpty() || studies.size() > 64) fail();
        ArrayNode resultStudies = mapper.createArrayNode();
        for (JsonNode study : studies) resultStudies.add(validateStudy(study, mapper));
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", schemaVersion);
        result.set("studies", resultStudies);
        if (v3 || v4 || v5) {
            JsonNode chokes = input.get("chokes");
            if (chokes == null || !chokes.isArray() || chokes.size() > 128) fail();
            ArrayNode resultChokes = mapper.createArrayNode();
            Set<String> names = new HashSet<>();
            for (JsonNode choke : chokes) {
                JsonNode normalized = validateChoke(choke, mapper);
                if (!names.add(normalized.path("name").asText())) fail();
                resultChokes.add(normalized);
            }
            result.set("chokes", resultChokes);
        }
        if (v4 || v5) {
            result.set("wellContexts", validateContexts(input.get("wellContexts"), mapper));
            result.set("flowlineContexts", validateContexts(input.get("flowlineContexts"), mapper));
        }
        if (v5) {
            ArrayNode flowlines = validateFlowlines(input.get("flowlines"), mapper);
            Set<String> flowlineNames = new HashSet<>();
            for (JsonNode flowline : flowlines) flowlineNames.add(flowline.path("name").asText());
            Set<String> flowlineContexts = new HashSet<>();
            for (JsonNode context : result.path("flowlineContexts")) flowlineContexts.add(context.asText());
            if (!flowlineNames.equals(flowlineContexts)) fail();
            result.set("flowlines", flowlines);
        }
        if (v2 || v3 || v4 || v5) result.set("packageFiles", PipesimPackageManifestValidator.validate(packageFiles, mapper));
        return result;
    }

    private static ArrayNode validateContexts(JsonNode input, ObjectMapper mapper) {
        if (input == null || !input.isArray() || input.size() > 128) fail();
        ArrayNode result = mapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode context : input) {
            if (!context.isTextual() || !safeText(context.asText(), 255) || !seen.add(context.asText())) fail();
            result.add(context.asText());
        }
        return result;
    }

    private static JsonNode validateChoke(JsonNode input, ObjectMapper mapper) {
        if (!input.isObject() || input.size() != CHOKE_FIELDS.size() || !hasOnly(input, CHOKE_FIELDS)) fail();
        String name = text(input, "name");
        String unit = text(input, "unit");
        JsonNode beanSize = input.get("beanSize");
        if (!safeText(name, 255) || !safeText(unit, 32) || beanSize == null || !beanSize.isNumber()
                || !Double.isFinite(beanSize.asDouble()) || beanSize.asDouble() <= 0 || beanSize.asDouble() > 1_000_000) fail();
        ObjectNode result = mapper.createObjectNode();
        result.put("name", name);
        result.put("beanSize", beanSize.asDouble());
        result.put("unit", unit);
        return result;
    }

    private static ArrayNode validateFlowlines(JsonNode input, ObjectMapper mapper) {
        if (input == null || !input.isArray() || input.size() > 128) fail();
        ArrayNode result = mapper.createArrayNode();
        Set<String> names = new HashSet<>();
        for (JsonNode flowline : input) {
            if (!flowline.isObject() || flowline.size() != FLOWLINE_FIELDS.size() || !hasOnly(flowline, FLOWLINE_FIELDS)) fail();
            String name = text(flowline, "name");
            String unit = text(flowline, "unit");
            JsonNode diameter = flowline.get("innerDiameter");
            if (!safeText(name, 255) || name.contains("..") || !safeText(unit, 32, true) || !names.add(name)
                    || diameter == null || !(diameter.isNull() || diameter.isNumber() && Double.isFinite(diameter.asDouble())
                    && diameter.asDouble() > 0 && diameter.asDouble() <= 1000)
                    || diameter.isNull() && !unit.isEmpty()) fail();
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put("name", name);
            if (diameter.isNull()) normalized.putNull("innerDiameter"); else normalized.put("innerDiameter", diameter.asDouble());
            normalized.put("unit", unit);
            result.add(normalized);
        }
        return result;
    }

    private static JsonNode validateStudy(JsonNode input, ObjectMapper mapper) {
        if (!input.isObject() || input.size() != STUDY_FIELDS.size() || !hasOnly(input, STUDY_FIELDS)) fail();
        String study = text(input, "study");
        if (!safeText(study, 255)) fail();
        JsonNode boundaries = input.get("boundaries");
        if (boundaries == null || !boundaries.isArray() || boundaries.isEmpty() || boundaries.size() > 256) fail();
        Set<String> nodes = new HashSet<>();
        ArrayNode resultBoundaries = mapper.createArrayNode();
        for (JsonNode boundary : boundaries) {
            JsonNode normalized = validateBoundary(boundary, mapper);
            if (!nodes.add(normalized.path("node").asText())) fail();
            resultBoundaries.add(normalized);
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("study", study);
        result.set("boundaries", resultBoundaries);
        return result;
    }

    private static JsonNode validateBoundary(JsonNode input, ObjectMapper mapper) {
        if (!input.isObject() || input.size() != BOUNDARY_FIELDS.size() || !hasOnly(input, BOUNDARY_FIELDS)) fail();
        String node = text(input, "node");
        String boundaryNodeType = text(input, "boundaryNodeType");
        if (!safeText(node, 255) || !safeText(boundaryNodeType, 64)
                || !input.path("isActive").isBoolean() || !input.path("isSurfaceCondition").isBoolean()) fail();
        JsonNode flowRateType = input.get("flowRateType");
        if (!(flowRateType == null || flowRateType.isNull() || flowRateType.isTextual() && FLOW_RATE_TYPES.contains(flowRateType.asText()))) fail();
        validateNumber(input.get("pressure"), 0, 100000);
        validateNumber(input.get("temperature"), -1000, 100000);
        validateNumber(input.get("gasFlowRate"), 0, 1000000000);
        validateNumber(input.get("liquidFlowRate"), 0, 1000000000);
        validateNumber(input.get("massFlowRate"), 0, 1000000000);

        ObjectNode result = mapper.createObjectNode();
        result.put("node", node);
        result.put("boundaryNodeType", boundaryNodeType);
        result.put("isActive", input.path("isActive").booleanValue());
        result.put("isSurfaceCondition", input.path("isSurfaceCondition").booleanValue());
        if (flowRateType == null || flowRateType.isNull()) result.putNull("flowRateType"); else result.put("flowRateType", flowRateType.asText());
        copyNumber(result, input, "pressure");
        copyNumber(result, input, "temperature");
        copyNumber(result, input, "gasFlowRate");
        copyNumber(result, input, "liquidFlowRate");
        copyNumber(result, input, "massFlowRate");
        return result;
    }

    private static void validateNumber(JsonNode value, double lowerExclusive, double upperInclusive) {
        if (value == null || value.isNull()) return;
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() <= lowerExclusive || value.asDouble() > upperInclusive) fail();
    }

    private static void copyNumber(ObjectNode target, JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) target.putNull(field); else target.put(field, value.asDouble());
    }

    private static boolean hasOnly(JsonNode value, Set<String> fields) {
        for (String name : value.propertyNames()) if (!fields.contains(name)) return false;
        return true;
    }

    private static String text(JsonNode value, String field) {
        JsonNode node = value.get(field);
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private static boolean safeText(String value, int maxLength) {
        return value != null && value.length() <= maxLength && SAFE_TEXT.matcher(value).matches()
                && !value.contains("/") && !value.contains("\\") && !value.contains("..") && !value.contains("://");
    }

    private static boolean safeText(String value, int maxLength, boolean allowEmpty) {
        return (allowEmpty && value != null && value.isEmpty()) || safeText(value, maxLength);
    }

    private static void fail() { throw new IllegalArgumentException("Invalid PIPESIM Network inspection response"); }
}
