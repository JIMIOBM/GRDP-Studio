package com.grdp.studio.softwareintegration.preprocessing;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.grdp.studio.softwareintegration.preprocessing.EclipsePreprocessingInput.*;

@Component
public final class EclipsePreprocessingValidator {
    public record Validation(boolean valid, List<Issue> issues, Map<String, String> normalized,
                             EclipsePreprocessingGrid.Preview preview) { }
    private final EclipsePreprocessingCatalog catalog;

    public EclipsePreprocessingValidator(EclipsePreprocessingCatalog catalog) { this.catalog = catalog; }

    public Validation validate(EclipsePreprocessingInput input) {
        if (input == null) return new Validation(false, List.of(new Issue("工程信息", 1, "请求", "", "前处理请求缺失")), Map.of(), null);
        var template = catalog.require(input.templateId());
        var issues = new ArrayList<Issue>();
        if (!template.path("version").asText().equals(input.templateVersion())) {
            issues.add(new Issue("工程信息", 2, "模板版本", "", "模板版本不一致，请重新下载对应模板"));
        }
        if (input.modelName() == null || input.modelName().isBlank() || input.modelName().length() > 80
                || !input.modelName().equals(input.modelName().trim())
                || input.modelName().chars().anyMatch(character -> Character.isISOControl(character) || "\\/:*?\"<>|".indexOf(character) >= 0)) {
            issues.add(new Issue("工程信息", 2, "模型名称", "", "名称须为1–80个字符，不得含路径或控制字符"));
        }
        if (!input.confirmedDefaults()) {
            issues.add(new Issue("工程信息", 2, "工程假设", "", "请先查看并确认沿用的教学模板固定选项与适用范围"));
        }
        var fields = new LinkedHashMap<String, JsonNode>();
        template.path("fields").forEach(field -> fields.put(field.path("id").asText(), field));
        var supplied = new HashMap<String, Value>();
        if (input.values() == null || input.values().size() > 20000) {
            issues.add(new Issue("导入数据", 1, "参数", "", "参数表缺失或超过20000行"));
            return new Validation(false, issues, Map.of(), null);
        }
        for (var value : input.values()) {
            if (value == null || value.id() == null || !fields.containsKey(value.id())) {
                issues.add(new Issue(value == null ? "导入数据" : value.sheet(), value == null ? 1 : value.row(), "参数编号", "", "未知参数，不能写入受支持的工程模板"));
            } else if (supplied.putIfAbsent(value.id(), value) != null) {
                issues.add(issue(fields.get(value.id()), value, "同一参数重复出现"));
            }
        }
        var normalized = new LinkedHashMap<String, String>();
        for (var entry : fields.entrySet()) {
            var field = entry.getValue();
            var value = supplied.get(entry.getKey());
            if (value == null || value.value() == null || value.value().isBlank()) {
                issues.add(issue(field, value, "必填参数缺失；不会使用零或教学原值替代"));
                continue;
            }
            if (!field.path("unit").asText().equals(value.unit())) {
                issues.add(issue(field, value, "单位必须为 " + field.path("unit").asText() + "；首版不做隐式单位转换"));
                continue;
            }
            if ("ISO-date".equals(value.unit())) {
                try {
                    if (!value.value().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("Invalid date", value.value(), 0);
                    var date = LocalDate.parse(value.value());
                    if (date.getYear() < 1900 || date.getYear() > 2200) throw new DateTimeParseException("Out of range", value.value(), 0);
                    normalized.put(entry.getKey(), date.toString());
                } catch (DateTimeParseException exception) { issues.add(issue(field, value, "请填写1900–2200年之间的有效日期，格式YYYY-MM-DD")); }
                continue;
            }
            try {
                if (value.value().length() > 48 || !value.value().matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[Ee][+-]?[0-9]{1,3})?")) {
                    throw new NumberFormatException();
                }
                var number = new BigDecimal(value.value());
                if (number.abs().compareTo(new BigDecimal("1e12")) > 0 || !Double.isFinite(number.doubleValue())) throw new NumberFormatException();
                if (field.path("integer").asBoolean() && number.stripTrailingZeros().scale() > 0) {
                    issues.add(issue(field, value, "必须是整数")); continue;
                }
                if (field.hasNonNull("minimum")) {
                    int order = number.compareTo(field.path("minimum").decimalValue());
                    if (order < 0 || order == 0 && field.path("exclusiveMinimum").asBoolean()) {
                        issues.add(issue(field, value, "数值必须" + (field.path("exclusiveMinimum").asBoolean() ? "大于" : "不小于") + field.path("minimum").asText())); continue;
                    }
                }
                if (field.hasNonNull("maximum") && number.compareTo(field.path("maximum").decimalValue()) > 0) {
                    issues.add(issue(field, value, "数值不得大于" + field.path("maximum").asText())); continue;
                }
                normalized.put(entry.getKey(), number.stripTrailingZeros().toPlainString());
            } catch (NumberFormatException exception) { issues.add(issue(field, value, "请输入有限数值，不能含公式、文本、重复语法或空白默认值")); }
        }
        if (!issues.isEmpty()) return new Validation(false, List.copyOf(issues), normalized, null);
        validateComposition(template, fields, normalized, supplied, issues);
        EclipsePreprocessingTableRules.validate(template, fields, normalized, supplied, issues);
        validateLiveOil(fields, normalized, supplied, issues);
        validateThermalControls(template, fields, normalized, supplied, issues);
        if (!issues.isEmpty()) return new Validation(false, List.copyOf(issues), normalized, null);
        var preview = EclipsePreprocessingGrid.evaluate(template, normalized);
        validateGrid(template, preview, fields, normalized, supplied, issues);
        return new Validation(issues.isEmpty(), List.copyOf(issues), normalized, issues.isEmpty() ? preview : null);
    }

    private void validateComposition(JsonNode template, Map<String, JsonNode> fields, Map<String, String> values,
                                     Map<String, Value> rows, List<Issue> issues) {
        int width = template.path("components").size() + 1;
        var sums = new HashMap<Integer, BigDecimal>();
        var first = new HashMap<Integer, JsonNode>();
        BigDecimal previousDepth = null;
        for (var field : fields.values()) {
            if (!"ZMFVD".equals(field.path("keyword").asText()) || field.path("streamIndex").asInt() % width != 0) continue;
            var depth = new BigDecimal(values.get(field.path("id").asText()));
            if (previousDepth != null && depth.compareTo(previousDepth) <= 0) {
                issues.add(issue(field, rows.get(field.path("id").asText()), "组成表的深度必须严格递增"));
            }
            previousDepth = depth;
        }
        fields.values().stream().filter(field -> "ZMFVD".equals(field.path("keyword").asText())).forEach(field -> {
            int index = field.path("streamIndex").asInt();
            if (index % width == 0) return;
            sums.merge(index / width, new BigDecimal(values.get(field.path("id").asText())), BigDecimal::add);
            first.putIfAbsent(index / width, field);
        });
        sums.forEach((group, sum) -> {
            if (sum.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.00000001")) > 0) {
                var field = first.get(group);
                issues.add(issue(field, rows.get(field.path("id").asText()), "同一深度的组分摩尔分数和必须为1；当前为" + sum.toPlainString() + "，不会自动归一化"));
            }
        });
    }

    private void validateLiveOil(Map<String, JsonNode> fields, Map<String, String> values,
                                 Map<String, Value> rows, List<Issue> issues) {
        var previous = new HashMap<String, BigDecimal>();
        for (var field : fields.values()) {
            if (!"PVTO".equals(field.path("keyword").asText())) continue;
            String id = field.path("id").asText();
            String block = field.path("block").asText();
            String record = block + ":" + field.path("record").asInt();
            int column = field.path("column").asInt();
            var number = new BigDecimal(values.get(id));
            String message = null;
            if (column == 0) {
                var prior = previous.put(block + ":rs", number);
                if (number.signum() < 0 || prior != null && number.compareTo(prior) <= 0)
                    message = "PVTO溶解气油比须非负且随饱和支路严格递增";
            } else if ((column - 1) % 3 == 0) {
                var prior = previous.put(record + ":pressure", number);
                if (number.signum() <= 0 || prior != null && number.compareTo(prior) <= 0)
                    message = "PVTO同一支路压力须大于0且严格递增";
                if (column == 1) {
                    var bubble = previous.put(block + ":bubble", number);
                    if (bubble != null && number.compareTo(bubble) <= 0)
                        message = "PVTO饱和压力须随溶解气油比严格递增";
                }
            } else if (number.signum() <= 0) {
                message = "PVTO油体积系数与黏度必须大于0";
            }
            if (message != null) issues.add(issue(field, rows.get(id), message));
        }
    }

    private void validateThermalControls(JsonNode template, Map<String, JsonNode> fields, Map<String, String> values,
                                         Map<String, Value> rows, List<Issue> issues) {
        for (var control : template.path("controls")) {
            if (!"WINJTEMP".equals(control.path("keyword").asText())) continue;
            var data = EclipsePreprocessingGrid.recordValues(control, fields, values);
            if (Double.parseDouble(data.get(1)) > 0 && Double.parseDouble(data.get(2)) <= 32) {
                String id = control.path("fields").get(1).asText();
                issues.add(issue(fields.get(id), rows.get(id), "蒸汽注入温度不能低于水的冰点；BHP上限不是实际蒸汽压力，实际相态由热采求解器联立计算"));
            }
        }
        double total = fields.values().stream().filter(field -> "TSTEP".equals(field.path("keyword").asText()))
                .mapToDouble(field -> Double.parseDouble(values.get(field.path("id").asText())) * field.path("repeat").asInt()).sum();
        if (!(total > 0 && total <= 365250)) issues.add(new Issue("时间计划", 2, "模拟时长", "", "模拟时间须大于0且不超过365250天"));
    }

    private void validateGrid(JsonNode template, EclipsePreprocessingGrid.Preview preview, Map<String, JsonNode> fields,
                              Map<String, String> values, Map<String, Value> rows, List<Issue> issues) {
        for (var cell : preview.cells()) {
            if (!Double.isFinite(cell.top()) || !(cell.dx() > 0 && cell.dy() > 0 && cell.dz() > 0)
                    || !Double.isFinite(cell.dx() + cell.dy() + cell.dz())) {
                issues.add(new Issue("网格", cell.k() + 1, "几何", "", "网格 " + cell.i() + "," + cell.j() + "," + cell.k() + " 的尺寸/深度无效")); break;
            }
            if (cell.active() && (!(cell.porosity() > 0 && cell.porosity() <= 1)
                    || !(cell.permx() >= 0 && cell.permy() >= 0 && cell.permz() >= 0))) {
                String property = "PERMZ";
                if (!(cell.porosity() > 0 && cell.porosity() <= 1)) property = "PORO";
                else if (!(cell.permx() >= 0)) property = "PERMX";
                else if (!(cell.permy() >= 0)) property = "PERMY";
                var field = origin(template, fields, property, cell, template.path("gridOperations").size() - 1);
                String message = "活动网格 " + cell.i() + "," + cell.j() + "," + cell.k() + " 的孔隙度须在(0,1]且渗透率非负";
                issues.add(field == null ? new Issue("岩石", 2, property, "", message) : issue(field, rows.get(field.path("id").asText()), message)); break;
            }
        }
        int nx = template.path("dimensions").path("nx").asInt(), ny = template.path("dimensions").path("ny").asInt();
        int nz = template.path("dimensions").path("nz").asInt();
        var wells = new HashMap<String, EclipsePreprocessingGrid.Well>();
        for (var well : preview.wells()) {
            wells.put(well.name(), well);
            if (well.i() < 1 || well.i() > nx || well.j() < 1 || well.j() > ny) {
                issues.add(new Issue("井", 2, "井口位置", "", well.name() + "的井口超出网格范围"));
            }
        }
        for (var definition : template.path("completions")) {
            var data = EclipsePreprocessingGrid.recordValues(definition, fields, values);
            var well = wells.get(data.get(0));
            int i = EclipsePreprocessingGrid.connectionCoordinate(data.get(1), well.i());
            int j = EclipsePreprocessingGrid.connectionCoordinate(data.get(2), well.j());
            int k1 = Integer.parseInt(data.get(3)), k2 = Integer.parseInt(data.get(4));
            String id = definition.path("fields").get(0).asText();
            if (i < 1 || i > nx || j < 1 || j > ny || k1 < 1 || k2 > nz || k1 > k2) {
                issues.add(issue(fields.get(id), rows.get(id), "完井坐标/层段超出网格范围或顶底层倒置")); continue;
            }
            for (int k = k1; k <= k2; k++) if (!preview.cells().get((k - 1) * nx * ny + (j - 1) * nx + i - 1).active()) {
                issues.add(issue(fields.get(id), rows.get(id), "完井落入非活动单元：" + i + "," + j + "," + k)); break;
            }
        }
    }

    private JsonNode origin(JsonNode template, Map<String, JsonNode> fields, String property, EclipsePreprocessingGrid.Cell cell, int last) {
        int nx = template.path("dimensions").path("nx").asInt(), ny = template.path("dimensions").path("ny").asInt(), nz = template.path("dimensions").path("nz").asInt();
        for (int index = last; index >= 0; index--) {
            var operation = template.path("gridOperations").get(index);
            if (!property.equals(operation.path("target").asText())) continue;
            if ("ARRAY".equals(operation.path("kind").asText())) {
                int position = (cell.k() - 1) * nx * ny + (cell.j() - 1) * nx + cell.i() - 1;
                for (var id : operation.path("fieldIds")) {
                    var field = fields.get(id.asText()); int start = field.path("streamIndex").asInt();
                    if (position >= start && position < start + field.path("repeat").asInt()) return field;
                }
                continue;
            }
            int[] bounds = {1, nx, 1, ny, 1, nz};
            var ranges = operation.path("ranges");
            for (int bound = 0; bound < Math.min(6, ranges.size()); bound++) if (!ranges.get(bound).isNull()) bounds[bound] = ranges.get(bound).asInt();
            if (cell.i() < bounds[0] || cell.i() > bounds[1] || cell.j() < bounds[2] || cell.j() > bounds[3] || cell.k() < bounds[4] || cell.k() > bounds[5]) continue;
            if ("COPY".equals(operation.path("kind").asText())) return origin(template, fields, operation.path("source").asText(), cell, index - 1);
            if (operation.hasNonNull("valueId")) return fields.get(operation.path("valueId").asText());
        }
        return null;
    }

    static Issue issue(JsonNode field, Value value, String message) {
        return new Issue(value == null || value.sheet() == null || value.sheet().isBlank() ? field.path("sheet").asText() : value.sheet(),
                value == null ? field.path("tableRow").asInt(2) : value.row(), field.path("field").asText(), field.path("id").asText(), message);
    }
}
