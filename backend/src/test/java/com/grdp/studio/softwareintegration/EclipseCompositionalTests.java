package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.EclipseSummaryResultValidator;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class EclipseCompositionalTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final EclipseSummaryResultValidator validator = new EclipseSummaryResultValidator(mapper);

    private ObjectNode inspection() throws Exception {
        try (var in = getClass().getResourceAsStream("/eclipse-compositional-inspection.json")) {
            assertThat(in).isNotNull(); return (ObjectNode) mapper.readTree(in);
        }
    }

    // Small constructed summary tests contracts only; native all-column proof runs separately.
    private ObjectNode result(ObjectNode inspection) {
        var r = mapper.createObjectNode().put("schemaVersion", "eclipse-summary-result/2")
                .put("modelKind", "eclipse_300").put("runTask", "eclipse").put("resultContract", "VALID_FULL")
                .put("caseName", "SPE3_GASCOND.DATA");
        r.set("simulation", inspection.get("simulation").deepCopy());
        r.putObject("eclEnd").put("comments", 1).put("warnings", 3).put("problems", 0).put("errors", 0).put("bugs", 0);
        var summary = r.putObject("summary").put("startDate", "1990-01-01").put("timeVectorIndex", 0);
        var series = summary.putArray("series");
        for (int index = 1; index <= 5; index++) {
            var s = series.addObject().put("vectorIndex", index).put("keyword", switch (index) {
                case 3 -> "WXMF_1"; case 4 -> "BXMF_6"; case 5 -> "WGPR"; default -> "FOPR";
            }).put("number", index == 4 ? 61 : 2);
            if (index == 3) s.put("objectName", "P"); else if (index == 5) s.put("objectName", ":+:+:+:+"); else s.putNull("objectName");
            if (index == 3 || index == 4) s.putNull("unit"); else s.put("unit", "STB/DAY");
            var points = s.putArray("points");
            points.addObject().put("timeDays", 0).put("value", index);
            points.addObject().put("timeDays", 0.25).put("value", index * 0.5);
        }
        r.putArray("outputFiles").addObject().put("name", "SPE3_GASCOND.ECLEND").put("sizeBytes", 8).put("sha256", "a".repeat(64));
        return r;
    }

    @Test void actualInspectionPersistsOriginalNineComponentsAndSourceLines() throws Exception {
        var i = inspection();
        var stored = EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
        assertThat(stored.toString()).isEqualTo(i.toString());
        assertThat(stored.path("simulation").path("componentNames").size()).isEqualTo(9);
        assertThat(stored.path("scheduleTimeline").get(2).path("lineNumber").asInt()).isEqualTo(486);
        assertThat(stored.has("scheduleMetadata")).isFalse();
    }

    @Test void duplicateMnemonicsRawNumbersPlaceholdersAndFractionalActualTimesStayIntact() throws Exception {
        var i = inspection(); var r = result(i);
        assertThat(validator.validateCompositional("SPE3_GASCOND.DATA", r, i).result()).isEqualTo(r);
        assertThatThrownBy(() -> validator.validate("SPE3_GASCOND.DATA", r)).isInstanceOf(EclipseSummaryResultValidator.ResultValidationException.class);
    }

    @Test void rejectsMetadataMissingColumnsWrongAxisAndComponentIdentity() throws Exception {
        for (var fault : new String[]{"solver", "components", "schema", "kind", "missingNumber", "missingColumn", "duplicateIndex", "axis", "component", "block", "extra"}) {
            var i = inspection(); var r = result(i); var series = r.path("summary").withArray("series"); var s = (ObjectNode) series.get(2);
            switch (fault) {
                case "solver" -> ((ObjectNode) r.path("simulation")).put("solver", 100);
                case "components" -> r.path("simulation").withArray("componentNames").set(0, mapper.getNodeFactory().textNode("OTHER"));
                case "schema" -> r.put("schemaVersion", "eclipse-summary-result/1");
                case "kind" -> r.put("modelKind", "eclipse_100");
                case "missingNumber" -> s.remove("number");
                case "missingColumn" -> series.remove(1);
                case "duplicateIndex" -> s.put("vectorIndex", 2);
                case "axis" -> ((ObjectNode) r.path("summary")).put("timeVectorIndex", 2);
                case "component" -> s.put("keyword", "WXMF_10");
                case "block" -> ((ObjectNode) series.get(3)).put("number", 325);
                default -> r.put("unexpected", true);
            }
            assertThatThrownBy(() -> validator.validateCompositional("SPE3_GASCOND.DATA", r, i)).as(fault)
                    .isInstanceOf(EclipseSummaryResultValidator.ResultValidationException.class);
        }
    }

    @Test void rejectsInventedTimeOriginIncompleteClockAndNonFiniteNumbers() throws Exception {
        for (var fault : new String[]{"date", "zeroYear", "missingPoint", "duplicateTime", "clock", "nan"}) {
            var i = inspection(); var r = result(i); var summary = (ObjectNode) r.path("summary"); var points = summary.path("series").get(3).withArray("points");
            switch (fault) {
                case "date" -> summary.put("startDate", "1990-02-31");
                case "zeroYear" -> summary.put("startDate", "0000-01-01");
                case "missingPoint" -> points.remove(1);
                case "duplicateTime" -> ((ObjectNode) points.get(1)).put("timeDays", 0);
                case "clock" -> ((ObjectNode) points.get(1)).put("timeDays", 1);
                default -> ((ObjectNode) points.get(1)).put("value", Double.NaN);
            }
            assertThatThrownBy(() -> validator.validateCompositional("SPE3_GASCOND.DATA", r, i)).as(fault)
                    .isInstanceOf(EclipseSummaryResultValidator.ResultValidationException.class);
        }
    }

    @Test void inspectionCannotSmuggleLegacyEditorsOrIncorrectNames() throws Exception {
        for (var fault : new String[]{"editor", "count", "duplicate", "extra", "unit"}) {
            var i = inspection(); var m = (ObjectNode) i.path("simulation");
            switch (fault) {
                case "editor" -> i.putObject("scheduleMetadata");
                case "count" -> m.put("componentCount", 8);
                case "duplicate" -> m.withArray("componentNames").set(1, m.path("componentNames").get(0));
                case "extra" -> m.put("extra", true);
                default -> i.put("unitSystem", "LAB");
            }
            assertThatThrownBy(() -> EclipseDataInspectionValidator.validateAndSerialize(i, mapper)).as(fault).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
