package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseGasPvtParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class EclipseGasPvtParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection()throws Exception{return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-pvdg-inspection.json"));}
    private ObjectNode request(ObjectNode inspection){
        var c=inspection.path("gasPvtContext");var p=mapper.createObjectNode();p.put("schemaVersion",EclipseGasPvtParameters.SCHEMA);p.put("baselineRunId",1);
        p.put("sourceFile",c.path("sourceFile").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.put("keywordLineNumber",c.path("keywordLineNumber").asInt());p.put("unit","cP");p.put("multiplier",2);p.set("rows",c.path("rows").deepCopy());return p;
    }
    @Test void ActualWorkerTableSurvivesPersistenceAndBindsEveryOriginalRow()throws Exception{
        var inspection=inspection();var p=request(inspection);assertTrue(EclipseGasPvtParameters.valid(p));assertTrue(EclipseGasPvtParameters.matchesInspection(p,inspection));
        var saved=EclipseDataInspectionValidator.validateAndSerialize(inspection,mapper);assertEquals(inspection.toString(),EclipseDataInspectionValidator.parsePersisted(saved).toString());
        var wrong=p.deepCopy();wrong.put("keywordLineNumber",83);assertFalse(EclipseGasPvtParameters.matchesInspection(wrong,inspection));wrong=p.deepCopy();wrong.put("sourceSha256","0".repeat(64));assertFalse(EclipseGasPvtParameters.matchesInspection(wrong,inspection));
        wrong=p.deepCopy();((ObjectNode)wrong.path("rows").path(1)).put("lineNumber",85);assertTrue(EclipseGasPvtParameters.valid(wrong));assertFalse(EclipseGasPvtParameters.matchesInspection(wrong,inspection));
    }
    @Test void TypedNumbersUnitsBoundsUnknownFieldsAndMonotonicColumnsReject()throws Exception{
        var p=request(inspection());
        for(String bad:List.of(p.toString().replace("\"multiplier\":2","\"multiplier\":\"2\""),p.toString().replace("\"multiplier\":2","\"multiplier\":1"),p.toString().replace("\"cP\"","\"Pa.s\""),p.toString().replace("\".92\"","\"1.3\""),p.toString().replace("\"2.0\"","\"1*\"")))assertFalse(EclipseGasPvtParameters.valid(mapper.readTree(bad)));
        var extra=p.deepCopy();extra.put("extra",1);assertFalse(EclipseGasPvtParameters.valid(extra));
        extra=p.deepCopy();((ObjectNode)extra.path("rows").path(0)).put("extra",1);assertFalse(EclipseGasPvtParameters.valid(extra));
        extra=p.deepCopy();extra.put("multiplier",0);assertFalse(EclipseGasPvtParameters.valid(extra));
    }
    @Test void InvalidOptionalContextCannotBeStoredAndLegacyInspectionRetainsCompatibility()throws Exception{
        var inspection=inspection();
        for(String key:List.of("keywordLineNumber","rows","schemaVersion")){var bad=inspection.deepCopy();((ObjectNode)bad.path("gasPvtContext")).putNull(key);assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(bad,mapper));}
        var bad=inspection.deepCopy();((ObjectNode)bad.path("gasPvtContext")).put("extra",1);assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(bad,mapper));
        inspection.remove("gasPvtContext");assertNotNull(EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(inspection,mapper)));
    }
}
