package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseThermalInjectionParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
class EclipseThermalInjectionTests {
 private final JsonMapper mapper=new JsonMapper();
 private ObjectNode inspection(){return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-injection-inspection.json"));}
 private ObjectNode request(ObjectNode i,String field){
  var c=i.path("thermalInjectionContext");var r=c.path("records").get(0);boolean q=field.equals("STEAM_QUALITY");
  var p=mapper.createObjectNode().put("schemaVersion",EclipseThermalInjectionParameters.SCHEMA).put("baselineRunId",1).put("field",field)
    .put("unit",q?"fraction":r.path("temperatureUnit").asText()).put("originalValue",r.path(q?"originalSteamQuality":"originalTemperature").asDouble()).put("targetValue",q?.5:400);
  for(var k:new String[]{"sourceFile","sourceSha256"})p.set(k,c.get(k));for(var k:new String[]{"well","lineNumber"})p.set(k,r.get(k));return p;
 }
 @Test void actualThermalSourceAndBothEditableFieldsRoundTripAfterNumericNormalization(){
  var i=inspection();var persisted=EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));
  assertEquals(i,mapper.readTree(mapper.writeValueAsString(persisted)));
  for(var field:new String[]{"STEAM_QUALITY","INJECTION_TEMPERATURE"})assertTrue(EclipseThermalInjectionParameters.matchesInspection(request(i,field),persisted));
  assertEquals(37.5,persisted.path("thermalInjectionContext").path("records").get(0).path("surfaceInjectionRate").asDouble());
 }
 @Test void staleSourcesUnitsOriginalValuesAndStrictTypesAreRejected(){
  for(var fault:new String[]{"sha","well","line","original","unit","field","string","range","extra","baseline"}){
   var i=inspection();var p=request(i,"STEAM_QUALITY");switch(fault){case "sha"->p.put("sourceSha256","0".repeat(64));case "well"->p.put("well","PNEAR");case "line"->p.put("lineNumber",1);
    case "original"->p.put("originalValue",.6);case "unit"->p.put("unit","degF");case "field"->p.put("field","BHP");case "string"->p.put("targetValue","0.5");case "range"->p.put("targetValue",1.01);case "extra"->p.put("bhpUpperLimit",2000);default->p.put("baselineRunId","1");}
   assertFalse(EclipseThermalInjectionParameters.matchesInspection(p,i),fault);
  }
 }
 @Test void incompleteForeignOrCorruptContextCannotBeValidated(){
  for(var fault:new String[]{"sha","size","names","count","well","quality","temperature","rate","pressure","line","extra","null"}){
   var i=inspection();var c=(ObjectNode)i.path("thermalInjectionContext");var rec=(ObjectNode)c.path("records").get(0);
   switch(fault){case "sha"->c.put("sourceSha256","0".repeat(64));case "size"->c.put("sourceSizeBytes",10627);case "names"->c.withArray("componentNames").set(0,mapper.getNodeFactory().stringNode("OTHER"));case "count"->c.put("componentCount",9);case "well"->rec.put("well","UNKNOWN");
    case "quality"->rec.put("originalSteamQuality",1.01);case "temperature"->rec.put("temperatureUnit","degC");case "rate"->rec.put("surfaceInjectionRate",0);case "pressure"->rec.put("bhpUpperLimit",0);case "line"->rec.put("controlLineNumber",500);case "extra"->c.put("extra",true);default->i.putNull("thermalInjectionContext");}
   assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper),fault);
  }
 }
 @Test void oldThermalInspectionRemainsRunnableWithoutOpeningAnEditor(){
  var i=inspection();var p=request(i,"STEAM_QUALITY");i.remove("thermalInjectionContext");assertNotNull(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));assertFalse(EclipseThermalInjectionParameters.matchesInspection(p,i));
  i.put("schemaVersion","eclipse-data-inspection/14");assertFalse(EclipseThermalInjectionParameters.matchesInspection(p,i));
 }
}
