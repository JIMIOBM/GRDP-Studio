package com.grdp.studio.softwareintegration.execution;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

@Component
public class PipesimVfpTablesResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "model_kind", "runTask", "resultContract", "producer", "reservoirSimulator",
            "tableNumber", "includeTemperature", "bottomHoleDatumDepth", "axes", "table", "temperatureTable",
            "vfpTableContent", "vfpTableWithTemperatureContent");
    private static final Set<String> AXES_FIELDS = Set.of(
            "liquidRatesStbPerDay", "outletPressuresPsi", "waterCutFraction", "gorMscfPerStb",
            "artificialLiftInjectionDpPsi");
    private static final Set<String> TABLE_FIELDS = Set.of("valueName", "unit", "rows");
    private static final Set<String> ROW_FIELDS = Set.of(
            "thpIndex", "waterCutIndex", "gorIndex", "artificialLiftIndex", "values");

    public PipesimWellResultValidator.ValidatedResult validate(JsonNode result) {
        requireObject(result, ROOT_FIELDS, "VFP tables result");
        requireText(result, "schemaVersion", "pipesim-vfp-tables-result/2");
        requireText(result, "model_kind", "black_oil_liquid");
        requireText(result, "runTask", "vfp-tables");
        requireText(result, "resultContract", "VALID_FULL");
        requireSafeText(result, "producer");
        requireText(result, "reservoirSimulator", "ECLIPSE");
        require(result.path("tableNumber").isIntegralNumber() && result.path("tableNumber").asInt() > 0,
                "Invalid tableNumber");
        require(result.path("includeTemperature").isBoolean(), "Invalid includeTemperature");
        requireNumber(result.path("bottomHoleDatumDepth"), 0D, 100000D, "bottomHoleDatumDepth");
        validateAxes(result.path("axes"));
        Set<String> bhpRows = validateTable(result.path("table"), "BHP", "psia", result.path("axes"), false);
        Set<String> temperatureRows = validateTable(result.path("temperatureTable"), "TEMP", "F", result.path("axes"), !result.path("includeTemperature").asBoolean());
        require(!result.path("includeTemperature").asBoolean() || bhpRows.equals(temperatureRows),
                "BHP and temperature VFP row grids do not match");
        requireSafeContent(result, "vfpTableContent");
        requireSafeContent(result, "vfpTableWithTemperatureContent");
        return new PipesimWellResultValidator.ValidatedResult(
                SoftwareIntegrationRunStatus.SUCCEEDED, "VALID_FULL", result);
    }

    private static void validateAxes(JsonNode axes) {
        requireObject(axes, AXES_FIELDS, "VFP axes");
        requireArray(axes.path("liquidRatesStbPerDay"), 1, 16, "liquidRatesStbPerDay");
        requireArray(axes.path("outletPressuresPsi"), 1, 16, "outletPressuresPsi");
        requireArray(axes.path("waterCutFraction"), 1, 16, "waterCutFraction");
        requireArray(axes.path("gorMscfPerStb"), 1, 16, "gorMscfPerStb");
        requireArray(axes.path("artificialLiftInjectionDpPsi"), 1, 16, "artificialLiftInjectionDpPsi");
    }

    private static Set<String> validateTable(JsonNode table, String valueName, String unit, JsonNode axes, boolean allowEmpty) {
        requireObject(table, TABLE_FIELDS, "VFP table");
        requireText(table, "valueName", valueName);
        requireText(table, "unit", unit);
        JsonNode rows = table.path("rows");
        require(rows.isArray() && rows.size() <= 65536 && (allowEmpty ? rows.size() == 0 : rows.size() >= 1), "VFP table rows have an invalid shape");
        if (rows.isEmpty()) return Set.of();
        int liquidCount = axes.path("liquidRatesStbPerDay").size();
        int thpCount = axes.path("outletPressuresPsi").size();
        int waterCutCount = axes.path("waterCutFraction").size();
        int gorCount = axes.path("gorMscfPerStb").size();
        int artificialLiftCount = axes.path("artificialLiftInjectionDpPsi").size();
        int expectedRows = thpCount * waterCutCount * gorCount * artificialLiftCount;
        require(rows.size() == expectedRows, "VFP table does not cover the full THP/WFR/GFR/ALQ grid");
        Set<String> rowKeys = new HashSet<>();
        for (JsonNode row : rows) {
            requireObject(row, ROW_FIELDS, "VFP table row");
            int thpIndex = requireIndex(row.path("thpIndex"), thpCount, "thpIndex");
            int waterCutIndex = requireIndex(row.path("waterCutIndex"), waterCutCount, "waterCutIndex");
            int gorIndex = requireIndex(row.path("gorIndex"), gorCount, "gorIndex");
            int artificialLiftIndex = requireIndex(row.path("artificialLiftIndex"), artificialLiftCount, "artificialLiftIndex");
            require(rowKeys.add(thpIndex + ":" + waterCutIndex + ":" + gorIndex + ":" + artificialLiftIndex),
                    "Duplicate VFP table index tuple");
            JsonNode values = row.path("values");
            require(values.isArray() && values.size() == liquidCount, "VFP table row must contain one value per liquid-rate axis point");
            for (JsonNode value : values) requireNumber(value, null, null, "VFP table value");
        }
        require(rowKeys.size() == expectedRows, "VFP table row grid is incomplete");
        return rowKeys;
    }

    private static int requireIndex(JsonNode value, int maximum, String name) {
        require(value.isIntegralNumber() && value.asInt() >= 1 && value.asInt() <= maximum, "Invalid " + name);
        return value.asInt();
    }

    private static void requireObject(JsonNode node, Set<String> fields, String name) {
        require(node != null && node.isObject() && node.size() == fields.size(), name + " has an invalid shape");
        for (String field : fields) require(node.has(field), name + " is missing " + field);
    }

    private static void requireText(JsonNode node, String field, String expected) {
        require(node.path(field).isTextual() && expected.equals(node.path(field).asText()), "Invalid " + field);
    }

    private static void requireSafeText(JsonNode node, String field) {
        String value = node.path(field).isTextual() ? node.path(field).asText() : "";
        require(!value.isBlank() && value.length() <= 1000 && value.indexOf('\u0000') < 0
                        && value.indexOf('\r') < 0 && value.indexOf('\n') < 0
                        && !value.contains("..") && value.indexOf('/') < 0 && value.indexOf('\\') < 0,
                field + " is invalid");
    }

    private static void requireSafeContent(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isTextual() && value.asText().length() <= 500000
                        && value.asText().indexOf('\u0000') < 0, field + " is invalid");
    }

    private static void requireArray(JsonNode value, int minSize, int maxSize, String name) {
        require(value.isArray() && value.size() >= minSize && value.size() <= maxSize, name + " has an invalid shape");
        double previous = Double.NEGATIVE_INFINITY;
        for (JsonNode item : value) {
            requireNumber(item, null, null, name + " value");
            require(item.asDouble() > previous, name + " must be strictly increasing");
            previous = item.asDouble();
        }
    }

    private static void requireNumber(JsonNode value, Double lowerInclusive, Double upperInclusive, String name) {
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue())
                        && (lowerInclusive == null || value.doubleValue() >= lowerInclusive)
                        && (upperInclusive == null || value.doubleValue() <= upperInclusive),
                name + " must be a finite number");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new PipesimWellResultValidator.ResultValidationException(message);
    }
}
