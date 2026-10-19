package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseThermalInjectionParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
class EclipseThermalControlTests {
 private final JsonMapper mapper=new JsonMapper();
 private ObjectNode inspection(){return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-injection-inspection.json"));}
 private ObjectNode request(ObjectNode i,String field){
  var c=i.path("thermalInjectionContext");var r=c.path("records").get(0);boolean rate=field.equals("SURFACE_INJECTION_RATE");
  var p=mapper.createObjectNode().put("schemaVersion",EclipseThermalInjectionParameters.CONTROL_SCHEMA).put("baselineRunId",1).put("field",field).put("unit",rate?"STB/day":"psia")
   .put("originalValue",r.path(rate?"surfaceInjectionRate":"bhpUpperLimit").asDouble()).put("targetValue",rate?25:700).put("lineNumber",r.path("controlLineNumber").asInt());
  for(var k:new String[]{"sourceFile","sourceSha256"})p.set(k,c.get(k));p.set("well",r.get("well"));return p;
 }
 @Test void bothControlFieldsBindTheControlRowAndPreserveTheOriginalSchemaBoundary(){
  for(var field:new String[]{"SURFACE_INJECTION_RATE","BHP_UPPER_LIMIT"}){
   var i=inspection();var p=request(i,field);assertTrue(EclipseThermalInjectionParameters.matchesInspection(p,i));assertEquals(429,p.path("lineNumber").asInt());
   p.put("schemaVersion",EclipseThermalInjectionParameters.SCHEMA);assertFalse(EclipseThermalInjectionParameters.valid(p));
  }
 }
 @Test void originalRatePressureUnitsAndControlRowsCannotBeReplacedWithWinjtempBindings(){
  for(var field:new String[]{"SURFACE_INJECTION_RATE","BHP_UPPER_LIMIT"})for(var fault:new String[]{"line","unit","original","source","same","zero","string"}){
   var i=inspection();var p=request(i,field);
   switch(fault){case "line"->p.put("lineNumber",433);case "unit"->p.put("unit",field.equals("SURFACE_INJECTION_RATE")?"sm3/day":"barsa");case "original"->p.put("originalValue",1);
    case "source"->p.put("sourceSha256","0".repeat(64));case "same"->p.set("targetValue",p.get("originalValue"));case "zero"->p.put("targetValue",0);default->p.put("targetValue","25");}
   assertFalse(EclipseThermalInjectionParameters.matchesInspection(p,i),field+fault);
  }
 }
}
