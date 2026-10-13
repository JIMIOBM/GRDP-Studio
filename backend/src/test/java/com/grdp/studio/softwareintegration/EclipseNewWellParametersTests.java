package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseNewWellParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class EclipseNewWellParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private final String request="""
        {"schemaVersion":"eclipse-new-well-parameters/1","baselineRunId":1,"sourceFile":"ECLIPSE_NEW_WELL_WATER.DATA",
        "sourceSha256":"dbae4c8a9f20f0a9c521f77d63fae3bbbdd3ec22cb8ab962897db5cb05a028c2","gridFile":"CASE.EGRID","gridArtifactSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "nx":15,"ny":15,"nz":1,"insertionLine":179,"well":"P2","group":"G","i":15,"j":1,"k":1,"referenceDepth":4000,"lengthUnit":"m",
        "diameter":0.2,"kh":1000,"skin":0,"waterRate":100,"rateUnit":"SM3/DAY","bhpLimit":3000,"pressureUnit":"BARSA"}
        """;
    @Test void sourceContextAndPhysicalUnitsBindInitialWaterProducer() throws Exception {
        var inspection=mapper.readTree(getClass().getResourceAsStream("/eclipse-new-well-inspection.json"));var p=mapper.readTree(request);
        assertTrue(EclipseNewWellParameters.valid(p));assertTrue(EclipseNewWellParameters.matchesInspection(p,inspection));
    }
    @Test void invalidParametersCannotBecomeScenario() throws Exception {
        for(String bad:List.of(request.replace("\"waterRate\":100","\"waterRate\":\"100\""),request.replace("\"skin\":0","\"skin\":-1"),
            request.replace("\"i\":15","\"i\":16"),request.replace("\"group\":\"G\"","\"group\":\"FIELD\""),request.replace("BARSA","PSIA"),request.replace("\"diameter\":0.2","\"diameter\":3")))
            assertFalse(EclipseNewWellParameters.valid(mapper.readTree(bad)));
    }
    @Test void existingWellOccupiedCellWrongSourceGroupAndCapacityReject() throws Exception {
        var inspection=mapper.readTree(getClass().getResourceAsStream("/eclipse-new-well-inspection.json"));
        for(String bad:List.of(request.replace("\"well\":\"P2\"","\"well\":\"P\""),request.replace("\"i\":15","\"i\":1"),request.replace("\"group\":\"G\"","\"group\":\"OTHER\""),
                request.replace("\"insertionLine\":179","\"insertionLine\":180"),request.replace("dbae4c8a","00000000")))
            assertFalse(EclipseNewWellParameters.matchesInspection(mapper.readTree(bad),inspection));
        var limited=mapper.readTree(inspection.toString().replace("\"maxWells\":3","\"maxWells\":2"));
        assertFalse(EclipseNewWellParameters.matchesInspection(mapper.readTree(request),limited));
    }
    @Test void actualWorkerInspectionPassesUploadAndPersistenceBoundary() throws Exception {
        var inspection=mapper.readTree(getClass().getResourceAsStream("/eclipse-new-well-inspection.json"));
        String stored=EclipseDataInspectionValidator.validateAndSerialize(inspection,mapper);
        // Package size is normalized to a long node; the persisted JSON must
        // retain every field/value even though the parsed node width differs.
        assertEquals(inspection.toString(),EclipseDataInspectionValidator.parsePersisted(stored).toString());
        var legacy=((ObjectNode)inspection).deepCopy();legacy.remove("newWellContext");
        assertEquals(legacy.toString(),EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(legacy,mapper)).toString());
    }
    @Test void malformedCapacitySourceTimingAndUnknownFieldsAreRejectedAtUpload() throws Exception {
        var inspection=(ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-new-well-inspection.json"));
        for(String field:List.of("insertionLine","maxWells","maxConnections","maxGroups","maxWellsPerGroup")) {
            for(var bad:List.of(mapper.readTree("0"),mapper.readTree("\"3\""),mapper.readTree("3.5"),mapper.readTree("2147483648"))) {
                var copy=inspection.deepCopy();((ObjectNode)copy.path("newWellContext")).set(field,bad);
                assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(copy,mapper),field+bad);
            }
        }
        for(String change:List.of("unknown","source","timing","schema","null","capacity","phase","record","root")) {
            var copy=inspection.deepCopy();var context=(ObjectNode)copy.path("newWellContext");
            switch(change) {
                case "unknown" -> context.put("extra",1);
                case "root" -> copy.put("extra",1);
                case "source" -> context.put("sourceFile","OTHER.DATA");
                case "timing" -> context.put("insertionLine",180);
                case "schema" -> context.put("schemaVersion","eclipse-new-well-context/2");
                case "null" -> copy.putNull("newWellContext");
                case "capacity" -> context.put("maxWells",1);
                case "phase" -> copy.putArray("phases").add("OIL").add("WATER");
                case "record" -> ((ObjectNode)copy.path("scheduleMetadata").path("wells").get(0)).put("lineNumber",180);
            }
            assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(copy,mapper),change);
            assertNull(EclipseDataInspectionValidator.parsePersisted(copy.toString()),change);
        }
    }
}
