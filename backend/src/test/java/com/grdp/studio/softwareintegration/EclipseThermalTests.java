package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.EclipseSummaryResultValidator;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class EclipseThermalTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final EclipseSummaryResultValidator validator = new EclipseSummaryResultValidator(mapper);
    private ObjectNode inspection() throws Exception {
        try (var in = getClass().getResourceAsStream("/eclipse-thermal-inspection.json")) {
            assertThat(in).isNotNull(); return (ObjectNode) mapper.readTree(in);
        }
    }
    // Small synthetic contract data; real 343x124 native comparison is separate.
    private ObjectNode result(ObjectNode i) {
        var r = mapper.createObjectNode().put("schemaVersion", "eclipse-summary-result/3").put("modelKind", "eclipse_300")
                .put("runTask", "eclipse").put("caseName", "THERM3.DATA").put("resultContract", "VALID_FULL");
        r.set("simulation", i.get("simulation").deepCopy());
        r.putObject("eclEnd").put("comments", 1).put("warnings", 2).put("problems", 0).put("errors", 0).put("bugs", 0);
        var s = r.putObject("summary").put("startDate", "1990-01-01").put("timeVectorIndex", 0).putArray("series");
        var a = s.addObject().put("vectorIndex", 1).put("keyword", "BTEMP").put("number", 144).put("unit", "DEG F");
        a.putNull("objectName"); var p = a.putArray("points"); p.addObject().put("timeDays", 1).put("value", 125); p.addObject().put("timeDays", 3650).put("value", 424.8);
        var b = s.addObject().put("vectorIndex", 2).put("keyword", "WTEMP").put("number", 1).put("objectName", "I").put("unit", "DEG F");
        b.set("points", p.deepCopy());
        r.putArray("outputFiles").addObject().put("name", "THERM3.ECLEND").put("sizeBytes", 8).put("sha256", "a".repeat(64));
        return r;
    }
    @Test void actualWorkerThermalModelPersistsSeparate500IdentityAndOilNames() throws Exception {
        var i = inspection();
        var saved = EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i, mapper));
        assertThat(saved.toString()).isEqualTo(i.toString());
        assertThat(saved.path("simulation").has("equationOfState")).isFalse();
        assertThat(saved.path("simulation").path("summaryProgramIdentifier").asInt()).isEqualTo(500);
    }
    @Test void thermalResultCannotBeValidatedAsCompositionalOrE100() throws Exception {
        var i = inspection(); var r = result(i);
        assertThat(validator.validateThermal("THERM3.DATA", r, i).result()).isEqualTo(r);
        assertThatThrownBy(() -> validator.validateCompositional("THERM3.DATA", r, i)).isInstanceOf(EclipseSummaryResultValidator.ResultValidationException.class);
        assertThatThrownBy(() -> validator.validate("THERM3.DATA", r)).isInstanceOf(EclipseSummaryResultValidator.ResultValidationException.class);
    }
    @Test void originalTemperatureUnitsRealCellAndModelWellAreRequired() throws Exception {
        for (var fault : new String[]{"unit", "cell", "zero", "well", "clock", "nan", "missing", "program", "names", "schema"}) {
            var i = inspection(); var r = result(i); var s = r.path("summary").withArray("series"); var v = (ObjectNode) s.get(0);
            switch (fault) {
                case "unit" -> v.put("unit", "DEG C");
                case "cell" -> v.put("number", 181);
                case "zero" -> v.put("number", 0);
                case "well" -> ((ObjectNode) s.get(1)).put("objectName", "UNKNOWN");
                case "clock" -> ((ObjectNode) s.get(1).path("points").get(1)).put("timeDays", 3651);
                case "nan" -> ((ObjectNode) v.path("points").get(1)).put("value", Double.NaN);
                case "missing" -> s.remove(0);
                case "program" -> ((ObjectNode) r.path("simulation")).put("summaryProgramIdentifier", 300);
                case "names" -> r.path("simulation").withArray("componentNames").set(0, mapper.getNodeFactory().textNode("OTHER"));
                default -> r.put("schemaVersion", "eclipse-summary-result/2");
            }
            assertThatThrownBy(() -> validator.validateThermal("THERM3.DATA", r, i)).as(fault).isInstanceOf(EclipseSummaryResultValidator.ResultValidationException.class);
        }
    }
    @Test void explicitMetricTemperatureUsesCelsiusWithoutConvertingNumbers() throws Exception {
        var i = inspection(); i.put("unitSystem", "METRIC"); var r = result(i);
        for (var v : r.path("summary").path("series")) ((ObjectNode) v).put("unit", "DEG C");
        assertThat(validator.validateThermal("THERM3.DATA", r, i).result()).isEqualTo(r);
    }
    @Test void wrongPhaseSetEosAndProgramCannotPersistAsThermal() throws Exception {
        for (var fault : new String[]{"phases", "eos", "program", "type", "count"}) {
            var i = inspection(); var m = (ObjectNode) i.path("simulation");
            switch (fault) {
                case "phases" -> i.withArray("phases").remove(2);
                case "eos" -> m.put("equationOfState", "PR");
                case "program" -> m.put("summaryProgramIdentifier", 300);
                case "type" -> m.put("modelType", "COMPOSITIONAL");
                default -> m.put("componentCount", 4);
            }
            assertThatThrownBy(() -> EclipseDataInspectionValidator.validateAndSerialize(i, mapper)).as(fault).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
