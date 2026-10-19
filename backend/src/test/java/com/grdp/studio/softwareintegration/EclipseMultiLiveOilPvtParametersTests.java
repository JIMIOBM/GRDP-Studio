package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseMultiLiveOilPvtParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import com.grdp.studio.softwareintegration.support.EclipseBatchParameters;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EclipseMultiLiveOilPvtParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection()throws Exception{return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-multi-pvto-inspection.json"));}
    private ObjectNode request(ObjectNode i,String field){var r=i.path("multiLiveOilPvtContext").path("regions").path(1);var p=mapper.createObjectNode();p.put("schemaVersion",EclipseMultiLiveOilPvtParameters.SCHEMA);p.put("regionNumber",2);p.put("baselineRunId",1);p.put("sourceFile",i.path("caseName").asText());p.put("sourceSha256",i.path("packageFiles").path(0).path("sha256").asText());p.put("keywordLineNumber",r.path("keywordLineNumber").asInt());p.put("field",field);p.put("unit","VISCOSITY".equals(field)?"cP":"rb/stb");p.put("multiplier","VISCOSITY".equals(field)?2:1.02);p.set("records",r.path("records").deepCopy());p.set("gasTable",r.path("gasTable").deepCopy());return p;}
    @Test void ActualMatchedRegionsBindAllBranchesAndTheUneditedDryGasTable()throws Exception{
        var i=inspection();assertEquals(i.toString(),EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).toString());
        for(var field:new String[]{"VISCOSITY","FORMATION_VOLUME_FACTOR"}){
            var p=request(i,field);assertTrue(EclipseMultiLiveOilPvtParameters.matchesInspection(p,i));assertTrue(EclipseBatchParameters.scenario(p));
            var bad=p.deepCopy();bad.put("regionNumber",1);assertTrue(EclipseMultiLiveOilPvtParameters.valid(bad));assertFalse(EclipseMultiLiveOilPvtParameters.matchesInspection(bad,i));bad.put("regionNumber",3);assertFalse(EclipseMultiLiveOilPvtParameters.matchesInspection(bad,i));bad.put("regionNumber",2);bad.put("sourceSha256","0".repeat(64));assertFalse(EclipseMultiLiveOilPvtParameters.matchesInspection(bad,i));
        }
        var changed=request(i,"VISCOSITY");((ArrayNode)changed.path("gasTable").path("rows").path(0).path("values")).set(0,mapper.getNodeFactory().stringNode("14.8"));assertTrue(EclipseMultiLiveOilPvtParameters.valid(changed));assertFalse(EclipseMultiLiveOilPvtParameters.matchesInspection(changed,i));
    }
    @Test void RejectTypedUnitsHighestRsWithoutContinuationAndOverlappingSourceRegions()throws Exception{
        var i=inspection();var p=request(i,"FORMATION_VOLUME_FACTOR");
        for(var text:new String[]{p.toString().replace("\"multiplier\":1.02","\"multiplier\":\"1.02\""),p.toString().replace("\"rb/stb\"","1"),p.toString().replace("\"FORMATION_VOLUME_FACTOR\"","\"RS\""),p.toString().replace("\"multiplier\":1.02","\"multiplier\":9")}){assertNotEquals(p.toString(),text);assertFalse(EclipseMultiLiveOilPvtParameters.valid(mapper.readTree(text)));}
        var missing=p.deepCopy();((ArrayNode)missing.path("records").path(8).path("rows")).remove(1);assertFalse(EclipseMultiLiveOilPvtParameters.valid(missing));
        var wrong=i.deepCopy();((ObjectNode)wrong.path("multiLiveOilPvtContext").path("regions").path(1)).put("cellCount",151);assertFalse(EclipseMultiLiveOilPvtParameters.validContext(wrong.path("multiLiveOilPvtContext"),wrong));assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(wrong,mapper));
        var overlap=i.deepCopy();var regions=overlap.path("multiLiveOilPvtContext").path("regions");((ObjectNode)regions.path(1).path("gasTable").path("rows").path(0)).put("lineNumber",regions.path(0).path("gasTable").path("rows").path(0).path("lineNumber").asInt());assertFalse(EclipseMultiLiveOilPvtParameters.validContext(overlap.path("multiLiveOilPvtContext"),overlap));
    }
}
