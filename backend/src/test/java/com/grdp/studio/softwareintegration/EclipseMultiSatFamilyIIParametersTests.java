package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseMultiSatFamilyIIParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import com.grdp.studio.softwareintegration.support.EclipseBatchParameters;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EclipseMultiSatFamilyIIParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection()throws Exception{return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-multi-sat-family-ii-inspection.json"));}
    private ObjectNode request(ObjectNode i,String field){var p=mapper.createObjectNode();p.put("schemaVersion",EclipseMultiSatFamilyIIParameters.SCHEMA);p.put("regionNumber",2);p.put("baselineRunId",1);p.put("sourceFile",i.path("caseName").asText());p.put("sourceSha256",i.path("packageFiles").path(0).path("sha256").asText());p.put("field",field);p.put("unit","1");p.put("multiplier",.5);p.set("tables",i.path("multiSatRelpermContext").path("regions").path(1).path("tables").deepCopy());return p;}
    @Test void ActualTwoTableInspectionBindsBothFullTablesAndAcceptsThreeIndependentBatchKinds()throws Exception{
        var i=inspection();assertEquals(i.toString(),EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).toString());
        for(var field:new String[]{"WATER_KR","GAS_KR","OIL_KR"}){var p=request(i,field);assertTrue(EclipseMultiSatFamilyIIParameters.matchesInspection(p,i));assertTrue(EclipseBatchParameters.scenario(p));var wrongRegion=p.deepCopy();wrongRegion.put("regionNumber",1);assertTrue(EclipseMultiSatFamilyIIParameters.valid(wrongRegion));assertFalse(EclipseMultiSatFamilyIIParameters.matchesInspection(wrongRegion,i));wrongRegion.put("regionNumber",3);assertFalse(EclipseMultiSatFamilyIIParameters.matchesInspection(wrongRegion,i));var stale=p.deepCopy();stale.put("sourceSha256","0".repeat(64));assertFalse(EclipseMultiSatFamilyIIParameters.matchesInspection(stale,i));}
    }
    @Test void RejectUnlinkedOilEndpointsTypedBoundsWrongFamiliesAndBindUnselectedCapillaryPressure()throws Exception{
        var i=inspection();var p=request(i,"OIL_KR");for(var field:new String[]{"WATER_KR","GAS_KR","OIL_KR"}){var bad=request(i,field);bad.put("multiplier",3);assertFalse(EclipseMultiSatFamilyIIParameters.valid(bad));}
        for(var text:new String[]{p.toString().replace("\"multiplier\":0.5","\"multiplier\":\"0.5\""),p.toString().replace("\"unit\":\"1\"","\"unit\":1"),p.toString().replace("\"OIL_KR\"","\"KROW_ONLY\""),p.toString().replace("\"SWFN\"","\"SWOF\"")})assertFalse(EclipseMultiSatFamilyIIParameters.valid(mapper.readTree(text)));
        var unlinked=p.deepCopy();((ArrayNode)unlinked.path("tables").path(2).path("rows").path(14).path("values")).set(2,mapper.getNodeFactory().stringNode(".8"));assertFalse(EclipseMultiSatFamilyIIParameters.valid(unlinked));
        var changed=p.deepCopy();((ArrayNode)changed.path("tables").path(0).path("rows").path(0).path("values")).set(2,mapper.getNodeFactory().stringNode("7"));assertTrue(EclipseMultiSatFamilyIIParameters.valid(changed));assertFalse(EclipseMultiSatFamilyIIParameters.matchesInspection(changed,i));
        var reordered=p.deepCopy();var tables=(ArrayNode)reordered.path("tables");var first=tables.path(0).deepCopy();tables.set(0,tables.path(1));tables.set(1,first);assertFalse(EclipseMultiSatFamilyIIParameters.valid(reordered));
    }
}
