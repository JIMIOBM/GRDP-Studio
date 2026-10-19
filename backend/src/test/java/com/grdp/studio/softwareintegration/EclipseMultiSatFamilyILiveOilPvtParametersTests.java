package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EclipseMultiSatFamilyILiveOilPvtParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection()throws Exception{return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-multi-sat-family-i-pvto-inspection.json"));}
    private ObjectNode request(ObjectNode i,String field){boolean fluid=field.equals("VISCOSITY")||field.equals("FORMATION_VOLUME_FACTOR");var r=i.path(fluid?"multiLiveOilPvtContext":"multiSatRelpermContext").path("regions").path(1);var p=mapper.createObjectNode();p.put("schemaVersion",fluid?EclipseMultiLiveOilPvtParameters.SCHEMA:EclipseMultiSatRelpermParameters.SCHEMA);p.put("baselineRunId",1);p.put("regionNumber",2);p.put("sourceFile",i.path("caseName").asText());p.put("sourceSha256",i.path("packageFiles").path(0).path("sha256").asText());p.put("field",field);p.put("unit",fluid?(field.equals("VISCOSITY")?"cP":"rb/stb"):"1");p.put("multiplier",fluid?(field.equals("VISCOSITY")?2:1.02):.5);if(fluid){p.put("keywordLineNumber",r.path("keywordLineNumber").asInt());p.set("records",r.path("records").deepCopy());p.set("gasTable",r.path("gasTable").deepCopy());}else p.set("tables",r.path("tables").deepCopy());return p;}
    @Test void fiveEditsBindTheirOwnRegionTablesAndSurviveSerialization()throws Exception{
        var i=inspection();var saved=EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));assertEquals(mapper.readTree(i.toString()),mapper.readTree(saved.toString()));
        for(var field:new String[]{"VISCOSITY","FORMATION_VOLUME_FACTOR","WATER_KR","GAS_KR","OIL_KR"}){var p=request(i,field);boolean fluid=p.has("records");assertTrue(EclipseBatchParameters.scenario(p));assertTrue(fluid?EclipseMultiLiveOilPvtParameters.matchesInspection(p,i):EclipseMultiSatRelpermParameters.matchesInspection(p,i));p.put("regionNumber",1);assertFalse(fluid?EclipseMultiLiveOilPvtParameters.matchesInspection(p,i):EclipseMultiSatRelpermParameters.matchesInspection(p,i));}
    }
    @Test void individuallyValidAxesCannotShareOnePhysicalTableSourceLine()throws Exception{
        var i=inspection();int satHeader=i.path("multiSatRelpermContext").path("regions").path(0).path("tables").path(1).path("keywordLineNumber").asInt();
        for(var r:i.path("multiLiveOilPvtContext").path("regions"))((ObjectNode)r.path("gasTable")).put("keywordLineNumber",satHeader);
        assertTrue(EclipseMultiLiveOilPvtParameters.validContext(i.path("multiLiveOilPvtContext"),i));assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper));
    }
}
