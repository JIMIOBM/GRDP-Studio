package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseOilPvtParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class EclipseOilPvtParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection()throws Exception{return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-pvdo-inspection.json"));}
    private ObjectNode request(ObjectNode inspection){
        var c=inspection.path("oilPvtContext");var p=mapper.createObjectNode();p.put("schemaVersion",EclipseOilPvtParameters.SCHEMA);p.put("baselineRunId",1);
        p.put("sourceFile",c.path("sourceFile").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.put("keywordLineNumber",c.path("keywordLineNumber").asInt());p.put("unit","cP");p.put("multiplier",2);p.set("rows",c.path("rows").deepCopy());return p;
    }
    @Test void ActualWorkerTableSurvivesPersistenceAndBindsEveryOriginalRow()throws Exception{
        var inspection=inspection();var p=request(inspection);assertTrue(EclipseOilPvtParameters.valid(p));assertTrue(EclipseOilPvtParameters.matchesInspection(p,inspection));
        var saved=EclipseDataInspectionValidator.validateAndSerialize(inspection,mapper);assertEquals(inspection.toString(),EclipseDataInspectionValidator.parsePersisted(saved).toString());
        var wrong=p.deepCopy();wrong.put("keywordLineNumber",181);assertFalse(EclipseOilPvtParameters.matchesInspection(wrong,inspection));wrong=p.deepCopy();wrong.put("sourceSha256","0".repeat(64));assertFalse(EclipseOilPvtParameters.matchesInspection(wrong,inspection));
        wrong=p.deepCopy();((ObjectNode)wrong.path("rows").path(0)).put("lineNumber",181);assertTrue(EclipseOilPvtParameters.valid(wrong));assertFalse(EclipseOilPvtParameters.matchesInspection(wrong,inspection));
    }
    @Test void TypedNumbersUnitsBoundsUnknownFieldsAndMonotonicColumnsReject()throws Exception{
        var p=request(inspection());
        for(String bad:List.of(p.toString().replace("\"multiplier\":2","\"multiplier\":\"2\""),p.toString().replace("\"multiplier\":2","\"multiplier\":1"),p.toString().replace("\"cP\"","\"Pa.s\""),p.toString().replace("\"1.256\"","\"1.3\""),p.toString().replace("\"0.5\"","\"1*\"")))assertFalse(EclipseOilPvtParameters.valid(mapper.readTree(bad)));
        var extra=p.deepCopy();extra.put("extra",1);assertFalse(EclipseOilPvtParameters.valid(extra));
        extra=p.deepCopy();((ObjectNode)extra.path("rows").path(0)).put("extra",1);assertFalse(EclipseOilPvtParameters.valid(extra));
        extra=p.deepCopy();extra.put("multiplier",0);assertFalse(EclipseOilPvtParameters.valid(extra));
    }
    @Test void InvalidOptionalContextCannotBeStoredAndLegacyInspectionRetainsCompatibility()throws Exception{
        var inspection=inspection();
        for(String key:List.of("keywordLineNumber","rows","schemaVersion")){var bad=inspection.deepCopy();((ObjectNode)bad.path("oilPvtContext")).putNull(key);assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(bad,mapper));}
        var bad=inspection.deepCopy();((ObjectNode)bad.path("oilPvtContext")).put("extra",1);assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(bad,mapper));
        inspection.remove("oilPvtContext");assertNotNull(EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(inspection,mapper)));
    }
    @Test void VolumeFactorBindsSourceUnitsAndAllRowsWithSeparateTargetBounds()throws Exception{
        var inspection=inspection();var p=request(inspection);p.put("schemaVersion",EclipseOilPvtParameters.FIELD_SCHEMA);p.put("field","FORMATION_VOLUME_FACTOR");p.put("unit","rb/stb");p.put("multiplier",1.02);
        assertTrue(EclipseOilPvtParameters.valid(p));assertTrue(EclipseOilPvtParameters.matchesInspection(p,inspection));assertEquals(inspection.path("oilPvtContext").path("rows"),p.path("rows"));
        for(String system:List.of("METRIC","PVT-M","LAB")){var i=inspection.deepCopy();i.put("unitSystem",system);assertFalse(EclipseOilPvtParameters.matchesInspection(p,i));var q=p.deepCopy();q.put("unit",system.equals("LAB")?"rcc/scc":"rm3/sm3");assertTrue(EclipseOilPvtParameters.matchesInspection(q,i));}
        for(String fault:List.of("field","unit","multiplier","schemaVersion")){var bad=p.deepCopy();switch(fault){case "field"->bad.put(fault,2);case "unit"->bad.put(fault,"cP");case "multiplier"->bad.put(fault,8);default->bad.put(fault,EclipseOilPvtParameters.SCHEMA);}assertFalse(EclipseOilPvtParameters.valid(bad));}
        var missing=p.deepCopy();missing.remove("field");assertFalse(EclipseOilPvtParameters.valid(missing));var stale=p.deepCopy();((tools.jackson.databind.node.ArrayNode)stale.path("rows").path(0).path("values")).set(1,mapper.getNodeFactory().stringNode("1.27"));assertTrue(EclipseOilPvtParameters.valid(stale));assertFalse(EclipseOilPvtParameters.matchesInspection(stale,inspection));
        var viscosity=p.deepCopy();viscosity.put("field","VISCOSITY");viscosity.put("unit","cP");assertTrue(EclipseOilPvtParameters.matchesInspection(viscosity,inspection));
    }
}
