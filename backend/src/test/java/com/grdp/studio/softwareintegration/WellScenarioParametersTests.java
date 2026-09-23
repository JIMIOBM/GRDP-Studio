package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.WellScenarioParameters;
import com.grdp.studio.softwareintegration.support.EclipseScheduleParameters;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WellScenarioParametersTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void rejectsInvalidValuesAndUnsupportedModels() {
        for (String value : new String[]{"true", "\"4000\"", "0", "-1", "100001", "null"}) {
            assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"pipesim-well-parameters/1\",\"reservoirPressurePsi\":" + value + "}"), "basic_gas", "nodal")).isFalse();
        }
        var valid = mapper.readTree("{\"schemaVersion\":\"pipesim-well-parameters/1\",\"reservoirPressurePsi\":4000}");
        assertThat(WellScenarioParameters.valid(valid, "basic_gas", "nodal")).isTrue();
        assertThat(WellScenarioParameters.valid(valid, "network", "nodal")).isFalse();
        var sensitivity = mapper.readTree("{\"schemaVersion\":\"pipesim-well-sensitivity-parameters/1\",\"targetVariable\":\"reservoirPressure\",\"values\":[3000,4000,5000]}");
        assertThat(WellScenarioParameters.valid(sensitivity, "basic_gas", "sensitivity")).isTrue();
        assertThat(WellScenarioParameters.valid(sensitivity, "black_oil_liquid", "sensitivity")).isTrue();
        assertThat(WellScenarioParameters.valid(null, "basic_gas", "sensitivity")).isFalse();
        assertThat(WellScenarioParameters.valid(valid, "basic_gas", "sensitivity")).isFalse();
        var network = mapper.readTree("{\"schemaVersion\":\"pipesim-network-parameters/1\",\"boundaries\":[{\"node\":\"Supply_1\",\"pressure\":1500,\"temperature\":130}]}");
        assertThat(WellScenarioParameters.valid(network, "network", "network")).isTrue();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"pipesim-network-parameters/1\",\"boundaries\":[{\"node\":\"Supply_1\",\"flowRateType\":\"GasFlowRate\",\"liquidFlowRate\":10}]}"), "network", "network")).isFalse();
        assertThat(WellScenarioParameters.valid(network, "basic_gas", "network")).isFalse();
        assertThat(WellScenarioParameters.valid(null, "legacy_well", "nodal")).isTrue();
        var profile = mapper.readTree("{\"schemaVersion\":\"pipesim-well-profile-parameters/1\",\"outletPressurePsi\":100}");
        assertThat(WellScenarioParameters.valid(profile, "legacy_well", "profile")).isTrue();
        assertThat(WellScenarioParameters.valid(profile, "legacy_well", "combined")).isTrue();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"pipesim-well-profile-parameters/1\",\"outletPressurePsi\":0}"), "legacy_well", "profile")).isFalse();
        var systemAnalysis = mapper.readTree("{\"schemaVersion\":\"pipesim-system-analysis-parameters/1\",\"producer\":\"Well\",\"branchTerminator\":\"FL-2\",\"outletPressurePsi\":600,\"scanVariable\":\"liquidFlowRate\",\"values\":[2400,3000,3600]}");
        assertThat(WellScenarioParameters.valid(systemAnalysis, "network", "system-analysis")).isTrue();
        assertThat(WellScenarioParameters.valid(null, "network", "system-analysis")).isFalse();
        assertThat(WellScenarioParameters.valid(systemAnalysis, "basic_gas", "system-analysis")).isFalse();
        var optimizerReadOnly = mapper.readTree("{\"schemaVersion\":\"pipesim-network-optimizer-parameters/1\",\"applyResults\":false}");
        var optimizerApply = mapper.readTree("{\"schemaVersion\":\"pipesim-network-optimizer-parameters/2\",\"applyResults\":true}");
        assertThat(WellScenarioParameters.valid(optimizerReadOnly, "network", "network-optimizer")).isTrue();
        assertThat(WellScenarioParameters.valid(optimizerApply, "network", "network-optimizer")).isTrue();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"pipesim-network-optimizer-parameters/1\",\"applyResults\":true}"), "network", "network-optimizer")).isFalse();
        for (String task : new String[]{"nodal", "profile", "combined", "network", "eclipse"}) {
            assertThat(WellScenarioParameters.valid(null, "basic_gas", task)).isTrue();
            assertThat(WellScenarioParameters.valid(valid, "basic_gas", task)).isEqualTo(java.util.Set.of("nodal", "profile", "combined").contains(task));
            assertThat(WellScenarioParameters.valid(valid, "black_oil_liquid", task)).isEqualTo("nodal".equals(task));
            assertThat(WellScenarioParameters.valid(valid, "legacy_well", task)).isFalse();
        }
    }

    @Test void acceptsOnlyTheClosedEclipseWholeWellScheduleContract() {
        var valid = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"SHUT\"}");
        var control = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/2\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"OPEN\",\"controlMode\":\"ORAT\",\"targetOilRate\":55.5}");
        var injection = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/3\",\"baselineRunId\":74,\"well\":\"D1\",\"date\":\"1970-01-15\",\"injectionType\":\"WATER\",\"controlMode\":\"RATE\",\"targetInjectionRate\":1500}");
        assertThat(WellScenarioParameters.valid(valid, "eclipse_100", "eclipse")).isTrue();
        assertThat(WellScenarioParameters.valid(control, "eclipse_100", "eclipse")).isTrue();
        assertThat(WellScenarioParameters.valid(injection, "eclipse_100", "eclipse")).isTrue();
        assertThat(WellScenarioParameters.valid(valid, "basic_gas", "eclipse")).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"OPEN\",\"extra\":true}"), "eclipse_100", "eclipse")).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-02-30\",\"status\":\"OPEN\"}"), "eclipse_100", "eclipse")).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/2\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"OPEN\",\"controlMode\":\"WRAT\",\"targetOilRate\":55.5}"), "eclipse_100", "eclipse")).isFalse();
    }

    @Test void acceptsReadOnlyWellTrajectoryForBothPipesimWellKinds() {
        var trajectory = mapper.readTree("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/1\"}");
        assertThat(WellScenarioParameters.valid(trajectory, "black_oil_liquid", "trajectory")).isTrue();
        assertThat(WellScenarioParameters.valid(trajectory, "basic_gas", "trajectory")).isTrue();
        assertThat(WellScenarioParameters.valid(trajectory, "legacy_well", "trajectory")).isTrue();
        assertThat(WellScenarioParameters.valid(null, "black_oil_liquid", "trajectory")).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/1\",\"writeBack\":false}"), "black_oil_liquid", "trajectory")).isFalse();
        assertThat(WellScenarioParameters.valid(trajectory, "network", "trajectory")).isFalse();
    }

    @Test void scheduleInspectionKeepsWeelopenCompatibleAndRequiresWconHistForOrat() {
        var inspection = mapper.readTree("""
                {"schemaVersion":"eclipse-data-inspection/4",
                 "scheduleMetadata":{"wells":[{"name":"G1"}],"records":[{"keyword":"WCONHIST","values":["G1","OPEN","ORAT","35.686"]}]},
                 "scheduleTimeline":[{"kind":"DATES","records":[{"day":"15","month":"JAN","year":"1970"}]}]}
                """);
        var wholeWell = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"OPEN\"}");
        var wconHist = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/2\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"SHUT\",\"controlMode\":\"ORAT\",\"targetOilRate\":55.5}");
        var missingWconHist = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/2\",\"baselineRunId\":74,\"well\":\"G2\",\"date\":\"1970-01-15\",\"status\":\"SHUT\",\"controlMode\":\"ORAT\",\"targetOilRate\":55.5}");
        var missingWconInje = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/3\",\"baselineRunId\":74,\"well\":\"D2\",\"date\":\"1970-01-15\",\"injectionType\":\"WATER\",\"controlMode\":\"RATE\",\"targetInjectionRate\":1500}");
        var wconProd = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/4\",\"baselineRunId\":74,\"well\":\"HORW2\",\"phase\":\"FORECAST_INITIAL\",\"status\":\"OPEN\",\"controlMode\":\"ORAT\",\"targetOilRate\":4500}");

        ((tools.jackson.databind.node.ArrayNode) inspection.path("scheduleMetadata").path("wells")).addObject().put("name", "D1");
        ((tools.jackson.databind.node.ArrayNode) inspection.path("scheduleMetadata").path("records")).addObject()
                .put("keyword", "WCONINJE").set("values", mapper.createArrayNode().add("D1").add("WATER").add("1*").add("RATE").add("2000.000"));
        ((tools.jackson.databind.node.ArrayNode) inspection.path("scheduleMetadata").path("wells")).addObject().put("name", "HORW2");
        ((tools.jackson.databind.node.ArrayNode) inspection.path("scheduleMetadata").path("records")).addObject()
                .put("keyword", "WCONPROD").set("values", mapper.createArrayNode().add("HORW2").add("SHUT").add("ORAT").add("5000.000"));
        var wconInje = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/3\",\"baselineRunId\":74,\"well\":\"D1\",\"date\":\"1970-01-15\",\"injectionType\":\"WATER\",\"controlMode\":\"RATE\",\"targetInjectionRate\":1500}");

        assertThat(EclipseScheduleParameters.matchesInspection(wholeWell, inspection)).isTrue();
        assertThat(EclipseScheduleParameters.matchesInspection(wconHist, inspection)).isTrue();
        assertThat(EclipseScheduleParameters.matchesInspection(missingWconHist, inspection)).isFalse();
        assertThat(EclipseScheduleParameters.matchesInspection(wconInje, inspection)).isTrue();
        assertThat(EclipseScheduleParameters.matchesInspection(missingWconInje, inspection)).isFalse();
        assertThat(EclipseScheduleParameters.matchesInspection(wconProd, inspection)).isTrue();
        assertThat(EclipseScheduleParameters.valid(mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/4\",\"baselineRunId\":74,\"well\":\"HORW2\",\"phase\":\"HISTORY\",\"status\":\"OPEN\",\"controlMode\":\"ORAT\",\"targetOilRate\":4500}"))).isFalse();
    }
}
