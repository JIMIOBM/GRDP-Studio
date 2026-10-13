package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict contract for redirecting one existing Network edge in a derived PIPESIM model. */
public final class NetworkConnectionParameters {
    public static final String SCHEMA = "pipesim-network-connection-parameters/1";
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "baselineRunId", "source", "sourcePort", "originalDestination", "newDestination");
    private static final Pattern NAME = Pattern.compile("^[^\\p{Cntrl}/\\\\]{1,255}$");
    private static final Set<String> SEPARATOR_TYPES = Set.of("TwoPhaseSeparator", "ThreePhaseSeparator");
    private static final Set<String> MULTI_INLET_TYPES = Set.of("Junction");

    private NetworkConnectionParameters() {}

    public static boolean valid(JsonNode value) {
        if (value == null || !value.isObject() || !SCHEMA.equals(value.path("schemaVersion").asText())) return false;
        Set<String> fields = new HashSet<>();
        value.properties().forEach(field -> fields.add(field.getKey()));
        if (!fields.equals(FIELDS) || !value.path("baselineRunId").isIntegralNumber()
                || !value.path("baselineRunId").canConvertToLong() || value.path("baselineRunId").asLong() <= 0
                || !safeName(value.path("source")) || !safeName(value.path("originalDestination"))
                || !safeName(value.path("newDestination")) || !safeText(value.path("sourcePort"), 64, true)) return false;
        String source = value.path("source").asText();
        String original = value.path("originalDestination").asText();
        String target = value.path("newDestination").asText();
        if (source.equals(original) || source.equals(target) || original.equals(target)) return false;
        String port = value.path("sourcePort").asText();
        return port.isEmpty() || "Top Port".equals(port) || "Bottom Port".equals(port);
    }

    public static boolean matchesBaseline(JsonNode value, JsonNode result) {
        if (!valid(value) || result == null || !result.isObject()
                || !"pipesim-network-result/1".equals(result.path("schemaVersion").asText())
                || !"network".equals(result.path("model_kind").asText())
                || !"network".equals(result.path("runTask").asText())
                || !"VALID_FULL".equals(result.path("resultContract").asText())) return false;
        JsonNode topology = result.path("topology");
        JsonNode nodes = topology.path("nodes");
        JsonNode edges = topology.path("edges");
        if (!nodes.isArray() || !edges.isArray() || nodes.isEmpty() || edges.isEmpty()) return false;
        Map<String, String> componentTypes = new HashMap<>();
        for (JsonNode node : nodes) {
            if (!safeName(node.path("id")) || !safeText(node.path("componentType"), 64, false)) return false;
            if (componentTypes.putIfAbsent(node.path("id").asText(), node.path("componentType").asText()) != null) return false;
        }
        String source = value.path("source").asText();
        String oldDestination = value.path("originalDestination").asText();
        String newDestination = value.path("newDestination").asText();
        if (!componentTypes.containsKey(source) || !componentTypes.containsKey(oldDestination)
                || !componentTypes.containsKey(newDestination)
                || "Unknown".equals(componentTypes.get(source)) || "Unknown".equals(componentTypes.get(oldDestination))
                || "Unknown".equals(componentTypes.get(newDestination))) return false;
        String sourceType = componentTypes.get(source);
        String port = value.path("sourcePort").asText();
        if (SEPARATOR_TYPES.contains(sourceType)
                ? !("Top Port".equals(port) || "Bottom Port".equals(port))
                : !port.isEmpty()) return false;
        if (targetInletUnavailable(value, result) || originalFlowlineInletWouldBeDisconnected(value, result)) return false;
        int oldEdgeMatches = 0;
        for (JsonNode edge : edges) {
            if (!edge.isObject() || !safeName(edge.path("source")) || !safeName(edge.path("destination"))
                    || !safeText(edge.path("sourcePort"), 64, true)) return false;
            String edgeSource = edge.path("source").asText();
            String edgeDestination = edge.path("destination").asText();
            String edgePort = edge.path("sourcePort").asText();
            if (source.equals(edgeSource) && oldDestination.equals(edgeDestination) && port.equals(edgePort)) oldEdgeMatches++;
            if (source.equals(edgeSource) && newDestination.equals(edgeDestination)) return false;
        }
        return oldEdgeMatches == 1;
    }

    /** Conservative preflight: block targets without a known free inlet and source-only components. */
    public static boolean targetInletUnavailable(JsonNode value, JsonNode result) {
        if (!valid(value) || result == null || !result.isObject()
                || !"pipesim-network-result/1".equals(result.path("schemaVersion").asText())
                || !"VALID_FULL".equals(result.path("resultContract").asText())) return false;
        String target = value.path("newDestination").asText();
        JsonNode nodes = result.path("topology").path("nodes");
        JsonNode edges = result.path("topology").path("edges");
        if (!nodes.isArray() || !edges.isArray()) return false;
        String targetType = null;
        for (JsonNode node : nodes) {
            if (target.equals(node.path("id").asText())) {
                targetType = node.path("componentType").asText();
                break;
            }
        }
        if (targetType == null || "Source".equals(targetType)) return "Source".equals(targetType);
        if (MULTI_INLET_TYPES.contains(targetType)) return false;
        for (JsonNode edge : edges) {
            if (target.equals(edge.path("destination").asText())) return true;
        }
        return false;
    }

    /** A Flowline's two native connections must remain populated after a one-edge redirect. */
    public static boolean originalFlowlineInletWouldBeDisconnected(JsonNode value, JsonNode result) {
        if (!valid(value) || result == null || !result.isObject()
                || !"pipesim-network-result/1".equals(result.path("schemaVersion").asText())
                || !"VALID_FULL".equals(result.path("resultContract").asText())) return false;
        String source = value.path("source").asText();
        String sourcePort = value.path("sourcePort").asText();
        String oldDestination = value.path("originalDestination").asText();
        JsonNode nodes = result.path("topology").path("nodes");
        JsonNode edges = result.path("topology").path("edges");
        if (!nodes.isArray() || !edges.isArray()) return false;
        boolean flowline = false;
        for (JsonNode node : nodes) {
            if (oldDestination.equals(node.path("id").asText())) {
                flowline = "Flowline".equals(node.path("componentType").asText());
                break;
            }
        }
        if (!flowline) return false;
        int inbound = 0;
        boolean selectedEdgeIsInbound = false;
        for (JsonNode edge : edges) {
            if (!oldDestination.equals(edge.path("destination").asText())) continue;
            inbound++;
            if (source.equals(edge.path("source").asText()) && sourcePort.equals(edge.path("sourcePort").asText())) {
                selectedEdgeIsInbound = true;
            }
        }
        return selectedEdgeIsInbound && inbound == 1;
    }

    private static boolean safeName(JsonNode value) {
        if (value == null || !value.isTextual()) return false;
        String text = value.asText();
        return NAME.matcher(text).matches() && !text.contains("..") && !text.contains("://");
    }

    private static boolean safeText(JsonNode value, int maximum, boolean allowEmpty) {
        if (value == null || !value.isTextual()) return false;
        String text = value.asText();
        return text.length() <= maximum && (allowEmpty || !text.isBlank())
                && text.chars().noneMatch(Character::isISOControl);
    }
}
