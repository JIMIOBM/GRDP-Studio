package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseThermalInjectionParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
class EclipseThermalStageTests {
 private final JsonMapper mapper=new JsonMapper();
 private tools.jackson.databind.JsonNode inspection(){return mapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-injection-inspection.json"));}
 private ObjectNode parameters(){var i=inspection();var c=i.path("thermalInjectionContext");var r=c.path("records").get(0);var p=mapper.createObjectNode().put("schemaVersion",EclipseThermalInjectionParameters.STAGE_SCHEMA).put("baselineRunId",1).put("field","SURFACE_INJECTION_RATE").put("unit","STB/day").put("originalValue",37.5).put("targetValue",25).put("startTimeDays",1825);for(var k:new String[]{"sourceFile","sourceSha256"})p.set(k,c.get(k));p.set("well",r.get("well"));p.set("lineNumber",r.get("controlLineNumber"));return p;}
 @Test void NineExistingReportBoundariesAndBothStageFieldsBindTheirOriginalSource(){var i=inspection();assertEquals(java.util.List.of(365,730,1095,1460,1825,2190,2555,2920,3285),EclipseThermalInjectionParameters.stageDays(i));var p=parameters();assertTrue(EclipseThermalInjectionParameters.matchesInspection(p,i));p.put("field","INJECTION_TEMPERATURE").put("unit","degF").put("lineNumber",433).put("originalValue",450).put("targetValue",400);assertTrue(EclipseThermalInjectionParameters.matchesInspection(p,i));}
 @Test void InvalidTimeSchemaFieldAndSourceAreRejected(){for(var fault:new String[]{"missing","string","fraction","zero","nonboundary","end","old","extra","field","hash"}){var p=parameters();switch(fault){case "missing"->p.remove("startTimeDays");case "string"->p.put("startTimeDays","1825");case "fraction"->p.put("startTimeDays",1825.5);case "zero"->p.put("startTimeDays",0);case "nonboundary"->p.put("startTimeDays",1824);case "end"->p.put("startTimeDays",3650);case "old"->p.put("schemaVersion",EclipseThermalInjectionParameters.CONTROL_SCHEMA);case "extra"->p.put("endTimeDays",3650);case "field"->p.put("field","BHP_UPPER_LIMIT");default->p.put("sourceSha256","0".repeat(64));}assertFalse(EclipseThermalInjectionParameters.matchesInspection(p,inspection()),fault);}}
}
