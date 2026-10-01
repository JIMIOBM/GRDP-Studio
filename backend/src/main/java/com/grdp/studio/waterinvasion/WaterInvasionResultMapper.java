package com.grdp.studio.waterinvasion;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

/** 旧协议的唯一映射入口。按类别取字段，不能把其它类别的占位 0 当成结果。 */
@Component
public class WaterInvasionResultMapper {
    public static final String VERSION = "water-invasion-v1";
    public static final List<String> TYPES = List.of("IDENTIFICATION", "AQUIFER_SIZE", "WATER_INFLUX", "DRIVE_MECHANISM", "WATER_ACTIVITY");
    private static final List<List<String>> SUMMARY = List.of(
        List.of("originalGasVolume","waterInvasionStateDesc"),
        List.of("waterBodySize","undergroundGasVolume","waterBodySizeMultiple"), List.of(), List.of(),
        List.of("waterActivenessDesc","abandonWaterInflux","reservoirOriginalVolume","waterInvasionReplacementCoefficient"));
    private static final List<List<String>> ROW_KEYS = List.of(
        List.of("apparentPressure","recoveryDegree"), List.of("apparentPressure","recoveryDegree"),
        List.of("waterInflux"), List.of("gasDriveIndex","reservoirVolumetricDriveIndex","waterInvasionEnergyDriveIndex"), List.of());

    public record Section(String type, Map<String,Object> raw, Map<String,Object> output,
                          List<Map<String,Object>> rows, boolean hasSummary, boolean hasDetail, boolean hasChart) {
        public boolean available() { return hasSummary || hasDetail || hasChart; }
    }

    public List<Section> parse(Map<String,Object> response, String wellName) {
        if (!wellName.equals(response.get("wellName")) || !(response.get("input") instanceof Map))
            invalid("旧平台返回的井名或输入结构不匹配，未保存结果");
        // 两个真实样本均固定保留五个位置，包括三个 null 的无结果项；未知协议不猜位置。
        if (!(response.get("outputs") instanceof List<?> list) || list.size() != 5)
            invalid("旧平台结果不是约定的五项结构，未保存结果");
        List<?> list = (List<?>) response.get("outputs");
        var sections = new ArrayList<Section>();
        for (int i = 0; i < 5; i++) {
            if (!(list.get(i) instanceof Map)) invalid("旧平台分析项结构无效");
            var raw = map(list.get(i));
            if (raw.get("output") != null && !(raw.get("output") instanceof Map)) invalid("旧平台汇总结果结构无效");
            var output = map(raw.get("output"));
            var rows = objects(raw.get("outputItems"));
            boolean summary = SUMMARY.get(i).stream().anyMatch(k -> k.endsWith("Desc")
                ? output.get(k) instanceof String text && !text.isBlank() : number(output.get(k)) != null);
            var keys = ROW_KEYS.get(i);
            boolean detail = rows.stream().anyMatch(row -> keys.stream().anyMatch(k -> number(row.get(k)) != null));
            boolean chart = false;
            for (var series : objects(raw.get("chartItems"))) {
                String xField = Objects.toString(series.get("xAxisField"), "");
                String yField = Objects.toString(series.get("yAxisField"), "");
                if (!keys.contains(yField)) continue;
                if (objects(series.get("data")).stream().anyMatch(p -> !Boolean.TRUE.equals(p.get("isDeleted"))
                    && number(p.get("yValue")) != null
                    && (xField.equals("date") ? date(p.get("xValue")) != null : number(p.get("xValue")) != null))) chart = true;
            }
            sections.add(new Section(TYPES.get(i), raw, output, rows, summary, detail, chart));
        }
        return sections;
    }

    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value) {
        return value instanceof Map<?,?> ? (Map<String,Object>) value : Map.of();
    }
    public static List<Map<String,Object>> objects(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) { invalid("旧平台明细或曲线不是数组"); return List.of(); }
        if (list.stream().anyMatch(item -> !(item instanceof Map))) invalid("旧平台明细或曲线包含无效项目");
        return list.stream().map(WaterInvasionResultMapper::map).toList();
    }
    public static Double number(Object value) {
        if (!(value instanceof Number) && !(value instanceof String s && !s.isBlank())) return null;
        try { double n = Double.parseDouble(value.toString()); return Double.isFinite(n) ? n : null; }
        catch (NumberFormatException ignored) { return null; }
    }
    public static Long legacyId(Object value) {
        Double n = number(value); return n != null && n > 0 && n == Math.rint(n) ? n.longValue() : null;
    }
    public static LocalDateTime date(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        try { return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime(); }
        catch (RuntimeException ignored) {
            try { return LocalDateTime.parse(text); }
            catch (RuntimeException alsoIgnored) {
                try { return LocalDate.parse(text).atStartOfDay(); } catch (RuntimeException invalid) { return null; }
            }
        }
    }
    private static void invalid(String message) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message); }
}
