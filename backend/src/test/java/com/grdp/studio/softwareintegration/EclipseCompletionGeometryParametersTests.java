package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseCompletionGeometryParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EclipseCompletionGeometryParametersTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private String request(String field,String unit,int original,double target) {
        return """
                {"schemaVersion":"eclipse-completion-geometry-parameters/1","baselineRunId":1,"well":"P       ",
                 "sourceFile":"ECLIPSE_COMPLETION_GEOMETRY.DATA","lineNumber":157,"i":"1","j":"1","k1":"1","k2":"1",
                 "field":"%s","unit":"%s","originalValue":%d,"targetValue":%s,
                 "recordValues":["P       ","1","1","1","1","OPEN","0",".0","1.0","1000","0"]}
                """.formatted(field,unit,original,target);
    }
    @Test void validDiameterKhAndSkinReferenceCompleteOriginalRecord() throws Exception {
        var stream=getClass().getResourceAsStream("/eclipse-completion-geometry-inspection.json"); assertNotNull(stream);
        var inspection=mapper.readTree(stream);
        for(String json:List.of(request("DIAMETER","m",1,0.2),request("KH","mD.m",1000,500),request("SKIN","1",0,2))) {
            var p=mapper.readTree(json); assertTrue(EclipseCompletionGeometryParameters.valid(p));
            assertTrue(EclipseCompletionGeometryParameters.matchesInspection(p,inspection));
        }
    }
    @Test void invalidValuesPositiveCfUnknownFieldsAndUnitCannotQueue() throws Exception {
        String json=request("DIAMETER","m",1,0.2);
        for(String bad:List.of(json.replace("\"targetValue\":0.2","\"targetValue\":\"0.2\""),json.replace("\".0\"","\"3.028\""),
                json.replace("\"originalValue\":1","\"originalValue\":2"),json.replace("\"unit\":\"m\"","\"unit\":\"mD\""),
                request("DIAMETER","m",1,3),request("SKIN","1",0,-1),request("SKIN","1",0,101),request("KH","mD.m",1000,1000)))
            assertFalse(EclipseCompletionGeometryParameters.valid(mapper.readTree(bad)));
    }
    @Test void wrongCoordinatesSourceUnitOrPreservedRecordRejectBeforeRun() throws Exception {
        var inspection=mapper.readTree(getClass().getResourceAsStream("/eclipse-completion-geometry-inspection.json"));
        String json=request("DIAMETER","m",1,0.2);
        for(String bad:List.of(json.replace("\"lineNumber\":157","\"lineNumber\":158"),json.replace("\"unit\":\"m\"","\"unit\":\"ft\""),
                json.replace("\"1000\"","\"500\"")))
            assertFalse(EclipseCompletionGeometryParameters.matchesInspection(mapper.readTree(bad),inspection));
    }
}
