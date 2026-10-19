package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseMultiPvtParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import com.grdp.studio.softwareintegration.support.EclipseBatchParameters;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EclipseMultiPvtParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection()throws Exception{return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-multi-pvt-inspection.json"));}
    private ObjectNode request(ObjectNode i,String keyword,String field){var p=mapper.createObjectNode();p.put("schemaVersion",EclipseMultiPvtParameters.SCHEMA);p.put("regionNumber",2);p.put("baselineRunId",1);p.put("sourceFile",i.path("caseName").asText());p.put("sourceSha256",i.path("packageFiles").path(0).path("sha256").asText());p.put("field",field);p.put("keyword",keyword);p.put("unit","VISCOSITY".equals(field)?"cP":"PVDO".equals(keyword)?"rb/stb":"rb/Mscf");p.put("multiplier","VISCOSITY".equals(field)?2:1.02);p.set("tables",i.path("multiPvtContext").path("regions").path(1).path("tables").deepCopy());return p;}
    @Test void ActualInspectionBindsBothFluidTablesAndAcceptsFourIndependentKinds()throws Exception{
        var i=inspection();assertEquals(i.toString(),EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).toString());
        for(var keyword:new String[]{"PVDO","PVDG"})for(var field:new String[]{"VISCOSITY","FORMATION_VOLUME_FACTOR"}){
            var p=request(i,keyword,field);assertTrue(EclipseMultiPvtParameters.matchesInspection(p,i));assertTrue(EclipseBatchParameters.scenario(p));
            var bad=p.deepCopy();bad.put("regionNumber",1);assertTrue(EclipseMultiPvtParameters.valid(bad));assertFalse(EclipseMultiPvtParameters.matchesInspection(bad,i));bad.put("regionNumber",3);assertFalse(EclipseMultiPvtParameters.matchesInspection(bad,i));bad.put("regionNumber",2);bad.put("sourceSha256","0".repeat(64));assertFalse(EclipseMultiPvtParameters.matchesInspection(bad,i));
        }
    }
    @Test void RejectTypedBoundsWrongUnitTableOrderMapCountsAndBindOtherFluid()throws Exception{
        var i=inspection();var p=request(i,"PVDO","VISCOSITY");
        for(var text:new String[]{p.toString().replace("\"multiplier\":2.0","\"multiplier\":\"2\""),p.toString().replace("\"cP\"","1"),p.toString().replace("\"VISCOSITY\"","\"PRESSURE\""),p.toString().replace("\"PVDO\"","\"PVTO\"")}){assertNotEquals(p.toString(),text);assertFalse(EclipseMultiPvtParameters.valid(mapper.readTree(text)));}
        var changed=p.deepCopy();((ArrayNode)changed.path("tables").path(1).path("rows").path(0).path("values")).set(1,mapper.getNodeFactory().stringNode(".99"));assertTrue(EclipseMultiPvtParameters.valid(changed));assertFalse(EclipseMultiPvtParameters.matchesInspection(changed,i));
        var bad=p.deepCopy();bad.put("multiplier",0);assertFalse(EclipseMultiPvtParameters.valid(bad));bad.put("multiplier",1);assertFalse(EclipseMultiPvtParameters.valid(bad));bad.put("multiplier",101);assertFalse(EclipseMultiPvtParameters.valid(bad));
        var volume=request(i,"PVDO","FORMATION_VOLUME_FACTOR");volume.put("multiplier",9);assertFalse(EclipseMultiPvtParameters.valid(volume));volume.put("unit","rb/Mscf");assertFalse(EclipseMultiPvtParameters.valid(volume));
        var reordered=p.deepCopy();var tables=(ArrayNode)reordered.path("tables");var first=tables.path(0).deepCopy();tables.set(0,tables.path(1));tables.set(1,first);assertFalse(EclipseMultiPvtParameters.valid(reordered));
        var wrong=i.deepCopy();((ObjectNode)wrong.path("multiPvtContext").path("regions").path(1)).put("cellCount",8);assertFalse(EclipseMultiPvtParameters.validContext(wrong.path("multiPvtContext"),wrong));assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(wrong,mapper));
    }
}
