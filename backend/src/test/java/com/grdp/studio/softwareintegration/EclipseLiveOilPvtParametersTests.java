package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseLiveOilPvtParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class EclipseLiveOilPvtParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection()throws Exception{return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-pvto-inspection.json"));}
    private ObjectNode request(ObjectNode inspection){
        var c=inspection.path("liveOilPvtContext");var p=mapper.createObjectNode();p.put("schemaVersion",EclipseLiveOilPvtParameters.SCHEMA);p.put("baselineRunId",1);
        p.put("sourceFile",c.path("sourceFile").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.put("keywordLineNumber",c.path("keywordLineNumber").asInt());p.put("unit","cP");p.put("multiplier",2);p.put("field","VISCOSITY");p.set("records",c.path("records").deepCopy());return p;
    }
    @Test void ActualWorkerBranchesPersistAndBindEveryInspectedRow()throws Exception{
        var inspection=inspection();var p=request(inspection);assertTrue(EclipseLiveOilPvtParameters.valid(p));assertTrue(EclipseLiveOilPvtParameters.matchesInspection(p,inspection));
        assertEquals(9,p.path("records").size());assertEquals(2,p.path("records").path(8).path("rows").size());
        var saved=EclipseDataInspectionValidator.validateAndSerialize(inspection,mapper);assertEquals(inspection.toString(),EclipseDataInspectionValidator.parsePersisted(saved).toString());
        var wrong=p.deepCopy();wrong.put("sourceSha256","0".repeat(64));assertFalse(EclipseLiveOilPvtParameters.matchesInspection(wrong,inspection));
        wrong=p.deepCopy();((ArrayNode)wrong.path("records").path(0).path("rows").path(0).path("values")).set(3,mapper.getNodeFactory().stringNode("1.05"));assertTrue(EclipseLiveOilPvtParameters.valid(wrong));assertFalse(EclipseLiveOilPvtParameters.matchesInspection(wrong,inspection));
    }
    @Test void MissingHighestRsUndersaturationDefaultsOrderingUnknownFieldsAndTypedNumbersReject()throws Exception{
        var p=request(inspection());
        for(String bad:List.of(p.toString().replace("\"multiplier\":2","\"multiplier\":\"2\""),p.toString().replace("\"multiplier\":2","\"multiplier\":1"),p.toString().replace("\"cP\"","\"Pa.s\""),p.toString().replace("\"1.726\"","\"1.9\""),p.toString().replace("\"0.605\"","\"1*\""),p.toString().replace("\"0.0905\"","\"0.001\"")))assertFalse(EclipseLiveOilPvtParameters.valid(mapper.readTree(bad)));
        var missing=p.deepCopy();((ArrayNode)missing.path("records").path(8).path("rows")).remove(1);assertFalse(EclipseLiveOilPvtParameters.valid(missing));
        var extra=p.deepCopy();((ObjectNode)extra.path("records").path(0)).put("extra",1);assertFalse(EclipseLiveOilPvtParameters.valid(extra));
        extra=p.deepCopy();extra.put("extra",1);assertFalse(EclipseLiveOilPvtParameters.valid(extra));
    }
    @Test void BothColumnsBindSourceUnitsAndHaveSeparateTargetBounds()throws Exception{
        var i=inspection();var p=request(i);p.put("field","FORMATION_VOLUME_FACTOR");p.put("unit","rb/stb");p.put("multiplier",1.02);assertTrue(EclipseLiveOilPvtParameters.matchesInspection(p,i));
        for(String system:List.of("METRIC","PVT-M","LAB")){var other=i.deepCopy();other.put("unitSystem",system);assertFalse(EclipseLiveOilPvtParameters.matchesInspection(p,other));var q=p.deepCopy();q.put("unit",system.equals("LAB")?"rcc/scc":"rm3/sm3");assertTrue(EclipseLiveOilPvtParameters.matchesInspection(q,other));}
        p.put("multiplier",8);assertFalse(EclipseLiveOilPvtParameters.valid(p));p=request(i);p.put("multiplier",10000);assertFalse(EclipseLiveOilPvtParameters.valid(p));
    }
    @Test void InvalidOptionalContextCannotPersistAndLegacyInspectionIsUnchanged()throws Exception{
        var i=inspection();for(String key:List.of("keywordLineNumber","records","schemaVersion")){var bad=i.deepCopy();((ObjectNode)bad.path("liveOilPvtContext")).putNull(key);assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(bad,mapper));}
        var bad=i.deepCopy();((ArrayNode)bad.path("liveOilPvtContext").path("records").path(8).path("rows")).remove(1);assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(bad,mapper));
        i.remove("liveOilPvtContext");assertNotNull(EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper)));
    }
}
