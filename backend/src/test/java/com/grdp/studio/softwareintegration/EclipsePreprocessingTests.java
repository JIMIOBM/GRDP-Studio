package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.preprocessing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EclipsePreprocessingTests {
    final ObjectMapper mapper = new ObjectMapper();
    final EclipsePreprocessingCatalog catalog = new EclipsePreprocessingCatalog(mapper);
    final EclipsePreprocessingTables tables = new EclipsePreprocessingTables();
    final EclipsePreprocessingValidator validator = new EclipsePreprocessingValidator(catalog);

    EclipsePreprocessingInput input(String id, List<EclipsePreprocessingInput.Value> values) {
        return new EclipsePreprocessingInput(id, catalog.require(id).path("version").asText(), "前处理教学验证", UUID.randomUUID(), UUID.randomUUID(), true, true, values);
    }

    @ParameterizedTest @ValueSource(strings = {"e100", "compositional", "thermal"})
    void TeachingParametersRegenerateTheIndependentOfficialSource(String id) {
        var template = catalog.require(id);
        var validation = validator.validate(input(id, tables.examples(template, false)));
        assertTrue(validation.valid(), () -> validation.issues().toString());
        assertEquals(template.path("normalizedSourceSha256").asText(),
                EclipsePreprocessingCatalog.sha256(EclipsePreprocessingDeck.render(template, validation.normalized(), false)));
        String export = System.getProperty("grdp.preprocessing.export");
        if (export != null) {
            try {
                var folder = java.nio.file.Path.of(export).resolve(id); java.nio.file.Files.createDirectories(folder);
                java.nio.file.Files.write(folder.resolve(template.path("sourceName").asText()), EclipsePreprocessingDeck.render(template, validation.normalized(), true));
                java.nio.file.Files.write(folder.resolve("example.xlsx"), tables.workbook(template, false));
                java.nio.file.Files.write(folder.resolve("example.csv"), tables.csv(template, false));
                java.nio.file.Files.write(folder.resolve("blank.xlsx"), tables.workbook(template, true));
                java.nio.file.Files.write(folder.resolve("blank.csv"), tables.csv(template, true));
                java.nio.file.Files.writeString(folder.resolve("input-preview.json"), mapper.writeValueAsString(validation.preview()));
                var edited = new ArrayList<>(tables.examples(template, false));
                String parameter = switch (id) { case "e100" -> "WCONINJE.1.1.5"; case "compositional" -> "GRUPSALE.1.1.2"; default -> "WINJTEMP.1.1.2"; };
                String target = switch (id) { case "e100" -> "4000"; case "compositional" -> "1200"; default -> "0.5"; };
                replace(edited, parameter, target);
                var result = validator.validate(input(id, edited)); assertTrue(result.valid(), () -> result.issues().toString());
                java.nio.file.Files.write(folder.resolve("modified.DATA"), EclipsePreprocessingDeck.render(template, result.normalized(), true));
                java.nio.file.Files.writeString(folder.resolve("edited-inputs.json"), mapper.writeValueAsString(edited));
            } catch (java.io.IOException exception) { fail(exception); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"e100", "compositional", "thermal"})
    void ExcelAndUtf8CsvPreserveEquivalentInputsAndDecks(String id) {
        var template = catalog.require(id);
        var csv = new MockMultipartFile("files", "教学.csv", "text/csv", tables.csv(template, false));
        var excel = new MockMultipartFile("files", "教学.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", tables.workbook(template, false));
        var csvResult = validator.validate(input(id, tables.read(List.of(csv), Map.of())));
        var excelResult = validator.validate(input(id, tables.read(List.of(excel), Map.of())));
        assertTrue(csvResult.valid(), () -> csvResult.issues().toString());
        assertTrue(excelResult.valid(), () -> excelResult.issues().toString());
        assertEquals(csvResult.normalized(), excelResult.normalized());
        assertArrayEquals(EclipsePreprocessingDeck.render(template, csvResult.normalized(), true),
                EclipsePreprocessingDeck.render(template, excelResult.normalized(), true));
        String export = System.getProperty("grdp.preprocessing.export");
        if (export != null) {
            try {
                var folder = java.nio.file.Path.of(export).resolve(id); java.nio.file.Files.createDirectories(folder);
                java.nio.file.Files.write(folder.resolve("excel.DATA"), EclipsePreprocessingDeck.render(template, excelResult.normalized(), true));
                java.nio.file.Files.write(folder.resolve("csv.DATA"), EclipsePreprocessingDeck.render(template, csvResult.normalized(), true));
            } catch (java.io.IOException exception) { fail(exception); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"e100", "compositional", "thermal"})
    void EmptyTemplatesNeverInventMissingEngineeringValues(String id) {
        var result = validator.validate(input(id, tables.examples(catalog.require(id), true)));
        assertFalse(result.valid()); assertNull(result.preview());
        assertTrue(result.normalized().isEmpty()); assertEquals(catalog.require(id).path("fields").size(), result.issues().size());
    }

    @Test void InvalidLiveOilBranchesAndCompositionDepthsAreBlockedBeforeGeneration() {
        for (var fault : List.of(
                new String[]{"e100", "PVTO.1.2.1", "0"},
                new String[]{"e100", "PVTO.1.2.2", "14.7"},
                new String[]{"e100", "PVTO.1.11.5", "3000"},
                new String[]{"e100", "PVTO.1.1.3", "0"},
                new String[]{"compositional", "ZMFVD.1.1.11", "1"})) {
            var values = new ArrayList<>(tables.examples(catalog.require(fault[0]), false));
            replace(values, fault[1], fault[2]);
            var result = validator.validate(input(fault[0], values));
            assertFalse(result.valid(), fault[1]);
            assertNull(result.preview());
            assertTrue(result.issues().stream().anyMatch(issue -> issue.id().equals(fault[1])), fault[1]);
        }
    }

    @Test void ErrorsKeepTheOriginalSheetRowAndField() {
        var values = new ArrayList<>(tables.examples(catalog.require("thermal"), false));
        int index = java.util.stream.IntStream.range(0, values.size()).filter(i -> values.get(i).id().equals("WINJTEMP.1.1.2")).findFirst().orElseThrow();
        var old = values.get(index);
        values.set(index, new EclipsePreprocessingInput.Value(old.id(), "1.2", old.unit(), "我的热采输入.csv", 37));
        var result = validator.validate(input("thermal", values));
        assertFalse(result.valid());
        var error = result.issues().getFirst();
        assertEquals("我的热采输入.csv", error.sheet()); assertEquals(37, error.row()); assertEquals("蒸汽干度", error.field());
    }

    @Test void CompositionCannotBeSilentlyNormalizedAndWrongUnitsCannotBeAccepted() {
        for (String fault : List.of("composition", "unit", "missing", "duplicate", "formula", "unknown")) {
            var values = new ArrayList<>(tables.examples(catalog.require("compositional"), false));
            int index = java.util.stream.IntStream.range(0, values.size()).filter(i -> values.get(i).id().equals("ZMFVD.1.1.2")).findFirst().orElseThrow();
            var old = values.get(index);
            switch (fault) {
                case "composition" -> values.set(index, new EclipsePreprocessingInput.Value(old.id(), "0.5", old.unit(), old.sheet(), old.row()));
                case "unit" -> values.set(index, new EclipsePreprocessingInput.Value(old.id(), old.value(), "%", old.sheet(), old.row()));
                case "missing" -> values.remove(index);
                case "duplicate" -> values.add(old);
                case "formula" -> values.set(index, new EclipsePreprocessingInput.Value(old.id(), "=1/9", old.unit(), old.sheet(), old.row()));
                default -> values.add(new EclipsePreprocessingInput.Value("arbitrary.DATA", "1", "fraction", "输入", 99));
            }
            assertFalse(validator.validate(input("compositional", values)).valid(), fault);
        }
    }

    @Test void BoundedTemplatesRejectOutOfGridAndInactiveCompletionsBeforeGeneration() {
        var values = new ArrayList<>(tables.examples(catalog.require("thermal"), false));
        replace(values, "COMPDAT.1.1.3", "2");
        assertFalse(validator.validate(input("thermal", values)).valid());
        values = new ArrayList<>(tables.examples(catalog.require("e100"), false));
        replace(values, "WELSPECS.1.1.3", "25");
        assertFalse(validator.validate(input("e100", values)).valid());
        values = new ArrayList<>(tables.examples(catalog.require("thermal"), false));
        replace(values, "TSTEP.1.1.1", "-10");
        assertFalse(validator.validate(input("thermal", values)).valid());
    }

    @Test void ColumnMappingIsExplicitAndQuotedCsvValuesArePreserved() {
        var bytes = "编号,新值,量纲\r\n\"project.startDate\",1990-01-01,ISO-date\r\n".getBytes(StandardCharsets.UTF_8);
        var file = new MockMultipartFile("files", "自定义.csv", "text/csv", bytes);
        assertThrows(RuntimeException.class, () -> tables.read(List.of(file), Map.of()));
        var rows = tables.read(List.of(file), Map.of("id", "编号", "value", "新值", "unit", "量纲"));
        assertEquals("1990-01-01", rows.getFirst().value()); assertEquals(2, rows.getFirst().row());
    }

    @Test void AnActiveCellPorosityFailurePointsBackToItsActualSpreadsheetRow() {
        var values = new ArrayList<>(tables.examples(catalog.require("thermal"), false));
        var row = values.stream().filter(value -> value.id().equals("PORO.1.1.1")).findFirst().orElseThrow();
        replace(values, row.id(), "0");
        var result = validator.validate(input("thermal", values));
        assertFalse(result.valid()); assertEquals(row.id(), result.issues().getFirst().id()); assertEquals(row.row(), result.issues().getFirst().row());
    }

    @Test void CriticalPropertiesAndIntegerCoordinatesAreCheckedBeforePreviewEvaluation() {
        for (var entry : Map.of("PCRIT.1.1.1", "0", "WELSPECS.1.1.3", "100000000000").entrySet()) {
            var values = new ArrayList<>(tables.examples(catalog.require("compositional"), false));
            replace(values, entry.getKey(), entry.getValue());
            var result = validator.validate(input("compositional", values)); assertFalse(result.valid());
            assertEquals(entry.getKey(), result.issues().getFirst().id());
        }
    }

    @Test void E100TeachingOutputHasAnExplicitUnambiguousFieldWellContract() {
        var template = catalog.require("e100"); var result = validator.validate(input("e100", tables.examples(template, false)));
        String deck = new String(EclipsePreprocessingDeck.render(template, result.normalized(), true), StandardCharsets.US_ASCII);
        String summary = deck.substring(deck.indexOf("\nSUMMARY\n"), deck.indexOf("\nSCHEDULE\n"));
        assertFalse(summary.contains("\nALL\n")); assertFalse(summary.contains("\nBPR\n"));
        for (String key : List.of("FOPR", "FWPR", "FGPR", "FOPT", "FWIT", "FGPT", "FPR", "FWPT", "WOPR", "WWPR", "WGPR", "WOPT", "WWIT", "WBHP", "WWIR")) {
            assertEquals(1, java.util.regex.Pattern.compile("(?m)^" + key + "$").matcher(summary).results().count(), key);
        }
        assertFalse(deck.contains("'INJE1 '")); assertTrue(deck.contains("'INJE1'"));
        assertTrue(summary.contains("WOPR\n 'INJE1'")); assertFalse(summary.contains("WOPR\n/"));
        assertTrue(deck.contains("RPTRST\n BASIC=2 FREQ=1 /"));
        var wrongVersion = new EclipsePreprocessingInput("e100", "1.0.0", "教学", UUID.randomUUID(), UUID.randomUUID(), true, true, tables.examples(template, false));
        assertFalse(validator.validate(wrongVersion).valid());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "e100,SWOF.1.1.2,0.5", "compositional,SWFN.1.1.2,0.5", "thermal,SWFN.1.1.2,0.5",
            "e100,SWOF.1.1.6,0.9", "e100,SWOF.1.1.8,500", "e100,SWOF.1.1.119,0.1",
            "e100,SGOF.1.1.1,0.01", "e100,SGOF.1.1.2,0.1", "e100,SGOF.1.1.3,0.9",
            "compositional,SGFN.1.1.6,-1", "compositional,SOF3.1.1.2,0.1",
            "compositional,SOF3.1.1.63,0.9", "thermal,SOF3.1.1.41,0.5",
            "thermal,TEMPVD.1.1.3,1000", "thermal,TEMPVD.1.1.3,1300",
            "thermal,OILVISCT.1.1.1,1000", "thermal,OILVISCT.1.1.6,20",
            "thermal,GASVISCT.1.1.1,1000", "thermal,GASVISCT.1.1.6,0.001"
    })
    void InvalidPhysicalTablesAreRejectedAtTheirSourceRows(String type, String parameter, String value) {
        var values = new ArrayList<>(tables.examples(catalog.require(type), false));
        replace(values, parameter, value);
        var result = validator.validate(input(type, values));
        assertFalse(result.valid(), parameter); assertNull(result.preview());
        String keyword = parameter.substring(0, parameter.indexOf('.'));
        var issue = result.issues().stream().filter(i -> i.id().startsWith(keyword + ".")).findFirst().orElseThrow();
        var source = values.stream().filter(v -> v.id().equals(issue.id())).findFirst().orElseThrow();
        assertEquals(source.sheet(), issue.sheet()); assertEquals(source.row(), issue.row());
    }

    @Test void ThermalConstantExtensionsAndLevelViscosityRemainValid() {
        var values = new ArrayList<>(tables.examples(catalog.require("thermal"), false));
        replace(values, "OILVISCT.1.1.6", "2.3");
        replace(values, "GASVISCT.1.1.6", "0.0143");
        var result = validator.validate(input("thermal", values));
        assertTrue(result.valid(), () -> result.issues().toString());
    }

    @ParameterizedTest @ValueSource(strings = {"e100", "compositional", "thermal"})
    void NativeExcelDatesInBothDateSystemsGenerateTheSameDeckAsCsv(String type) throws Exception {
        var definition = catalog.require(type);
        var csv = validator.validate(input(type, tables.read(List.of(new MockMultipartFile("files", "case.csv", "text/csv", tables.csv(definition, false))), Map.of())));
        String date = csv.normalized().get("project.startDate");
        for (boolean date1904 : new boolean[]{false, true}) {
            byte[] bytes;
            try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(tables.workbook(definition, false)));
                 var output = new java.io.ByteArrayOutputStream()) {
                if (!book.getCTWorkbook().isSetWorkbookPr()) book.getCTWorkbook().addNewWorkbookPr();
                book.getCTWorkbook().getWorkbookPr().setDate1904(date1904);
                var cell = dateCell(book);
                cell.setCellValue(org.apache.poi.ss.usermodel.DateUtil.getExcelDate(java.time.LocalDate.parse(date).atStartOfDay(), date1904));
                var style = book.createCellStyle(); style.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd")); cell.setCellStyle(style);
                book.write(output); bytes = output.toByteArray();
            }
            var result = validator.validate(input(type, tables.read(List.of(new MockMultipartFile("files", "case.xlsx", "application/octet-stream", bytes)), Map.of())));
            assertTrue(result.valid(), () -> result.issues().toString());
            assertEquals(csv.normalized(), result.normalized());
            assertArrayEquals(EclipsePreprocessingDeck.render(definition, csv.normalized(), true), EclipsePreprocessingDeck.render(definition, result.normalized(), true));
        }
    }

    @Test void ExcelDatesDoNotAcceptFormulasTimesOrTheFictitiousLeapDay() throws Exception {
        for (String fault : List.of("formula", "time", "leap")) {
            byte[] bytes;
            try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(tables.workbook(catalog.require("thermal"), false)));
                 var output = new java.io.ByteArrayOutputStream()) {
                var cell = dateCell(book);
                var style = book.createCellStyle(); style.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd")); cell.setCellStyle(style);
                if (fault.equals("formula")) cell.setCellFormula("DATE(1990,1,1)");
                else cell.setCellValue(fault.equals("time") ? 32874.5 : 60);
                book.write(output); bytes = output.toByteArray();
            }
            var result = validator.validate(input("thermal", tables.read(List.of(new MockMultipartFile("files", "case.xlsx", "application/octet-stream", bytes)), Map.of())));
            assertFalse(result.valid(), fault);
            assertTrue(result.issues().stream().anyMatch(i -> i.id().equals("project.startDate")));
        }
    }

    private org.apache.poi.ss.usermodel.Cell dateCell(org.apache.poi.xssf.usermodel.XSSFWorkbook book) {
        for (var sheet : book) for (var row : sheet) {
            var cell = row.getCell(0);
            if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                    && cell.getStringCellValue().equals("project.startDate")) return row.getCell(4);
        }
        throw new AssertionError("Missing date input");
    }

    @Test void PreviewKeepsExplicitCompletionCoordinatesSeparateFromWellHeads() {
        var values = new ArrayList<>(tables.examples(catalog.require("compositional"), false));
        replace(values, "COMPDAT.1.1.2", "2");
        var result = validator.validate(input("compositional", values));
        assertTrue(result.valid(), () -> result.issues().toString());
        var well = mapper.readTree(mapper.writeValueAsString(result.preview())).path("wells").get(0);
        assertEquals(1, well.path("i").asInt());
        var connection = well.path("completions").get(0);
        assertEquals(2, connection.path("i").asInt()); assertEquals(1, connection.path("j").asInt());
        assertEquals(1, connection.path("k1").asInt()); assertEquals(2, connection.path("k2").asInt());
        assertEquals("OPEN", connection.path("status").asText());
        var e100 = new ArrayList<>(tables.examples(catalog.require("e100"), false));
        replace(e100, "WELSPECS.1.1.3", "23");
        var inherited = validator.validate(input("e100", e100)); assertTrue(inherited.valid());
        var waterWell = mapper.readTree(mapper.writeValueAsString(inherited.preview())).path("wells").get(0);
        assertEquals(23, waterWell.path("completions").get(0).path("i").asInt());
    }

    private static void replace(List<EclipsePreprocessingInput.Value> values, String id, String value) {
        for (int i = 0; i < values.size(); i++) {
            var old = values.get(i);
            if (id.equals(old.id())) { values.set(i, new EclipsePreprocessingInput.Value(id, value, old.unit(), old.sheet(), old.row())); return; }
        }
        fail("Missing fixture parameter " + id);
    }
}
