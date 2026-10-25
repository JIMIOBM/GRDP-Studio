package com.grdp.studio.softwareintegration.preprocessing;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.grdp.studio.softwareintegration.preprocessing.EclipsePreprocessingInput.*;
import static com.grdp.studio.softwareintegration.preprocessing.EclipsePreprocessingValidator.issue;

/** Keyword rules for the supported engineering templates (ECLIPSE 2024.1 reference manual). */
final class EclipsePreprocessingTableRules {
    private record Point(JsonNode field, BigDecimal value) { }
    private record Table(String keyword, List<List<Point>> rows) {
        Point first(int column) { return rows.getFirst().get(column); }
        Point last(int column) { return rows.getLast().get(column); }
    }

    static void validate(JsonNode template, Map<String, JsonNode> fields, Map<String, String> values,
                         Map<String, Value> sources, List<Issue> issues) {
        int viscosityWidth = template.path("components").size() + 1;
        var widths = Map.of("SWFN", 3, "SGFN", 3, "SOF3", 3, "SWOF", 4, "SGOF", 4,
                "PVDG", 3, "TEMPVD", 2, "OILVISCT", viscosityWidth, "GASVISCT", viscosityWidth);
        var groups = new LinkedHashMap<String, List<JsonNode>>();
        for (var field : fields.values()) {
            if (!widths.containsKey(field.path("keyword").asText())) continue;
            String key = field.path("block").asText() + ":" + field.path("record").asInt();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(field);
        }
        var tables = new LinkedHashMap<String, List<Table>>();
        for (var group : groups.values()) {
            group.sort(Comparator.comparingInt(field -> field.path("streamIndex").asInt()));
            String keyword = group.getFirst().path("keyword").asText();
            int width = widths.get(keyword);
            if (group.size() < width * 2 || group.size() % width != 0)
                throw new IllegalStateException("Incomplete template table: " + keyword);
            var rows = new ArrayList<List<Point>>();
            for (int offset = 0; offset < group.size(); offset += width) {
                var row = new ArrayList<Point>();
                for (var field : group.subList(offset, offset + width))
                    row.add(new Point(field, new BigDecimal(values.get(field.path("id").asText()))));
                rows.add(row);
            }
            var table = new Table(keyword, rows);
            tables.computeIfAbsent(keyword, ignored -> new ArrayList<>()).add(table);
            checkTable(table, sources, issues);
        }
        var waterOil = tables.getOrDefault("SWOF", List.of());
        var gasOil = tables.getOrDefault("SGOF", List.of());
        for (int index = 0; index < Math.min(waterOil.size(), gasOil.size()); index++) {
            equal(gasOil.get(index).first(2), waterOil.get(index).first(2).value(),
                    "SGOF与SWOF的首个油相相对渗透率必须相同", sources, issues);
        }
        var oil = tables.getOrDefault("SOF3", List.of());
        var water = tables.getOrDefault("SWFN", List.of());
        for (int index = 0; index < Math.min(oil.size(), water.size()); index++)
            checkOilEndpoint(oil.get(index), BigDecimal.ONE.subtract(water.get(index).first(0).value()), sources, issues);
    }

    private static void checkTable(Table table, Map<String, Value> sources, List<Issue> issues) {
        trend(table, 0, true, true, "自变量（饱和度、压力、深度或温度）必须严格递增", sources, issues);
        switch (table.keyword()) {
            case "SWFN", "SGFN", "SWOF", "SGOF" -> {
                zero(table.first(1), "首个水/气相相对渗透率必须为0", sources, issues);
                trend(table, 1, true, false, "水/气相相对渗透率必须非递减", sources, issues);
                boolean water = table.keyword().startsWith("SW");
                int pressureColumn = table.rows().getFirst().size() - 1;
                trend(table, pressureColumn, !water, false,
                        water ? "油水毛管压力必须非递增" : "油气毛管压力必须非递减", sources, issues);
                if (pressureColumn == 3) {
                    trend(table, 2, false, false, "油相相对渗透率必须非递增", sources, issues);
                    zero(table.last(2), "最后一个油相相对渗透率必须为0", sources, issues);
                }
                if (table.keyword().equals("SGOF")) zero(table.first(0), "SGOF首个气饱和度必须为0", sources, issues);
            }
            case "SOF3" -> {
                for (int column = 1; column <= 2; column++) {
                    zero(table.first(column), "首个油相相对渗透率必须为0", sources, issues);
                    trend(table, column, true, false, "油相相对渗透率必须非递减", sources, issues);
                }
                equal(table.last(2), table.last(1).value(), "SOF3两列油相相对渗透率的最大值必须相同", sources, issues);
            }
            case "OILVISCT", "GASVISCT" -> {
                boolean gas = table.keyword().equals("GASVISCT");
                for (int column = 1; column < table.rows().getFirst().size(); column++)
                    trend(table, column, gas, false, gas ? "气组分黏度必须随温度非递减" : "油组分黏度必须随温度非递增", sources, issues);
            }
            default -> { }
        }
    }

    private static void trend(Table table, int column, boolean increasing, boolean strict, String message,
                              Map<String, Value> sources, List<Issue> issues) {
        for (int index = 1; index < table.rows().size(); index++) {
            var point = table.rows().get(index).get(column);
            int order = point.value().compareTo(table.rows().get(index - 1).get(column).value());
            if ((increasing ? order < 0 : order > 0) || strict && order == 0)
                error(point, table.keyword() + "：" + message, sources, issues);
        }
    }

    private static void checkOilEndpoint(Table oil, BigDecimal maximum, Map<String, Value> sources, List<Issue> issues) {
        if (oil.last(0).value().compareTo(maximum) < 0 || oil.first(0).value().compareTo(maximum) > 0) {
            error(oil.last(0), "SOF3必须覆盖最大有效油饱和度1-Swco=" + maximum.toPlainString(), sources, issues);
            return;
        }
        // THERM3 includes constant extension rows beyond 1-Swco. They must not
        // introduce a different endpoint outside the physically accessible interval.
        for (int index = 1; index < oil.rows().size(); index++) {
            var before = oil.rows().get(index - 1);
            var after = oil.rows().get(index);
            if (before.get(0).value().compareTo(maximum) >= 0) {
                for (int column = 1; column <= 2; column++)
                    equal(after.get(column), before.get(column).value(), "SOF3在1-Swco以外的延伸段必须保持端点值", sources, issues);
            } else if (after.get(0).value().compareTo(maximum) > 0) {
                for (int column = 1; column <= 2; column++)
                    equal(after.get(column), before.get(column).value(), "SOF3须在1-Swco处达到最大油相相对渗透率", sources, issues);
            }
        }
    }

    private static void zero(Point point, String message, Map<String, Value> sources, List<Issue> issues) {
        if (point.value().signum() != 0) error(point, message, sources, issues);
    }

    private static void equal(Point point, BigDecimal expected, String message, Map<String, Value> sources, List<Issue> issues) {
        if (point.value().subtract(expected).abs().compareTo(new BigDecimal("1e-8")) > 0)
            error(point, message, sources, issues);
    }

    private static void error(Point point, String message, Map<String, Value> sources, List<Issue> issues) {
        issues.add(issue(point.field(), sources.get(point.field().path("id").asText()), message));
    }
}
