package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseBatchParameters;
import com.grdp.studio.softwareintegration.support.EclipseCompositionalFluidPropertyParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
class EclipseCompositionalBatchParametersTests {
    private final JsonMapper mapper=new JsonMapper();
    private ObjectNode request(){
        var i=mapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-eos-inspection.json"));var n=mapper.createObjectNode().put("requestKey","10000000-0000-0000-0000-000000000001").put("name","组分物性敏感性").put("baselineRunId",1);var cases=n.putArray("cases");
        for(int j=0;j<4;j++){
            var c=j==0?i.path("compositionalAcentricFactorContext"):i.path("compositionalEosPropertyContexts").get(j-1);var row=c.path("records").get(2);
            var p=cases.addObject().put("name",row.path("field").asText()).putObject("parameters").put("schemaVersion",j==0?EclipseCompositionalFluidPropertyParameters.ACENTRIC_FACTOR_SCHEMA:EclipseCompositionalFluidPropertyParameters.EOS_PROPERTY_SCHEMA).put("baselineRunId",1).put("targetValue",new double[]{.03,17,.54,.078}[j]);
            for(var k:new String[]{"sourceFile","sourceSha256"})p.set(k,c.get(k));for(var k:new String[]{"componentIndex","componentName","field","lineNumber","unit","originalValue"})p.set(k,row.get(k));
        }return n;
    }
    @Test void fourIndependentPropertiesShareOneBaselineAndSignedAcentricValuesRemainValid(){var n=request();assertTrue(EclipseBatchParameters.valid(n));var acf=(ObjectNode)n.path("cases").get(0).path("parameters");acf.put("targetValue",0);assertTrue(EclipseBatchParameters.valid(n));acf.put("targetValue",-.01);assertTrue(EclipseBatchParameters.valid(n));}
    @Test void duplicateNumericBodiesForeignBaselinesAndInvalidPropertyValuesCannotEnterTheQueue(){
        for(var fault:new String[]{"name","duplicate","baseline","unit","zero","extra","string","limit"}){
            var n=request();var item=(ObjectNode)n.path("cases").get(1);var p=(ObjectNode)item.path("parameters");
            switch(fault){case "name"->item.put("name","ACF");case "duplicate"->item.set("parameters",n.path("cases").get(0).path("parameters").deepCopy());case "baseline"->p.put("baselineRunId",2);case "unit"->p.put("unit","dimensionless");case "zero"->p.put("targetValue",0);case "extra"->p.put("extra",true);case "string"->p.put("targetValue","17");default->n.withArray("cases").removeAll();}
            assertFalse(EclipseBatchParameters.valid(n),fault);
        }
        var n=request();var a=(ObjectNode)n.path("cases").get(1).path("parameters");var copy=a.deepCopy().put("targetValue",17.0);((ObjectNode)n.withArray("cases").get(0)).set("parameters",copy);assertFalse(EclipseBatchParameters.valid(n));
    }
    @Test void mixedBlackOilAndCompositionalFamiliesCannotShareOneBatch(){
        var n=request();var i=mapper.readTree(getClass().getResourceAsStream("/eclipse-pvdo-inspection.json"));var c=i.path("oilPvtContext");var p=mapper.createObjectNode().put("schemaVersion","eclipse-pvdo-parameters/1").put("baselineRunId",1).put("sourceFile",c.path("sourceFile").asText()).put("sourceSha256",i.path("packageFiles").get(0).path("sha256").asText()).put("unit","cP").put("multiplier",2);p.set("keywordLineNumber",c.get("keywordLineNumber"));p.set("rows",c.get("rows"));assertTrue(EclipseBatchParameters.scenario(p));((ObjectNode)n.path("cases").get(1)).set("parameters",p);assertFalse(EclipseBatchParameters.valid(n));
    }
}
