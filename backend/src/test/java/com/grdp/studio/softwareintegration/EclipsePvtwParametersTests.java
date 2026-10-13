package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipsePvtwParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class EclipsePvtwParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private final String request="""
        {"schemaVersion":"eclipse-pvtw-parameters/1","baselineRunId":1,"sourceFile":"CASE.DATA","sourceSha256":"dbae4c8a9f20f0a9c521f77d63fae3bbbdd3ec22cb8ab962897db5cb05a028c2",
        "lineNumber":77,"field":"VISCOSITY","unit":"cP","originalValue":0.5,"targetValue":1,"recordValues":[".0","1.0","3.03E-06",".5","0.0"]}
        """;
    @Test void ActualWorkerFixturePassesPersistenceBoundaryAndFullRecordBinding() throws Exception {
        var inspection=mapper.readTree(getClass().getResourceAsStream("/eclipse-pvtw-inspection.json"));
        assertTrue(EclipsePvtwParameters.valid(mapper.readTree(request)));assertTrue(EclipsePvtwParameters.matchesInspection(mapper.readTree(request),inspection));
        var saved=EclipseDataInspectionValidator.validateAndSerialize(inspection,mapper);assertEquals(inspection.toString(),EclipseDataInspectionValidator.parsePersisted(saved).toString());
    }
    @Test void TypesUnitsUnknownFieldSourceAndStaleRecordFail() throws Exception {
        var inspection=mapper.readTree(getClass().getResourceAsStream("/eclipse-pvtw-inspection.json"));
        for(String bad:List.of(request.replace("\"targetValue\":1","\"targetValue\":\"1\""),request.replace("\"targetValue\":1","\"targetValue\":0"),request.replace("\"unit\":\"cP\"","\"unit\":\"Pa.s\""),request.replace("\".5\"","\"1*\""),request.replace("\"lineNumber\":77","\"lineNumber\":78"),request.replace("dbae4c8a","00000000"),request.replace("\"1.0\"","\"2.0\""),request.trim().substring(0,request.trim().length()-1)+",\"extra\":1}"))assertFalse(EclipsePvtwParameters.matchesInspection(mapper.readTree(bad),inspection));
    }
    @Test void MalformedOptionalContextCannotBeStoredAndLegacyStillWorks() throws Exception {
        var inspection=(ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-pvtw-inspection.json"));
        for(String key:List.of("lineNumber","recordValues","schemaVersion")){var bad=inspection.deepCopy();((ObjectNode)bad.path("pvtwContext")).putNull(key);assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(bad,mapper));}
        var bad=inspection.deepCopy();((ObjectNode)bad.path("pvtwContext")).put("extra",1);assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(bad,mapper));
        inspection.remove("pvtwContext");assertNotNull(EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(inspection,mapper)));
    }

    @Test void FiveFieldsRetainFullOriginalRecordAndLegacyContractIsNotBroadened() throws Exception {
        var inspection=mapper.readTree(getClass().getResourceAsStream("/eclipse-pvtw-inspection.json"));
        String[] fields={"REFERENCE_PRESSURE","FORMATION_VOLUME_FACTOR","COMPRESSIBILITY","VISCOSITY","VISCOSIBILITY"};double[] targets={4000,1.02,.00000606,1,.000001};
        for(int i=0;i<fields.length;i++) {
            var v=(ObjectNode)mapper.readTree(request);v.put("schemaVersion",EclipsePvtwParameters.SCHEMA2);v.put("field",fields[i]);v.put("unit",EclipsePvtwParameters.unitFor("METRIC",fields[i]));v.put("originalValue",Double.parseDouble(v.path("recordValues").path(i).asText()));v.put("targetValue",targets[i]);
            assertTrue(EclipsePvtwParameters.valid(v));assertTrue(EclipsePvtwParameters.matchesInspection(v,inspection));
            v.put("schemaVersion",EclipsePvtwParameters.SCHEMA);assertEquals(i==3,EclipsePvtwParameters.valid(v));v.put("schemaVersion",EclipsePvtwParameters.SCHEMA2);v.put("unit","Pa.s");assertFalse(EclipsePvtwParameters.matchesInspection(v,inspection));
        }
    }
    @Test void OilAndGasVolumeUnitsMustNotBeUsedForWater() {
        assertEquals("rb/stb",EclipsePvtwParameters.unitFor("FIELD","FORMATION_VOLUME_FACTOR"));assertEquals("rm3/sm3",EclipsePvtwParameters.unitFor("PVT-M","FORMATION_VOLUME_FACTOR"));assertEquals("rcc/scc",EclipsePvtwParameters.unitFor("LAB","FORMATION_VOLUME_FACTOR"));assertEquals("1/psi",EclipsePvtwParameters.unitFor("FIELD","VISCOSIBILITY"));
        assertFalse(EclipsePvtwParameters.scalar("FORMATION_VOLUME_FACTOR","rb/stb",0));assertFalse(EclipsePvtwParameters.scalar("COMPRESSIBILITY","1/bar",.01));
    }
}
