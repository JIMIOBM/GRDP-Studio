package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseMultiWetGasPvtParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import com.grdp.studio.softwareintegration.support.EclipseBatchParameters;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EclipseMultiWetGasPvtParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection()throws Exception{return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-multi-pvtg-inspection.json"));}
    private ObjectNode request(ObjectNode i,String field){var r=i.path("multiWetGasPvtContext").path("regions").path(1);var p=mapper.createObjectNode();p.put("schemaVersion",EclipseMultiWetGasPvtParameters.SCHEMA);p.put("regionNumber",2);p.put("baselineRunId",1);p.put("sourceFile",i.path("caseName").asText());p.put("sourceSha256",i.path("packageFiles").path(0).path("sha256").asText());p.put("keywordLineNumber",r.path("keywordLineNumber").asInt());p.put("field",field);p.put("unit","VISCOSITY".equals(field)?"cP":"rm3/sm3");p.put("multiplier","VISCOSITY".equals(field)?2:1.02);p.set("records",r.path("records").deepCopy());p.set("oilTable",r.path("oilTable").deepCopy());return p;}
    @Test void ActualMatchedRegionsBindAllBranchesAndTheUneditedLiveOilBranches()throws Exception{
        var i=inspection();assertEquals(i.toString(),EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).toString());
        for(var field:new String[]{"VISCOSITY","FORMATION_VOLUME_FACTOR"}){
            var p=request(i,field);assertTrue(EclipseMultiWetGasPvtParameters.matchesInspection(p,i));assertTrue(EclipseBatchParameters.scenario(p));
            var bad=p.deepCopy();bad.put("regionNumber",1);assertTrue(EclipseMultiWetGasPvtParameters.valid(bad));assertFalse(EclipseMultiWetGasPvtParameters.matchesInspection(bad,i));bad.put("regionNumber",3);assertFalse(EclipseMultiWetGasPvtParameters.matchesInspection(bad,i));bad.put("regionNumber",2);bad.put("sourceSha256","0".repeat(64));assertFalse(EclipseMultiWetGasPvtParameters.matchesInspection(bad,i));
        }
        var changed=request(i,"VISCOSITY");((ArrayNode)changed.path("oilTable").path("records").path(0).path("rows").path(0).path("values")).set(0,mapper.getNodeFactory().stringNode("2.1"));assertTrue(EclipseMultiWetGasPvtParameters.valid(changed));assertFalse(EclipseMultiWetGasPvtParameters.matchesInspection(changed,i));
    }
    @Test void RejectTypedUnitsHighestPressureWithoutContinuationAndOverlappingSourceRegions()throws Exception{
        var i=inspection();var p=request(i,"FORMATION_VOLUME_FACTOR");
        for(var text:new String[]{p.toString().replace("\"multiplier\":1.02","\"multiplier\":\"1.02\""),p.toString().replace("\"rm3/sm3\"","1"),p.toString().replace("\"FORMATION_VOLUME_FACTOR\"","\"RS\""),p.toString().replace("\"multiplier\":1.02","\"multiplier\":101")}){assertNotEquals(p.toString(),text);assertFalse(EclipseMultiWetGasPvtParameters.valid(mapper.readTree(text)));}
        var missing=p.deepCopy();((ArrayNode)missing.path("records").path(12).path("rows")).removeAll();assertFalse(EclipseMultiWetGasPvtParameters.valid(missing));
        var wrong=i.deepCopy();((ObjectNode)wrong.path("multiWetGasPvtContext").path("regions").path(1)).put("cellCount",82);assertFalse(EclipseMultiWetGasPvtParameters.validContext(wrong.path("multiWetGasPvtContext"),wrong));assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(wrong,mapper));
        var overlap=i.deepCopy();var regions=overlap.path("multiWetGasPvtContext").path("regions");((ObjectNode)regions.path(1).path("oilTable").path("records").path(0).path("rows").path(0)).put("lineNumber",regions.path(0).path("oilTable").path("records").path(0).path("rows").path(0).path("lineNumber").asInt());assertFalse(EclipseMultiWetGasPvtParameters.validContext(overlap.path("multiWetGasPvtContext"),overlap));
    }
}
