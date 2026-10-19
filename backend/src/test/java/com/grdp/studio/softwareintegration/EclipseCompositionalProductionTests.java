package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseCompositionalProductionParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
class EclipseCompositionalProductionTests {
 private final JsonMapper mapper = new JsonMapper();
 private ObjectNode inspection(){return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-production-inspection.json"));}
 private ObjectNode parameters(ObjectNode i){
  var c=i.path("compositionalProductionContext");var r=c.path("records").get(0);
  var p=mapper.createObjectNode().put("schemaVersion",EclipseCompositionalProductionParameters.SCHEMA).put("baselineRunId",1).put("targetGasRate",4500);
  for(var k:new String[]{"sourceFile","sourceSha256"})p.set(k,c.get(k));
  for(var k:new String[]{"well","lineNumber","unit","originalGasRate"})p.set(k,r.get(k));return p;
 }
 @Test void actualWorkerInspectionRoundTripsWithExactSourceAndDedicatedContext(){
  var i=inspection();var p=parameters(i);assertTrue(EclipseCompositionalProductionParameters.matchesInspection(p,i));
  var persisted=EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));
  assertEquals(i,mapper.readTree(mapper.writeValueAsString(persisted)));assertTrue(EclipseCompositionalProductionParameters.matchesInspection(p,persisted));assertEquals(500,i.path("compositionalProductionContext").path("records").get(0).path("bhpLimit").asInt());
 }
 @Test void rejectsChangedPhysicalBindingAndStrictParameterTypes(){
  for(var fault:new String[]{"source","sha","line","well","unit","original","same","string","extra","baseline"}){
   var i=inspection();var p=parameters(i);switch(fault){
    case "source"->p.put("sourceFile","../SPE3_GASCOND.DATA");case "sha"->p.put("sourceSha256","0".repeat(64));case "line"->p.put("lineNumber",431);
    case "well"->p.put("well","I");case "unit"->p.put("unit","sm3/day");case "original"->p.put("originalGasRate",6199);
    case "same"->p.put("targetGasRate",6200);case "string"->p.put("targetGasRate","4500");case "extra"->p.put("unknown",true);default->p.put("baselineRunId","1");}
   assertFalse(EclipseCompositionalProductionParameters.matchesInspection(p,i),fault);
  }
 }
 @Test void corruptOrForeignContextCannotBecomeAnEditableInspection(){
  for(var fault:new String[]{"sha","size","well","count","name","unit","pressure","duplicate","extra","null"}){
   var i=inspection();var c=(ObjectNode)i.path("compositionalProductionContext");var rec=(ObjectNode)c.path("records").get(0);
   switch(fault){case "sha"->c.put("sourceSha256","0".repeat(64));case "size"->c.put("sourceSizeBytes",8774);case "well"->rec.put("well","UNKNOWN");
    case "count"->c.put("componentCount",8);case "name"->c.withArray("componentNames").set(0,mapper.getNodeFactory().stringNode("OTHER"));
    case "unit"->rec.put("unit","sm3/day");case "pressure"->rec.put("bhpLimit",0);case "duplicate"->c.withArray("records").add(rec.deepCopy());case "extra"->c.put("extra",true);default->i.putNull("compositionalProductionContext");}
   assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper),fault);
  }
 }
 @Test void oldCompositionalInspectionStillRunsWithoutOfferingAnEditor(){
  var i=inspection();var p=parameters(i);i.remove("compositionalProductionContext");
  assertNotNull(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));assertFalse(EclipseCompositionalProductionParameters.matchesInspection(p,i));
  i.put("schemaVersion","eclipse-data-inspection/15");assertFalse(EclipseCompositionalProductionParameters.matchesInspection(p,i));
 }
}
