package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseRelpermParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class EclipseRelpermParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection()throws Exception{return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-relperm-inspection.json"));}
    private ObjectNode request(ObjectNode inspection,String keyword){
        var c=inspection.path("relpermContext");var p=mapper.createObjectNode();p.put("schemaVersion",EclipseRelpermParameters.SCHEMA);p.put("baselineRunId",1);p.put("sourceFile",c.path("sourceFile").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.put("keyword",keyword);p.put("unit","1");p.put("multiplier",.5);
        for(var t:c.path("tables"))if(keyword.equals(t.path("keyword").asText())){p.set("keywordLineNumber",t.path("keywordLineNumber").deepCopy());p.set("rows",t.path("rows").deepCopy());}return p;
    }
    @Test void ActualWorkerThreeTablesPersistAndBothSupportedScenariosBindCompleteSelectedRows()throws Exception{
        var inspection=inspection();var saved=EclipseDataInspectionValidator.validateAndSerialize(inspection,mapper);assertEquals(inspection.toString(),EclipseDataInspectionValidator.parsePersisted(saved).toString());
        for(String keyword:List.of("SWFN","SOF3")){
            var p=request(inspection,keyword);assertTrue(EclipseRelpermParameters.valid(p));assertTrue(EclipseRelpermParameters.matchesInspection(p,inspection));
            var bad=p.deepCopy();bad.put("sourceSha256","0".repeat(64));assertFalse(EclipseRelpermParameters.matchesInspection(bad,inspection));
            bad=p.deepCopy();var last=(ObjectNode)bad.path("rows").path(bad.path("rows").size()-1);last.put("lineNumber",last.path("lineNumber").asInt()+1);assertTrue(EclipseRelpermParameters.valid(bad));assertFalse(EclipseRelpermParameters.matchesInspection(bad,inspection));
        }
    }
    @Test void TypedUnitsBoundsUnsupportedGasAndExtraFieldsRejectBeforeRunCreation()throws Exception{
        for(String keyword:List.of("SWFN","SOF3")){
            var p=request(inspection(),keyword);
            for(String bad:List.of(p.toString().replace("\"multiplier\":0.5","\"multiplier\":\"0.5\""),p.toString().replace("\"multiplier\":0.5","\"multiplier\":1"),p.toString().replace("\"multiplier\":0.5","\"multiplier\":2"),p.toString().replace("\"unit\":\"1\"","\"unit\":\"mD\""),p.toString().replace("\"keyword\":\""+keyword+"\"","\"keyword\":\"SGFN\"")))assertFalse(EclipseRelpermParameters.valid(mapper.readTree(bad)));
            var extra=p.deepCopy();extra.put("extra",1);assertFalse(EclipseRelpermParameters.valid(extra));extra=p.deepCopy();((ObjectNode)extra.path("rows").path(0)).put("extra",1);assertFalse(EclipseRelpermParameters.valid(extra));
        }
    }
    @Test void OilEndpointsConnateWaterAndCapillaryMonotonicityCannotBeFalsified()throws Exception{
        var good=inspection();
        for(int column:List.of(0,2)){
            var bad=good.deepCopy();var rows=bad.path("relpermContext").path("tables").path(2).path("rows");((tools.jackson.databind.node.ArrayNode)rows.path(rows.size()-1).path("values")).set(column,mapper.getNodeFactory().stringNode(column==0?"0.79":"0.8"));assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(bad,mapper));
        }
        var bad=good.deepCopy();((tools.jackson.databind.node.ArrayNode)bad.path("relpermContext").path("tables").path(0).path("rows").path(1).path("values")).set(2,mapper.getNodeFactory().stringNode("7"));assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(bad,mapper));
    }
    @Test void MissingOrUnknownContextRejectsWhileLegacyInspectionStillPersists()throws Exception{
        var inspection=inspection();for(String key:List.of("schemaVersion","sourceFile","tables")){var bad=inspection.deepCopy();((ObjectNode)bad.path("relpermContext")).putNull(key);assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(bad,mapper));}
        var extra=inspection.deepCopy();((ObjectNode)extra.path("relpermContext")).put("extra",1);assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(extra,mapper));
        inspection.remove("relpermContext");assertNotNull(EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(inspection,mapper)));
    }
}
