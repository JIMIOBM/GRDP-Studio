package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseBatchParameters;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class EclipseBatchParametersTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode request()throws Exception{
        var inspection=mapper.readTree(getClass().getResourceAsStream("/eclipse-pvdo-inspection.json"));var c=inspection.path("oilPvtContext");var request=mapper.createObjectNode();request.put("requestKey",UUID.randomUUID().toString());request.put("name","油黏度比较");request.put("baselineRunId",1);var cases=request.putArray("cases");
        for(int i=2;i<=3;i++){var item=cases.addObject();item.put("name","油黏度×"+i);var p=item.putObject("parameters");p.put("schemaVersion","eclipse-pvdo-parameters/1");p.put("baselineRunId",1);p.put("sourceFile",c.path("sourceFile").asText());p.put("sourceSha256",inspection.path("packageFiles").path(0).path("sha256").asText());p.set("keywordLineNumber",c.path("keywordLineNumber").deepCopy());p.put("unit","cP");p.put("multiplier",i);p.set("rows",c.path("rows").deepCopy());}return request;
    }
    @Test void TwoIndependentActualSourceScenariosBindOneBaseline()throws Exception{assertTrue(EclipseBatchParameters.valid(request()));}
    @Test void DuplicateNamesDuplicateCasesAndMismatchedBaselineReject()throws Exception{
        var p=request();((ObjectNode)p.path("cases").path(1)).put("name",p.path("cases").path(0).path("name").asText());assertFalse(EclipseBatchParameters.valid(p));
        p=request();((ObjectNode)p.path("cases").path(1)).set("parameters",p.path("cases").path(0).path("parameters").deepCopy());assertFalse(EclipseBatchParameters.valid(p));
        p=request();((ObjectNode)p.path("cases").path(1).path("parameters")).put("baselineRunId",2);assertFalse(EclipseBatchParameters.valid(p));
    }
    @Test void UnknownOuterOrMemberFieldsMalformedSubmissionKeyAndTypedBaselineReject()throws Exception{
        var p=request();p.put("extra",1);assertFalse(EclipseBatchParameters.valid(p));p=request();((ObjectNode)p.path("cases").path(1)).put("extra",1);assertFalse(EclipseBatchParameters.valid(p));
        p=request();p.put("requestKey","../batch");assertFalse(EclipseBatchParameters.valid(p));p=request();p.put("baselineRunId","1");assertFalse(EclipseBatchParameters.valid(p));p=request();p.put("name","\n油黏度");assertFalse(EclipseBatchParameters.valid(p));
    }
    @Test void NumericallyIdenticalJsonRequestsAndCasesStayIdenticalAcrossPersistence()throws Exception {
        var p=request();var reread=mapper.readTree(mapper.writeValueAsString(p));assertTrue(EclipseBatchParameters.sameContent(p,reread));
        var second=(ObjectNode)p.path("cases").path(1).path("parameters");second.put("multiplier",2.0);assertFalse(EclipseBatchParameters.valid(p));
        second.put("multiplier",3.0);assertTrue(EclipseBatchParameters.valid(p));
    }
    @Test void UnsupportedOrNonphysicalChildAndSingleCaseReject()throws Exception{
        var p=request();((ObjectNode)p.path("cases").path(1).path("parameters")).put("multiplier",1);assertFalse(EclipseBatchParameters.valid(p));
        p=request();((ObjectNode)p.path("cases").path(1).path("parameters")).put("schemaVersion","shell-command/1");assertFalse(EclipseBatchParameters.valid(p));
        p=request();((tools.jackson.databind.node.ArrayNode)p.path("cases")).remove(1);assertFalse(EclipseBatchParameters.valid(p));
    }
}
