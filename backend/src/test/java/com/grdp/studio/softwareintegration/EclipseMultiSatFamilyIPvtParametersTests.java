package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EclipseMultiSatFamilyIPvtParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection()throws Exception{return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-multi-sat-family-i-pvt-inspection.json"));}
    private ObjectNode request(ObjectNode i,String keyword,String field){
        boolean fluid=keyword.equals("PVDO")||keyword.equals("PVDG");var context=i.path(fluid?"multiPvtContext":"multiSatRelpermContext");var p=mapper.createObjectNode();
        p.put("schemaVersion",fluid?EclipseMultiPvtParameters.SCHEMA:EclipseMultiSatRelpermParameters.SCHEMA);p.put("regionNumber",2);p.put("baselineRunId",1);p.put("sourceFile",i.path("caseName").asText());p.put("sourceSha256",i.path("packageFiles").path(0).path("sha256").asText());p.put("field",field);p.put("multiplier",fluid?(field.equals("VISCOSITY")?2:1.02):.5);p.put("unit",fluid?(field.equals("VISCOSITY")?"cP":keyword.equals("PVDO")?"rb/stb":"rb/Mscf"):"1");p.set("tables",context.path("regions").path(1).path("tables").deepCopy());if(fluid)p.put("keyword",keyword);return p;
    }
    @Test void SevenPropertiesBindTheAppropriateIndependentRegionMap()throws Exception{
        var i=inspection();var saved=EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));assertEquals(mapper.readTree(i.toString()),mapper.readTree(saved.toString()));
        assertEquals(8,i.path("multiSatRelpermContext").path("regions").path(1).path("cellCount").asInt());assertEquals(7,i.path("multiPvtContext").path("regions").path(1).path("cellCount").asInt());
        for(var entry:new String[][]{{"PVDO","VISCOSITY"},{"PVDO","FORMATION_VOLUME_FACTOR"},{"PVDG","VISCOSITY"},{"PVDG","FORMATION_VOLUME_FACTOR"},{"SWOF","WATER_KR"},{"SGOF","GAS_KR"},{"SWOF","OIL_KR"}}){
            var p=request(i,entry[0],entry[1]);boolean fluid=p.has("keyword");assertTrue(EclipseBatchParameters.scenario(p));assertTrue(fluid?EclipseMultiPvtParameters.matchesInspection(p,i):EclipseMultiSatRelpermParameters.matchesInspection(p,i));
            p.put("regionNumber",1);assertFalse(fluid?EclipseMultiPvtParameters.matchesInspection(p,i):EclipseMultiSatRelpermParameters.matchesInspection(p,i));
        }
    }
    @Test void IndependentValidContextsCannotClaimTheSamePhysicalSourceLine()throws Exception{
        var i=inspection();int satHeader=i.path("multiSatRelpermContext").path("regions").path(0).path("tables").path(1).path("keywordLineNumber").asInt();
        for(var region:i.path("multiPvtContext").path("regions"))((ObjectNode)region.path("tables").path(0)).put("keywordLineNumber",satHeader);
        assertTrue(EclipseMultiPvtParameters.validContext(i.path("multiPvtContext"),i));
        assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper));
    }
}
