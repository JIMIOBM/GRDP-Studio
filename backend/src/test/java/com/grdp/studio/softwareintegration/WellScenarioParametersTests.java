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
        for (String task : new String[]{"nodal", "profile", "combined", "eclipse"}) {
            assertThat(WellScenarioParameters.valid(null, "basic_gas", task)).isTrue();
            assertThat(WellScenarioParameters.valid(valid, "basic_gas", task)).isEqualTo(java.util.Set.of("nodal", "profile", "combined").contains(task));
            assertThat(WellScenarioParameters.valid(valid, "black_oil_liquid", task)).isEqualTo("nodal".equals(task));
            assertThat(WellScenarioParameters.valid(valid, "legacy_well", task)).isFalse();
        }
        assertThat(WellScenarioParameters.valid(null, "basic_gas", "network")).isFalse();
    }

    @Test void acceptsOnlyTheClosedEclipseWholeWellScheduleContract() {
        var valid = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"SHUT\"}");
        var control = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/2\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"OPEN\",\"controlMode\":\"ORAT\",\"targetOilRate\":55.5}");
        var injection = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/3\",\"baselineRunId\":74,\"well\":\"D1\",\"date\":\"1970-01-15\",\"injectionType\":\"WATER\",\"controlMode\":\"RATE\",\"targetInjectionRate\":1500}");
        var eventPair = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/5\",\"baselineRunId\":74,\"well\":\"G1\",\"events\":[{\"date\":\"1970-01-15\",\"status\":\"SHUT\"},{\"date\":\"1970-02-01\",\"status\":\"OPEN\"}]}");
        assertThat(WellScenarioParameters.valid(valid, "eclipse_100", "eclipse")).isTrue();
        assertThat(WellScenarioParameters.valid(control, "eclipse_100", "eclipse")).isTrue();
        assertThat(WellScenarioParameters.valid(injection, "eclipse_100", "eclipse")).isTrue();
        assertThat(WellScenarioParameters.valid(eventPair, "eclipse_100", "eclipse")).isTrue();
        assertThat(WellScenarioParameters.valid(valid, "basic_gas", "eclipse")).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"OPEN\",\"extra\":true}"), "eclipse_100", "eclipse")).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-02-30\",\"status\":\"OPEN\"}"), "eclipse_100", "eclipse")).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/2\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"OPEN\",\"controlMode\":\"WRAT\",\"targetOilRate\":55.5}"), "eclipse_100", "eclipse")).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree(eventPair.toString().replace("1970-02-01", "1970-01-15")), "eclipse_100", "eclipse")).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree(eventPair.toString().replace("SHUT", "OPEN")), "eclipse_100", "eclipse")).isFalse();
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

    @Test void acceptsEspParameterSchemeForLegacyAndStandardWellKinds() {
        var inspection = mapper.readTree("{\"schemaVersion\":\"pipesim-well-inspection/4\",\"reservoirPressure\":null,\"packageFiles\":[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"" + "a".repeat(64) + "\"}],\"well\":\"Well_1\",\"espContexts\":[\"Well_1:ESP\"]}");
        var espParameter = mapper.readTree("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/1\",\"espContext\":\"Well_1:ESP\",\"parameter\":\"operatingFrequency\",\"targetValueHz\":70}");
        assertThat(WellScenarioParameters.valid(espParameter, "black_oil_liquid", "esp-parameter", inspection)).isTrue();
        assertThat(WellScenarioParameters.valid(espParameter, "basic_gas", "esp-parameter", inspection)).isTrue();
        assertThat(WellScenarioParameters.valid(espParameter, "legacy_well", "esp-parameter", inspection)).isTrue();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/1\",\"espContext\":\"AUTO\",\"parameter\":\"operatingFrequency\",\"targetValueHz\":70}"), "legacy_well", "esp-parameter", inspection)).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/1\",\"espContext\":\"Well_2:ESP\",\"parameter\":\"operatingFrequency\",\"targetValueHz\":70}"), "legacy_well", "esp-parameter", inspection)).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/1\",\"espContext\":\"Well_1:ESP\",\"parameter\":\"operatingFrequency\",\"targetValueHz\":0}"), "legacy_well", "esp-parameter", inspection)).isFalse();
        var headFactor = mapper.readTree("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/2\",\"espContext\":\"Well_1:ESP\",\"parameter\":\"headFactor\",\"targetValue\":0.9}");
        assertThat(WellScenarioParameters.valid(headFactor, "legacy_well", "esp-parameter", inspection)).isTrue();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/2\",\"espContext\":\"Well_1:ESP\",\"parameter\":\"headFactor\",\"targetValue\":2.1}"), "legacy_well", "esp-parameter", inspection)).isFalse();
        assertThat(WellScenarioParameters.valid(espParameter, "legacy_well", "esp-parameter", mapper.readTree(inspection.toString().replace("inspection/4", "inspection/3")))).isFalse();
    }

    @Test void multiWellInspectionRequiresExplicitExistingWellAndEspBindings() {
        var inspection = mapper.readTree("""
                {"schemaVersion":"pipesim-well-inspection/5","reservoirPressure":null,
                 "packageFiles":[{"relativePath":"model.pips","sizeBytes":12,"sha256":"%s"}],"well":null,
                 "espContexts":["Well_1:ESP","Well_2:ESP"],
                 "wellContexts":[{"context":"Well_1","espContexts":["Well_1:ESP"]},{"context":"Well_2","espContexts":["Well_2:ESP"]}]}
                """.formatted("a".repeat(64)));
        var trajectory = mapper.readTree("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/2\",\"wellContext\":\"Well_2\"}");
        var unboundTrajectory = mapper.readTree("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/1\"}");
        var espCurves = mapper.readTree("{\"schemaVersion\":\"pipesim-esp-curves-parameters/2\",\"wellContext\":\"Well_2\",\"espContext\":\"Well_2:ESP\"}");
        var espParameter = mapper.readTree("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/3\",\"wellContext\":\"Well_2\",\"espContext\":\"Well_2:ESP\",\"parameter\":\"headFactor\",\"targetValue\":0.9}");
        var mismatchedEspParameter = mapper.readTree(espParameter.toString().replace("Well_2:ESP", "Well_1:ESP"));

        assertThat(WellScenarioParameters.valid(trajectory, "legacy_well", "trajectory", inspection)).isTrue();
        assertThat(WellScenarioParameters.valid(unboundTrajectory, "legacy_well", "trajectory", inspection)).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/2\",\"wellContext\":\"missing\"}"), "legacy_well", "trajectory", inspection)).isFalse();
        assertThat(WellScenarioParameters.valid(espCurves, "legacy_well", "esp-curves", inspection)).isTrue();
        assertThat(WellScenarioParameters.valid(mapper.readTree("{\"schemaVersion\":\"pipesim-esp-curves-parameters/1\"}"), "legacy_well", "esp-curves", inspection)).isFalse();
        assertThat(WellScenarioParameters.valid(espParameter, "legacy_well", "esp-parameter", inspection)).isTrue();
        assertThat(WellScenarioParameters.valid(mismatchedEspParameter, "legacy_well", "esp-parameter", inspection)).isFalse();
        assertThat(WellScenarioParameters.valid(null, "legacy_well", "nodal", inspection)).isFalse();
    }

    @Test void versionSixInspectionRequiresPerWellCompletionAndTubingForEveryFlowTask() {
        var noFlowObjects = mapper.readTree("""
                {"schemaVersion":"pipesim-well-inspection/6","reservoirPressure":null,
                 "packageFiles":[{"relativePath":"model.pips","sizeBytes":12,"sha256":"%s"}],
                 "well":"Well_1","espContexts":[],
                 "wellContexts":[{"context":"Well_1","espContexts":[],"completionContexts":[],"tubingContexts":[]}]}
                """.formatted("a".repeat(64)));
        var hasFlowObjects = mapper.readTree(noFlowObjects.toString()
                .replace("\"completionContexts\":[]", "\"completionContexts\":[\"Well_1:Completion\"]")
                .replace("\"tubingContexts\":[]", "\"tubingContexts\":[\"Well_1:Tubing\"]"));
        var trajectory = mapper.readTree("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/2\",\"wellContext\":\"Well_1\"}");
        var sensitivity = mapper.readTree("{\"schemaVersion\":\"pipesim-well-sensitivity-parameters/1\",\"targetVariable\":\"reservoirPressure\",\"values\":[3000,4000,5000]}");

        for (String task : new String[]{"nodal", "profile", "combined"}) {
            assertThat(WellScenarioParameters.valid(null, "legacy_well", task, noFlowObjects)).as(task).isFalse();
            assertThat(WellScenarioParameters.valid(null, "legacy_well", task, hasFlowObjects)).as(task).isTrue();
        }
        assertThat(WellScenarioParameters.valid(sensitivity, "black_oil_liquid", "sensitivity", noFlowObjects)).isFalse();
        assertThat(WellScenarioParameters.valid(sensitivity, "black_oil_liquid", "sensitivity", hasFlowObjects)).isTrue();
        assertThat(WellScenarioParameters.valid(trajectory, "legacy_well", "trajectory", noFlowObjects)).isTrue();
        assertThat(WellScenarioParameters.valid(trajectory, "legacy_well", "trajectory", hasFlowObjects)).isTrue();
        var mismatchedWell = mapper.readTree("{\"schemaVersion\":\"pipesim-esp-curves-parameters/2\",\"wellContext\":\"Well_2\",\"espContext\":\"Well_2:ESP\"}");
        assertThat(WellScenarioParameters.valid(mismatchedWell, "legacy_well", "esp-curves", hasFlowObjects)).isFalse();
    }

    @Test void mixedWellNetworkTasksRequireValidatedCapability() throws Exception {
        var network = mapper.readTree("{\"schemaVersion\":\"pipesim-network-parameters/1\",\"boundaries\":[{\"node\":\"Source_1\",\"pressure\":150}]}");
        var withoutCapability = mapper.readTree("{\"schemaVersion\":\"pipesim-well-inspection/6\",\"reservoirPressure\":null,\"packageFiles\":[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"" + "a".repeat(64) + "\"}],\"well\":\"Well_1\",\"espContexts\":[],\"wellContexts\":[{\"context\":\"Well_1\",\"espContexts\":[],\"completionContexts\":[\"Well_1:Completion\"],\"tubingContexts\":[\"Well_1:Tubing\"]}]}");
        var withCapability = mapper.readTree("{\"schemaVersion\":\"pipesim-well-inspection/7\",\"reservoirPressure\":null,\"packageFiles\":[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"" + "a".repeat(64) + "\"}],\"well\":\"Well_1\",\"espContexts\":[],\"wellContexts\":[{\"context\":\"Well_1\",\"espContexts\":[],\"completionContexts\":[\"Well_1:Completion\"],\"tubingContexts\":[\"Well_1:Tubing\"]}],\"networkCapability\":{\"schemaVersion\":\"pipesim-network-capability/1\",\"studies\":[\"Study 1\"],\"networkInspection\":{\"schemaVersion\":\"pipesim-network-inspection/3\",\"studies\":[{\"study\":\"Study 1\",\"boundaries\":[{\"node\":\"Source_1\",\"boundaryNodeType\":\"Source\",\"isActive\":true,\"isSurfaceCondition\":true,\"flowRateType\":\"GasFlowRate\",\"pressure\":120,\"temperature\":80,\"gasFlowRate\":5,\"liquidFlowRate\":null,\"massFlowRate\":null}]}],\"chokes\":[],\"packageFiles\":[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"" + "a".repeat(64) + "\"}]}}}");

        assertThat(WellScenarioParameters.valid(network, "basic_gas", "network", withoutCapability)).isFalse();
        assertThat(WellScenarioParameters.valid(network, "basic_gas", "network", withCapability)).isTrue();
        assertThat(WellScenarioParameters.valid(null, "basic_gas", "network", withCapability)).isTrue();
        assertThat(WellScenarioParameters.valid(null, "basic_gas", "system-analysis", withCapability)).isFalse();
        assertThat(WellScenarioParameters.valid(network, "basic_gas", "network-optimizer", withCapability)).isFalse();
    }

    @Test void networkWellPerformanceRequiresInspectionBoundWellAndFlowlineAndInchUnits() {
        var inspection = mapper.readTree("""
                {"schemaVersion":"pipesim-network-inspection/4",
                 "studies":[{"study":"Study 1","boundaries":[{"node":"Sink","boundaryNodeType":"Sink","isActive":true,"isSurfaceCondition":true,"flowRateType":null,"pressure":100,"temperature":null,"gasFlowRate":null,"liquidFlowRate":null,"massFlowRate":null}]}],
                 "chokes":[],"packageFiles":[{"relativePath":"network.pips","sizeBytes":12,"sha256":"%s"}],
                 "wellContexts":["Well"],"flowlineContexts":["FL-2"]}
                """.formatted("a".repeat(64)));
        var parameters = mapper.readTree("""
                {"schemaVersion":"pipesim-network-well-performance-parameters/1","producerContext":"Well",
                 "branchTerminator":"FL-2","sensitivityUnit":"in","sensitivityValues":[4,5,6]}
                """);

        assertThat(WellScenarioParameters.valid(parameters, "network", "network-well-performance-curves", inspection)).isTrue();
        assertThat(WellScenarioParameters.valid(parameters, "network", "network-well-performance-curves",
                mapper.readTree(inspection.toString().replace("FL-2", "OtherFlowline")))).isFalse();
        assertThat(WellScenarioParameters.valid(parameters, "network", "network-well-performance-curves",
                mapper.readTree(inspection.toString().replace("inspection/4", "inspection/3")))).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree(parameters.toString().replace("\"in\"", "\"mm\"")),
                "network", "network-well-performance-curves", inspection)).isFalse();
    }

    @Test void v6EspTasksRequirePerWellCompletionAndTubingButTrajectoryDoesNot() {
        var inspection = mapper.readTree("""
                {"schemaVersion":"pipesim-well-inspection/6","reservoirPressure":null,
                 "packageFiles":[{"relativePath":"model.pips","sizeBytes":12,"sha256":"%s"}],"well":null,
                 "espContexts":["Well_1:ESP","Well_2:ESP"],
                 "wellContexts":[
                   {"context":"Well_1","espContexts":["Well_1:ESP"],"completionContexts":["Well_1:Completion"],"tubingContexts":["Well_1:Tubing"]},
                   {"context":"Well_2","espContexts":["Well_2:ESP"],"completionContexts":[],"tubingContexts":[]}]}
                """.formatted("a".repeat(64)));
        var trajectory = mapper.readTree("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/2\",\"wellContext\":\"Well_2\"}");
        var trajectoryEdit = mapper.readTree("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/3\",\"wellContext\":\"Well_2\",\"stationIndex\":2,\"targetInclinationDeg\":26.5}");
        var espCurves = mapper.readTree("{\"schemaVersion\":\"pipesim-esp-curves-parameters/2\",\"wellContext\":\"Well_2\",\"espContext\":\"Well_2:ESP\"}");
        var espParameter = mapper.readTree("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/3\",\"wellContext\":\"Well_2\",\"espContext\":\"Well_2:ESP\",\"parameter\":\"headFactor\",\"targetValue\":0.9}");

        assertThat(WellScenarioParameters.valid(trajectory, "legacy_well", "trajectory", inspection)).isTrue();
        assertThat(WellScenarioParameters.valid(trajectoryEdit, "legacy_well", "trajectory", inspection)).isTrue();
        assertThat(WellScenarioParameters.valid(mapper.readTree(trajectoryEdit.toString().replace("Well_2", "Well_3")), "legacy_well", "trajectory", inspection)).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree(trajectoryEdit.toString().replace("\"stationIndex\":2", "\"stationIndex\":0")), "legacy_well", "trajectory", inspection)).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree(trajectoryEdit.toString().replace("26.5", "91")), "legacy_well", "trajectory", inspection)).isFalse();
        assertThat(WellScenarioParameters.valid(espCurves, "legacy_well", "esp-curves", inspection)).isFalse();
        assertThat(WellScenarioParameters.valid(espParameter, "legacy_well", "esp-parameter", inspection)).isFalse();
        assertThat(WellScenarioParameters.valid(mapper.readTree(espCurves.toString().replace("Well_2", "Well_1")), "legacy_well", "esp-curves", inspection)).isTrue();
    }

    @Test void scheduleInspectionKeepsWeelopenCompatibleAndRequiresWconHistForOrat() {
        var inspection = mapper.readTree("""
                {"schemaVersion":"eclipse-data-inspection/4",
                 "scheduleMetadata":{"wells":[{"name":"G1"}],"records":[{"keyword":"WCONHIST","values":["G1","OPEN","ORAT","35.686"]}]},
                 "scheduleTimeline":[{"kind":"DATES","records":[{"day":"15","month":"JAN","year":"1970"}]},{"kind":"DATES","records":[{"day":"1","month":"FEB","year":"1970"}]}]}
                """);
        var wholeWell = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"OPEN\"}");
        var wconHist = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/2\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"SHUT\",\"controlMode\":\"ORAT\",\"targetOilRate\":55.5}");
        var missingWconHist = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/2\",\"baselineRunId\":74,\"well\":\"G2\",\"date\":\"1970-01-15\",\"status\":\"SHUT\",\"controlMode\":\"ORAT\",\"targetOilRate\":55.5}");
        var missingWconInje = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/3\",\"baselineRunId\":74,\"well\":\"D2\",\"date\":\"1970-01-15\",\"injectionType\":\"WATER\",\"controlMode\":\"RATE\",\"targetInjectionRate\":1500}");
        var wconProd = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/4\",\"baselineRunId\":74,\"well\":\"HORW2\",\"phase\":\"FORECAST_INITIAL\",\"status\":\"OPEN\",\"controlMode\":\"ORAT\",\"targetOilRate\":4500}");
        var eventPair = mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/5\",\"baselineRunId\":74,\"well\":\"G1\",\"events\":[{\"date\":\"1970-01-15\",\"status\":\"SHUT\"},{\"date\":\"1970-02-01\",\"status\":\"OPEN\"}]}");

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
        assertThat(EclipseScheduleParameters.matchesInspection(eventPair, inspection)).isTrue();
        assertThat(EclipseScheduleParameters.matchesInspection(mapper.readTree(eventPair.toString().replace("1970-02-01", "1970-03-01")), inspection)).isFalse();
        assertThat(EclipseScheduleParameters.valid(mapper.readTree("{\"schemaVersion\":\"eclipse-schedule-parameters/4\",\"baselineRunId\":74,\"well\":\"HORW2\",\"phase\":\"HISTORY\",\"status\":\"OPEN\",\"controlMode\":\"ORAT\",\"targetOilRate\":4500}"))).isFalse();
    }
}
