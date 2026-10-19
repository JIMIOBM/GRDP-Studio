package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseRelpermParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EclipseSgfnParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection()throws Exception{return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-sgfn-inspection.json"));}
    private ObjectNode request(ObjectNode i){var t=i.path("relpermContext").path("tables").path(1);assertEquals("SGFN",t.path("keyword").asText());var p=mapper.createObjectNode();p.put("schemaVersion","eclipse-relperm-parameters/2");p.put("baselineRunId",1);p.put("sourceFile",i.path("caseName").asText());p.put("sourceSha256",i.path("packageFiles").path(0).path("sha256").asText());p.put("keyword","SGFN");p.set("keywordLineNumber",t.path("keywordLineNumber").deepCopy());p.put("unit","1");p.put("multiplier",.5);p.set("rows",t.path("rows").deepCopy());return p;}
    @Test void ActualGasInspectionBindsEveryOriginalRowAndLegacySchemaCannotEditGas()throws Exception{var i=inspection();var p=request(i);assertTrue(EclipseRelpermParameters.matchesInspection(p,i));assertEquals(13,p.path("rows").size());assertEquals(i.toString(),EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).toString());var old=p.deepCopy();old.put("schemaVersion",EclipseRelpermParameters.SCHEMA);assertFalse(EclipseRelpermParameters.valid(old));var stale=p.deepCopy();stale.put("sourceSha256","0".repeat(64));assertFalse(EclipseRelpermParameters.matchesInspection(stale,i));}
    @Test void GasSaturationRelativePermeabilityCapillaryPressureAndTypedBoundsReject()throws Exception{var p=request(inspection());for(String text:new String[]{p.toString().replace("\"multiplier\":0.5","\"multiplier\":2"),p.toString().replace("\"multiplier\":0.5","\"multiplier\":\"0.5\""),p.toString().replace("\"0.09\"","\"-1\""),p.toString().replace("\"0.75\"","\"1.1\"")})assertFalse(EclipseRelpermParameters.valid(mapper.readTree(text)));var wrong=p.deepCopy();((ArrayNode)wrong.path("rows").path(1).path("values")).set(2,mapper.getNodeFactory().stringNode(".1"));assertTrue(EclipseRelpermParameters.valid(wrong));assertFalse(EclipseRelpermParameters.matchesInspection(wrong,inspection()));}
}
