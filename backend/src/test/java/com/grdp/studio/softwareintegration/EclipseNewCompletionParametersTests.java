package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseNewCompletionParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EclipseNewCompletionParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private final String request="""
        {"schemaVersion":"eclipse-new-completion-parameters/1","baselineRunId":1,"sourceFile":"ECLIPSE_NEW_COMPLETION_WATER.DATA",
        "sourceSha256":"dbae4c8a9f20f0a9c521f77d63fae3bbbdd3ec22cb8ab962897db5cb05a028c2","gridFile":"CASE.EGRID","gridArtifactSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "nx":15,"ny":15,"nz":1,"insertionLine":179,"well":"P","i":2,"j":1,"k":1,"diameter":0.2,"kh":1000,"skin":0,"lengthUnit":"m"}
        """;
    @Test void actualWorkerInspectionSupportsExistingProducerAndPassesUploadBoundary() throws Exception {
        var inspection=mapper.readTree(getClass().getResourceAsStream("/eclipse-new-completion-inspection.json"));
        assertTrue(EclipseNewCompletionParameters.valid(mapper.readTree(request)));
        assertTrue(EclipseNewCompletionParameters.matchesInspection(mapper.readTree(request),inspection));
        String stored=EclipseDataInspectionValidator.validateAndSerialize(inspection,mapper);
        assertEquals(inspection.toString(),EclipseDataInspectionValidator.parsePersisted(stored).toString());
    }
    @Test void InvalidNumericTypesFieldsUnitsAndBoundsAreRejected() throws Exception {
        for(String bad:List.of(request.replace("\"i\":2","\"i\":\"2\""),request.replace("\"i\":2","\"i\":16"),request.replace("\"i\":2","\"i\":2.5"),
                request.replace("\"diameter\":0.2","\"diameter\":0"),request.replace("\"skin\":0","\"skin\":-1"),request.replace("\"kh\":1000","\"kh\":0"),
                request.replace("\"lengthUnit\":\"m\"","\"lengthUnit\":\"BARSA\""),request.trim().substring(0,request.trim().length()-1)+",\"extra\":1}"))
            assertFalse(EclipseNewCompletionParameters.valid(mapper.readTree(bad)),bad);
    }
    @Test void SourceUnknownOrInjectionWellOccupiedCellTimingAndCapacityFail() throws Exception {
        var inspection=mapper.readTree(getClass().getResourceAsStream("/eclipse-new-completion-inspection.json"));
        for(String bad:List.of(request.replace("\"well\":\"P\"","\"well\":\"P2\""),request.replace("\"well\":\"P\"","\"well\":\"I\""),
                request.replace("\"i\":2","\"i\":1"),request.replace("\"insertionLine\":179","\"insertionLine\":180"),request.replace("dbae4c8a","00000000"),request.replace("\"lengthUnit\":\"m\"","\"lengthUnit\":\"ft\"")))
            assertFalse(EclipseNewCompletionParameters.matchesInspection(mapper.readTree(bad),inspection),bad);
        var limited=(ObjectNode)inspection.deepCopy();((ObjectNode)limited.path("newWellContext")).put("maxConnections",1);
        assertFalse(EclipseNewCompletionParameters.matchesInspection(mapper.readTree(request),limited));
        var shut=(ObjectNode)inspection.deepCopy();
        for(var row:shut.path("scheduleMetadata").path("records"))if("WCONPROD".equals(row.path("keyword").asText()))((tools.jackson.databind.node.ArrayNode)row.path("values")).set(1,mapper.getNodeFactory().stringNode("SHUT"));
        assertFalse(EclipseNewCompletionParameters.matchesInspection(mapper.readTree(request),shut));
    }
}
