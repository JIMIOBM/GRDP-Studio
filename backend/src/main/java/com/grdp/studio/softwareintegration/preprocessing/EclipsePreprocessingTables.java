package com.grdp.studio.softwareintegration.preprocessing;

import com.grdp.studio.common.BusinessException;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.grdp.studio.softwareintegration.preprocessing.EclipsePreprocessingInput.Value;

@Component
public final class EclipsePreprocessingTables {
    public static final List<String> HEADERS = List.of("参数编号", "数据表", "对象或范围", "字段", "数值", "单位", "来源");
    private static final Map<String, List<String>> ALIASES = Map.of(
            "id", List.of("参数编号", "id", "parameter_id"), "value", List.of("数值", "value", "数值输入"),
            "unit", List.of("单位", "unit"), "sheet", List.of("数据表", "sheet"));

    public List<Value> examples(JsonNode template, boolean blank) {
        var result = new ArrayList<Value>();
        var rows = new LinkedHashMap<String, Integer>();
        for (var field : template.path("fields")) {
            String sheet = field.path("sheet").asText();
            result.add(new Value(field.path("id").asText(), blank ? "" : field.path("baseValue").asText(),
                    field.path("unit").asText(), sheet, rows.merge(sheet, 2, (before, ignored) -> before + 1)));
        }
        return result;
    }

    public byte[] workbook(JsonNode template, boolean blank) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var titleStyle = workbook.createCellStyle();
            titleStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex()); titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var white = workbook.createFont(); white.setColor(IndexedColors.WHITE.getIndex()); white.setBold(true); titleStyle.setFont(white);
            var inputStyle = workbook.createCellStyle(); inputStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex()); inputStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            inputStyle.setDataFormat(workbook.createDataFormat().getFormat("0.###############"));
            var instructions = workbook.createSheet("说明");
            var text = List.of(List.of("内容", "说明"), List.of("数据性质", template.path("classification").asText()),
                    List.of("模板", template.path("name").asText() + " / " + template.path("version").asText()),
                    List.of("官方来源", "ECLIPSE 2024.1 / " + template.path("sourceLocation").asText()),
                    List.of("源文件SHA-256", template.path("sourceSha256").asText()),
                    List.of("适用范围", template.path("scope").asText()),
                    List.of("输出设置", template.path("outputPolicy").asText()),
                    List.of("填写方法", "只填写黄色数值列，保持参数编号与单位。空白模板不填数值，不能直接计算。修改后的文件导入后先检查再生成。"),
                    List.of("单位", "首版保留FIELD原单位。fraction填0–1；温度区分degF与临界温度degR；日期用YYYY-MM-DD。"),
                    List.of("默认语义", "源工程的重复、继承、井组、求解选项列在固定选项表。不会给缺失的数值自动填零。"),
                    List.of("来源说明", "这些表格由原官方DATA转换，为教学输入，不是官方发布的Excel，也不是现场实测。"),
                    List.of("温压关系", "热采的BHP为上限，非实际注入压力。温度、干度与实际相态由官方热采求解器联立计算。"),
                    List.of("使用步骤", "选类型 → 导入此文件 → 修正错误 → 输入预览 → 生成并计算。调整数值后再次生成新版本。"));
            for (int row = 0; row < text.size(); row++) {
                var target = instructions.createRow(row);
                for (int column = 0; column < 2; column++) target.createCell(column).setCellValue(text.get(row).get(column));
            }
            instructions.setColumnWidth(0, 24 * 256); instructions.setColumnWidth(1, 110 * 256);
            var wrap = workbook.createCellStyle(); wrap.setWrapText(true);
            for (var row : instructions) { row.getCell(1).setCellStyle(wrap); row.setHeightInPoints(row.getRowNum() > 4 ? 42 : 25); }
            instructions.getRow(0).forEach(cell -> cell.setCellStyle(titleStyle));
            for (var field : template.path("fields")) {
                String sheetName = field.path("sheet").asText();
                var sheet = workbook.getSheet(sheetName);
                if (sheet == null) {
                    sheet = workbook.createSheet(sheetName); sheet.createFreezePane(0, 1);
                    var row = sheet.createRow(0);
                    for (int column = 0; column < HEADERS.size(); column++) { row.createCell(column).setCellValue(HEADERS.get(column)); row.getCell(column).setCellStyle(titleStyle); }
                    int[] widths = {30, 16, 44, 28, 23, 24, 42};
                    for (int column = 0; column < widths.length; column++) sheet.setColumnWidth(column, widths[column] * 256);
                }
                var row = sheet.createRow(sheet.getLastRowNum() + 1);
                var data = tableRow(template, field, blank);
                for (int column = 0; column < data.size(); column++) row.createCell(column).setCellValue(data.get(column));
                var value = row.getCell(4); value.setCellStyle(inputStyle);
                if (!blank && !"ISO-date".equals(field.path("unit").asText())) value.setCellValue(Double.parseDouble(field.path("baseValue").asText()));
            }
            var options = workbook.createSheet("固定选项");
            options.createRow(0).createCell(0).setCellValue("原工程逐行参考；表中已列出的数值由导入数据替换，其余选项沿用。此页不是自由DECK编辑器。");
            int line = 1;
            for (var value : template.path("source").asText().split("\n", -1)) options.createRow(line++).createCell(0).setCellValue(value);
            options.setColumnWidth(0, 120 * 256); options.createFreezePane(0, 1);
            workbook.write(output); return output.toByteArray();
        } catch (java.io.IOException exception) { throw new BusinessException(500, "Excel模板生成失败"); }
    }

    public byte[] csv(JsonNode template, boolean blank) {
        var text = new StringBuilder("\uFEFF" + String.join(",", HEADERS) + "\r\n");
        for (var field : template.path("fields")) {
            text.append(tableRow(template, field, blank).stream().map(EclipsePreprocessingTables::quote).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private List<String> tableRow(JsonNode template, JsonNode field, boolean blank) {
        return List.of(field.path("id").asText(), field.path("sheet").asText(), field.path("objectName").asText(), field.path("field").asText(),
                blank ? "" : field.path("baseValue").asText(), field.path("unit").asText(),
                template.path("sourceName").asText() + ":" + field.path("sourceLine").asInt());
    }

    public List<Value> read(List<MultipartFile> files, Map<String, String> mapping) {
        if (files == null || files.isEmpty() || files.size() > 16) throw new BusinessException(400, "请选择一个Excel或不超过16份UTF-8 CSV");
        long bytes = files.stream().mapToLong(MultipartFile::getSize).sum();
        if (bytes > 8 * 1024 * 1024) throw new BusinessException(400, "表格总大小不得超过8MB");
        if (mapping == null) mapping = Map.of();
        if (!ALIASES.keySet().containsAll(mapping.keySet())) throw new BusinessException(400, "仅支持编号、数值、单位、数据表的列对应");
        var result = new ArrayList<Value>();
        for (var file : files) {
            String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
            try {
                if (filename.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
                    String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(file.getBytes())).toString();
                    var rows = parseCsv(text);
                    readRows(rows, filename, mapping, result);
                } else if (filename.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
                    byte[] data = file.getBytes(); checkExpansion(data);
                    try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
                        if (workbook.getNumberOfSheets() > 16) throw new BusinessException(400, "Excel工作表不得超过16张");
                        for (var sheet : workbook) {
                            if (Set.of("说明", "固定选项").contains(sheet.getSheetName())) continue;
                            if (sheet.getLastRowNum() > 20000) throw new BusinessException(400, "工作表不得超过20000行");
                            var rows = new ArrayList<List<String>>();
                            Map<String, Integer> columns = null;
                            for (int index = 0; index <= sheet.getLastRowNum(); index++) {
                                var row = sheet.getRow(index); var cells = new ArrayList<String>();
                                if (row != null && row.getLastCellNum() > 32) throw new BusinessException(400, "表格列数超过32列");
                                for (int column = 0; row != null && column < row.getLastCellNum(); column++) {
                                    var cell = row.getCell(column);
                                    cells.add(cell == null ? "" : switch (cell.getCellType()) {
                                        case STRING -> cell.getStringCellValue();
                                        case NUMERIC -> java.math.BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
                                        case BLANK -> "";
                                        case FORMULA -> "=" + cell.getCellFormula();
                                        default -> "不支持的单元格类型";
                                    });
                                }
                                if (index == 0) columns = columnPositions(cells, sheet.getSheetName(), mapping);
                                else if (row != null && columns != null && "ISO-date".equals(at(cells, columns.get("unit")))) {
                                    int valueColumn = columns.get("value");
                                    var cell = row.getCell(valueColumn);
                                    if (cell != null && cell.getCellType() == CellType.NUMERIC && org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell))
                                        cells.set(valueColumn, excelDate(cell.getNumericCellValue(), workbook.isDate1904()));
                                }
                                rows.add(cells);
                            }
                            readRows(rows, sheet.getSheetName(), mapping, result);
                        }
                    }
                } else throw new BusinessException(400, "仅支持.xlsx与UTF-8 CSV，不支持宏或旧版Excel");
            } catch (BusinessException exception) { throw exception; }
            catch (Exception exception) { throw new BusinessException(400, filename + "无法读取，请检查文件格式和UTF-8编码"); }
            if (result.size() > 20000) throw new BusinessException(400, "总参数不得超过20000行");
        }
        return result;
    }

    private void readRows(List<List<String>> rows, String sheet, Map<String, String> mapping, List<Value> result) {
        if (rows.isEmpty()) throw new BusinessException(400, sheet + "没有表头");
        var positions = columnPositions(rows.get(0), sheet, mapping);
        for (int index = 1; index < rows.size(); index++) {
            var row = rows.get(index);
            if (row.stream().allMatch(String::isBlank)) continue;
            result.add(new Value(at(row, positions.get("id")), at(row, positions.get("value")), at(row, positions.get("unit")), sheet, index + 1));
        }
    }

    private static String excelDate(double serial, boolean date1904) {
        if (!Double.isFinite(serial) || serial < 0 || serial > 2957003 || serial != Math.floor(serial)
                || !date1904 && serial == 60) return "无效日期或包含时间";
        return org.apache.poi.ss.usermodel.DateUtil.getLocalDateTime(serial, date1904).toLocalDate().toString();
    }

    private Map<String, Integer> columnPositions(List<String> header, String sheet, Map<String, String> mapping) {
        var headers = header.stream().map(value -> value.replace("\uFEFF", "").trim()).toList();
        var positions = new LinkedHashMap<String, Integer>();
        for (var key : List.of("id", "value", "unit", "sheet")) {
            var accepted = mapping.containsKey(key) ? List.of(mapping.get(key)) : ALIASES.get(key);
            var found = new ArrayList<Integer>();
            for (int index = 0; index < headers.size(); index++) if (accepted.contains(headers.get(index))) found.add(index);
            if (found.size() > 1 || found.isEmpty() && !key.equals("sheet")) {
                throw new BusinessException(400, sheet + "的" + key + "列缺失或不唯一，请确认列名对应");
            }
            if (!found.isEmpty()) positions.put(key, found.get(0));
        }
        return positions;
    }

    static List<List<String>> parseCsv(String text) {
        var rows = new ArrayList<List<String>>(); var row = new ArrayList<String>(); var value = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int index = text.startsWith("\uFEFF") ? 1 : 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') { value.append('"'); index++; }
                else if (character == '"') { quoted = false; closed = true; }
                else value.append(character);
            } else if (character == '"') {
                if (!value.isEmpty() || closed) throw new BusinessException(400, "CSV引号位置错误");
                quoted = true;
            } else if (character == ',' || character == '\n' || character == '\r') {
                row.add(value.toString()); value.setLength(0); closed = false;
                if (row.size() > 32) throw new BusinessException(400, "CSV列数超过32");
                if (character != ',') {
                    rows.add(row); row = new ArrayList<>();
                    if (rows.size() > 20001) throw new BusinessException(400, "CSV超过20000行");
                    if (character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
                }
            } else {
                if (closed) throw new BusinessException(400, "CSV闭合引号后存在多余文本");
                value.append(character);
            }
        }
        if (quoted) throw new BusinessException(400, "CSV引号未闭合");
        if (!value.isEmpty() || !row.isEmpty()) { row.add(value.toString()); rows.add(row); }
        return rows;
    }

    private static void checkExpansion(byte[] bytes) throws java.io.IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            long total = 0; int count = 0; byte[] buffer = new byte[8192];
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (++count > 512) throw new BusinessException(400, "Excel压缩条目过多");
                for (int read; (read = zip.read(buffer)) >= 0;) {
                    total += read;
                    if (total > 32 * 1024 * 1024) throw new BusinessException(400, "Excel展开内容超过32MB");
                }
            }
        }
    }

    private static String at(List<String> row, int index) { return index < row.size() ? row.get(index).trim() : ""; }
    private static String quote(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
}
