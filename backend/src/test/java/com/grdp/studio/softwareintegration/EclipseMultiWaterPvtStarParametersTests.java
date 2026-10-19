package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EclipseMultiWaterPvtStarParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection(boolean pure)throws Exception{return (ObjectNode)mapper.readTree(getClass().getResourceAsStream(pure?"/eclipse-multi-pure-water-star-inspection.json":"/eclipse-multi-water-star-inspection.json"));}
    private ObjectNode request(JsonNode i,String field){var r=i.path("multiWaterPvtContext").path("regions").path(1);int k=java.util.List.of("REFERENCE_PRESSURE","FORMATION_VOLUME_FACTOR","COMPRESSIBILITY","VISCOSITY","VISCOSIBILITY").indexOf(field);var p=mapper.createObjectNode();p.put("schemaVersion",EclipseMultiWaterPvtParameters.SCHEMA3);p.put("baselineRunId",1);p.put("regionNumber",2);p.put("sourceFile",i.path("caseName").asText());p.put("sourceSha256",i.path("packageFiles").path(0).path("sha256").asText());p.put("lineNumber",r.path("lineNumber").asInt());p.put("field",field);p.put("unit",EclipsePvtwParameters.unitFor(i.path("unitSystem").asText(),field));double original=k==4?0:Double.parseDouble(r.path("recordValues").path(k).asText().replace('D','E'));p.put("originalValue",original);p.put("targetValue",k==4?1e-6:k==0?4000:original*2);p.set("recordValues",r.path("recordValues").deepCopy());return p;}
    @Test void bothModelKindsBindAllFiveFieldsAndRawStarsThroughPersistence()throws Exception{
        for(boolean pure:new boolean[]{false,true}){var i=inspection(pure);assertTrue(EclipseMultiWaterPvtParameters.validContext(i.path("multiWaterPvtContext"),i));var saved=EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));assertEquals(mapper.readTree(i.toString()),mapper.readTree(saved.toString()));for(String f:new String[]{"REFERENCE_PRESSURE","FORMATION_VOLUME_FACTOR","COMPRESSIBILITY","VISCOSITY","VISCOSIBILITY"}){var p=request(i,f);assertTrue(EclipseBatchParameters.scenario(p));assertTrue(EclipseMultiWaterPvtParameters.matchesInspection(p,i));assertEquals("1*",p.path("recordValues").path(4).asText());}}
    }
    @Test void olderVersionsAndNumericalZeroSubstitutionCannotBindStarSource()throws Exception{
        for(boolean pure:new boolean[]{false,true}){var i=inspection(pure);var p=request(i,"VISCOSIBILITY");for(String schema:new String[]{EclipseMultiWaterPvtParameters.SCHEMA,EclipseMultiWaterPvtParameters.SCHEMA2}){p.put("schemaVersion",schema);assertFalse(EclipseMultiWaterPvtParameters.valid(p));}p.put("schemaVersion",EclipseMultiWaterPvtParameters.SCHEMA3);((ArrayNode)p.path("recordValues")).remove(4);((ArrayNode)p.path("recordValues")).add("0");assertTrue(EclipseMultiWaterPvtParameters.valid(p));assertFalse(EclipseMultiWaterPvtParameters.matchesInspection(p,i));}
    }
    @Test void earlierDefaultColumnsAndRepeatDefaultsAreRejected()throws Exception{
        for(boolean pure:new boolean[]{false,true})for(int column=0;column<5;column++){var i=inspection(pure);for(var r:i.path("multiWaterPvtContext").path("regions")){var row=(ArrayNode)r.path("recordValues");var copy=mapper.createArrayNode();for(int k=0;k<5;k++)copy.add(k==column?(column==4?"2*":"1*"):row.path(k).asText());((ObjectNode)r).set("recordValues",copy);}assertFalse(EclipseMultiWaterPvtParameters.validContext(i.path("multiWaterPvtContext"),i));}
    }
    @Test void mixedOmittedAndExplicitStarRecordsUseVersionThreeOnlyWhileAnyStarRemains()throws Exception{
        for(boolean pure:new boolean[]{false,true}){var i=inspection(pure);((ArrayNode)i.path("multiWaterPvtContext").path("regions").path(0).path("recordValues")).remove(4);assertTrue(EclipseMultiWaterPvtParameters.validContext(i.path("multiWaterPvtContext"),i));((ArrayNode)i.path("multiWaterPvtContext").path("regions").path(1).path("recordValues")).remove(4);assertFalse(EclipseMultiWaterPvtParameters.validContext(i.path("multiWaterPvtContext"),i));((ObjectNode)i.path("multiWaterPvtContext")).put("schemaVersion","eclipse-multi-water-pvt-context/2");assertTrue(EclipseMultiWaterPvtParameters.validContext(i.path("multiWaterPvtContext"),i));}
    }
}
