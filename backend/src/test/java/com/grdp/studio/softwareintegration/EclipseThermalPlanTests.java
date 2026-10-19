package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseThermalPlanParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class EclipseThermalPlanTests {
    private final JsonMapper mapper = new JsonMapper();
    private JsonNode inspection() { return mapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-injection-inspection.json")); }
    private ObjectNode parameters() {
        var c=inspection().path("thermalInjectionContext");var r=c.path("records").get(0);
        var p=mapper.createObjectNode().put("schemaVersion",EclipseThermalPlanParameters.SCHEMA).put("baselineRunId",1)
            .put("temperatureUnit","degF").put("rateUnit","STB/day").put("pressureUnit","psia");
        for(var k:new String[]{"sourceFile","sourceSha256"})p.set(k,c.get(k));
        for(var k:new String[]{"well","lineNumber","controlLineNumber"})p.set(k,r.get(k));
        p.putObject("original").put("surfaceInjectionRate",37.5).put("injectionTemperature",450).put("steamQuality",.7).put("bhpUpperLimit",1000);
        var stages=p.putArray("stages");stages.addObject().put("startTimeDays",1095).put("surfaceInjectionRate",25).put("injectionTemperature",450).put("steamQuality",.7).put("bhpUpperLimit",1000);
        stages.addObject().put("startTimeDays",2190).put("surfaceInjectionRate",25).put("injectionTemperature",400).put("steamQuality",.7).put("bhpUpperLimit",1000);return p;
    }
    @Test void ExactOriginalControlsAndTwoStageScheduleAreAccepted() {
        var p=parameters();assertTrue(EclipseThermalPlanParameters.valid(p));assertTrue(EclipseThermalPlanParameters.matchesInspection(p,inspection()));
        var stages=p.withArray("stages");stages.addObject().put("startTimeDays",2920).put("surfaceInjectionRate",37.5).put("injectionTemperature",450).put("steamQuality",.7).put("bhpUpperLimit",1000);
        assertTrue(EclipseThermalPlanParameters.matchesInspection(p,inspection()));
    }
    @Test void InvalidShapePhysicsAndStageOrderingAreRejected() {
        for(var fault:new String[]{"one","nine","order","duplicate","noop","string","fraction","missing","extra","quality","temperature","schema","units","rootExtra"}) {
            var p=parameters();var stages=p.withArray("stages");var first=(ObjectNode)stages.get(0);
            switch(fault) {
                case "one"->stages.remove(1);case "nine"->{for(int j=0;j<7;j++)stages.add(first.deepCopy());}
                case "order"->first.put("startTimeDays",2500);case "duplicate"->first.put("startTimeDays",2190);
                case "noop"->p.path("original").properties().forEach(e->first.set(e.getKey(),e.getValue()));
                case "string"->first.put("surfaceInjectionRate","25");case "fraction"->first.put("startTimeDays",1095.5);
                case "missing"->first.remove("steamQuality");case "extra"->first.put("endTimeDays",2000);
                case "quality"->first.put("steamQuality",1.1);case "temperature"->first.put("injectionTemperature",-459.67);
                case "schema"->p.put("schemaVersion","eclipse-thermal-injection-parameters/3");case "units"->p.put("pressureUnit","barsa");default->p.put("targetValue",1);
            }
            assertFalse(EclipseThermalPlanParameters.valid(p),fault);
        }
    }
    @Test void ForeignSourceOriginalsRowsAndNonReportTimesNeverMatch() {
        for(var fault:new String[]{"source","well","line","controlLine","original","boundary","end"}) {
            var p=parameters();switch(fault) {
                case "source"->p.put("sourceSha256","0".repeat(64));case "well"->p.put("well","PNEAR");case "line"->p.put("lineNumber",999);
                case "controlLine"->p.put("controlLineNumber",1);case "original"->((ObjectNode)p.path("original")).put("steamQuality",.6);
                case "boundary"->((ObjectNode)p.path("stages").get(0)).put("startTimeDays",1094);default->((ObjectNode)p.path("stages").get(1)).put("startTimeDays",3650);
            }
            assertFalse(EclipseThermalPlanParameters.matchesInspection(p,inspection()),fault);
        }
    }
}
