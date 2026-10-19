package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseWetGasPvtParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class EclipseWetGasPvtParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection()throws Exception{return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-pvtg-inspection.json"));}
    private ObjectNode request(ObjectNode inspection){
        var c=inspection.path("wetGasPvtContext");var p=mapper.createObjectNode();p.put("schemaVersion",EclipseWetGasPvtParameters.SCHEMA);p.put("baselineRunId",1);
        p.put("sourceFile",c.path("sourceFile").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.put("keywordLineNumber",c.path("keywordLineNumber").asInt());p.put("unit","cP");p.put("multiplier",2);p.put("field","VISCOSITY");p.set("records",c.path("records").deepCopy());return p;
    }
    @Test void ActualWorkerBranchesPersistAndBindEveryInspectedRow()throws Exception{
        var inspection=inspection();var p=request(inspection);assertTrue(EclipseWetGasPvtParameters.valid(p));assertTrue(EclipseWetGasPvtParameters.matchesInspection(p,inspection));
        assertEquals(13,p.path("records").size());assertEquals(3,p.path("records").path(12).path("rows").size());
        var saved=EclipseDataInspectionValidator.validateAndSerialize(inspection,mapper);assertEquals(inspection.toString(),EclipseDataInspectionValidator.parsePersisted(saved).toString());
        var wrong=p.deepCopy();wrong.put("sourceSha256","0".repeat(64));assertFalse(EclipseWetGasPvtParameters.matchesInspection(wrong,inspection));
        wrong=p.deepCopy();((ArrayNode)wrong.path("records").path(0).path("rows").path(0).path("values")).set(3,mapper.getNodeFactory().stringNode("0.014"));assertTrue(EclipseWetGasPvtParameters.valid(wrong));assertFalse(EclipseWetGasPvtParameters.matchesInspection(wrong,inspection));
    }
    @Test void MissingHighestPressureUndersaturationDefaultsOrderingUnknownFieldsAndTypedNumbersReject()throws Exception{
        var p=request(inspection());
        for(String bad:List.of(p.toString().replace("\"multiplier\":2","\"multiplier\":\"2\""),p.toString().replace("\"multiplier\":2","\"multiplier\":1"),p.toString().replace("\"cP\"","\"Pa.s\""),p.toString().replace("\"0.04234\"","\"-1\""),p.toString().replace("\"0.01344\"","\"1*\""),p.toString().replace("\"60\"","\"30\"")))assertFalse(EclipseWetGasPvtParameters.valid(mapper.readTree(bad)));
        var missing=p.deepCopy();((ArrayNode)missing.path("records").path(12).path("rows")).removeAll();assertFalse(EclipseWetGasPvtParameters.valid(missing));
        var extra=p.deepCopy();((ObjectNode)extra.path("records").path(0)).put("extra",1);assertFalse(EclipseWetGasPvtParameters.valid(extra));
        extra=p.deepCopy();extra.put("extra",1);assertFalse(EclipseWetGasPvtParameters.valid(extra));
    }
    @Test void BothColumnsBindSourceUnitsAndHaveSeparateTargetBounds()throws Exception{
        var i=inspection();var p=request(i);p.put("field","FORMATION_VOLUME_FACTOR");p.put("unit","rm3/sm3");p.put("multiplier",1.02);assertTrue(EclipseWetGasPvtParameters.matchesInspection(p,i));
        for(String system:List.of("FIELD","LAB")){var other=i.deepCopy();other.put("unitSystem",system);assertFalse(EclipseWetGasPvtParameters.matchesInspection(p,other));var q=p.deepCopy();q.put("unit",system.equals("LAB")?"rcc/scc":"rb/Mscf");assertTrue(EclipseWetGasPvtParameters.matchesInspection(q,other));}
        p.put("multiplier",101);assertFalse(EclipseWetGasPvtParameters.valid(p));p=request(i);p.put("multiplier",10000);assertFalse(EclipseWetGasPvtParameters.valid(p));
    }
    @Test void InvalidOptionalContextCannotPersistAndLegacyInspectionIsUnchanged()throws Exception{
        var i=inspection();for(String key:List.of("keywordLineNumber","records","schemaVersion")){var bad=i.deepCopy();((ObjectNode)bad.path("wetGasPvtContext")).putNull(key);assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(bad,mapper));}
        var bad=i.deepCopy();((ArrayNode)bad.path("wetGasPvtContext").path("records").path(12).path("rows")).removeAll();assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(bad,mapper));
        i.remove("wetGasPvtContext");assertNotNull(EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper)));
    }
}
