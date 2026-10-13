package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.PipesimNetworkResultValidator;
import com.grdp.studio.softwareintegration.execution.PipesimResultValidator;
import com.grdp.studio.softwareintegration.execution.PipesimWellResultValidator;
import com.grdp.studio.softwareintegration.support.SystemAnalysisParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemAnalysisScenarioTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PipesimResultValidator validator = new PipesimResultValidator(
            new PipesimWellResultValidator(), new PipesimNetworkResultValidator());

    private ObjectNode parameters(int version, String producer) {
        ObjectNode value = mapper.createObjectNode();
        value.put("schemaVersion", "pipesim-system-analysis-parameters/" + version);
        value.put("producer", producer).put("branchTerminator", "Flowline_1")
                .put("outletPressurePsi", 600).put("scanVariable", "liquidFlowRate");
        value.putArray("values").add(2400).add(3000);
        return value;
    }

    private ObjectNode result(int version, String producer) {
        ObjectNode value = mapper.createObjectNode();
        value.put("schemaVersion", "pipesim-system-analysis-result/" + version).put("model_kind", "network")
                .put("runTask", "system-analysis").put("resultContract", "VALID_FULL").put("study", "Study 1")
                .put("producer", producer).put("branchTerminator", "Flowline_1")
                .put("outletPressurePsi", 600).put("scanVariable", "liquidFlowRate");
        ArrayNode cases = value.putArray("cases");
        for (double flow : new double[]{2400, 3000}) {
            ObjectNode item = cases.addObject().put("caseName", "Base Case").put("scanValue", flow);
            item.putArray("system").addObject().put("variable", "Pressure").put("unit", "psia").put("value", 1200);
            item.putArray("node").addObject().put("node", producer).putArray("variables")
                    .addObject().put("variable", "Pressure").put("unit", "psia").put("value", 1200);
            ArrayNode variables = item.putObject("profile").put("pointCount", 2).putArray("variables");
            for (String name : new String[]{"TotalDistance", "Pressure", "Temperature"}) {
                ObjectNode variable = variables.addObject().put("variable", name)
                        .put("unit", "TotalDistance".equals(name) ? "ft" : "Pressure".equals(name) ? "psia" : "degF");
                variable.putArray("values").add(100).add(200);
            }
        }
        return value;
    }

    @Test void acceptsDynamicNameAndKeepsOldRequestRestriction() {
        assertThat(SystemAnalysisParameters.valid(parameters(2, "Well_1"))).isTrue();
        assertThat(SystemAnalysisParameters.valid(parameters(1, "Well_1"))).isFalse();
        assertThat(SystemAnalysisParameters.valid(parameters(1, "Well"))).isTrue();
        assertThat(validator.validate("system-analysis", "Study 1", result(2, "Well_1"), parameters(2, "Well_1")).contract())
                .isEqualTo("VALID_FULL");
        assertThat(validator.validate("system-analysis", "Study 1", result(1, "Well")).contract()).isEqualTo("VALID_FULL");
    }

    @Test void rejectsWrongProducerBranchPressureStudyAndScanWithoutRelaxingShape() {
        for (String field : new String[]{"producer", "branchTerminator", "outletPressurePsi", "study"}) {
            ObjectNode wrong = result(2, "Well_1");
            if ("outletPressurePsi".equals(field)) wrong.put(field, 700); else wrong.put(field, "Other");
            rejects(wrong, parameters(2, "Well_1"));
        }
        ObjectNode wrong = result(2, "Well_1");
        ((ObjectNode) wrong.path("cases").get(0)).put("scanValue", 2500);
        rejects(wrong, parameters(2, "Well_1"));
        ObjectNode extra = result(2, "Well_1");
        extra.put("arbitrary", true);
        rejects(extra, parameters(2, "Well_1"));
    }

    @Test void dynamicResultRequiresMatchingRequestAndCannotBorrowOldSchema() {
        assertThatThrownBy(() -> validator.validate("system-analysis", "Study 1", result(2, "Well_1")))
                .isInstanceOf(PipesimResultValidator.ResultValidationException.class);
        rejects(result(1, "Well"), parameters(2, "Well"));
        rejects(result(2, "Well"), parameters(1, "Well"));
    }

    @Test void rejectsMissingCaseRequiredTemperatureUnitAndDuplicateVariable() {
        ObjectNode missingCase = result(2, "Well_1");
        ((ArrayNode) missingCase.path("cases")).remove(1);
        rejects(missingCase, parameters(2, "Well_1"));
        ObjectNode missingUnit = result(2, "Well_1");
        ((ObjectNode) missingUnit.path("cases").get(0).path("profile").path("variables").get(1)).putNull("unit");
        rejects(missingUnit, parameters(2, "Well_1"));
        ObjectNode missingTemperature = result(2, "Well_1");
        ((ArrayNode) missingTemperature.path("cases").get(0).path("profile").path("variables")).remove(2);
        rejects(missingTemperature, parameters(2, "Well_1"));
        ObjectNode duplicate = result(2, "Well_1");
        ((ArrayNode) duplicate.path("cases").get(0).path("profile").path("variables"))
                .add(duplicate.path("cases").get(0).path("profile").path("variables").get(2).deepCopy());
        rejects(duplicate, parameters(2, "Well_1"));
    }

    @Test void rejectsUnsafeNamesUnknownSchemaAndDuplicateAxes() {
        for (String name : new String[]{"", " Well_1", "Well_1\n", "../Well_1"}) {
            assertThat(SystemAnalysisParameters.valid(parameters(2, name))).isFalse();
        }
        assertThat(SystemAnalysisParameters.valid(parameters(3, "Well_1"))).isFalse();
        ObjectNode duplicate = parameters(2, "Well_1");
        duplicate.putArray("values").add(2400).add(2400);
        assertThat(SystemAnalysisParameters.valid(duplicate)).isFalse();
    }

    @Test void rejectsWrongDimensionAndUnknownUnitsInEveryNumericSection() {
        for (String unit : new String[]{"STB/d", "banana", "psig", "bar"}) {
            ObjectNode wrong = result(2, "Well_1");
            ((ObjectNode) wrong.path("cases").get(0).path("profile").path("variables").get(1)).put("unit", unit);
            rejects(wrong, parameters(2, "Well_1"));
        }
        ObjectNode system = result(2, "Well_1");
        ((ObjectNode) system.path("cases").get(0).path("system").get(0)).put("unit", "ft");
        rejects(system, parameters(2, "Well_1"));
        ObjectNode node = result(2, "Well_1");
        ((ObjectNode) node.path("cases").get(0).path("node").get(0).path("variables").get(0)).put("unit", "degF");
        rejects(node, parameters(2, "Well_1"));
    }

    @Test void rejectsNativeUnavailableSentinelsInEveryNumericSection() {
        for (double missing : new double[]{-1e31, 1.2345e25}) {
            ObjectNode system = result(2, "Well_1");
            ((ObjectNode) system.path("cases").get(0).path("system").get(0)).put("value", missing);
            rejects(system, parameters(2, "Well_1"));
            ObjectNode node = result(2, "Well_1");
            ((ObjectNode) node.path("cases").get(0).path("node").get(0).path("variables").get(0)).put("value", missing);
            rejects(node, parameters(2, "Well_1"));
            ObjectNode profile = result(2, "Well_1");
            ((ObjectNode) profile.path("cases").get(0).path("profile").path("variables").get(1))
                    .putArray("values").add(missing).add(200);
            rejects(profile, parameters(2, "Well_1"));
        }
    }

    private void rejects(ObjectNode result, ObjectNode request) {
        assertThatThrownBy(() -> validator.validate("system-analysis", "Study 1", result, request))
                .isInstanceOf(PipesimResultValidator.ResultValidationException.class);
    }
}
