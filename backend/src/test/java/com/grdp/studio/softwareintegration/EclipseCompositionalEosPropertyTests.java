package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.execution.EclipseSummaryResultValidator;
import com.grdp.studio.softwareintegration.support.EclipseCompositionalFluidPropertyParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
class EclipseCompositionalEosPropertyTests {
    private final JsonMapper mapper=new JsonMapper();
    private ObjectNode inspection(){return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-eos-inspection.json"));}
    private ObjectNode parameters(ObjectNode i,int index){
        var c=i.path("compositionalEosPropertyContexts").get(index);var r=c.path("records").get(2);
        var p=mapper.createObjectNode().put("schemaVersion",EclipseCompositionalFluidPropertyParameters.EOS_PROPERTY_SCHEMA).put("baselineRunId",1).put("targetValue",new double[]{17,.54,.078}[index]);
        for(var k:new String[]{"sourceFile","sourceSha256"})p.set(k,c.get(k));
        for(var k:new String[]{"componentIndex","componentName","field","lineNumber","unit","originalValue"})p.set(k,r.get(k));return p;
    }
    @Test void actualVersion22BindsAllThreePropertiesAndPreservesAllOlderContexts(){
        var i=inspection();var restored=EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));assertEquals(i,mapper.readTree(mapper.writeValueAsString(restored)));
        for(int j=0;j<3;j++){var p=parameters(i,j);assertTrue(EclipseCompositionalFluidPropertyParameters.matchesInspection(p,restored));assertEquals("C1",p.path("componentName").asText());assertEquals(140+6*j,p.path("lineNumber").asInt());}
        assertEquals("lb/lb-M",parameters(i,0).path("unit").asText());assertTrue(restored.has("compositionalAcentricFactorContext"));
    }
    @Test void positiveEffectiveOverridesCannotUseAcentricRulesOrAnotherPropertyUnit(){
        for(int j=0;j<3;j++)for(var fault:new String[]{"zero","negative","same","high","string","field","unit","schema","line","hash","extra"}){
            var i=inspection();var p=parameters(i,j);
            switch(fault){case "zero"->p.put("targetValue",0);case "negative"->p.put("targetValue",-1);case "same"->p.set("targetValue",p.get("originalValue"));case "high"->p.put("targetValue",1e9);case "string"->p.put("targetValue","17");case "field"->p.put("field","ACF");case "unit"->p.put("unit",j==0?"dimensionless":"lb/lb-M");case "schema"->p.put("schemaVersion",EclipseCompositionalFluidPropertyParameters.ACENTRIC_FACTOR_SCHEMA);case "line"->p.put("lineNumber",122);case "hash"->p.put("sourceSha256","0".repeat(64));default->p.put("extra",true);}
            assertFalse(EclipseCompositionalFluidPropertyParameters.matchesInspection(p,i),j+":"+fault);
        }
    }
    @Test void root22RequiresEveryEosArrayAndOlderRootsStayClosed(){
        var i=inspection();i.put("schemaVersion","eclipse-data-inspection/21");assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper));
        i.remove("compositionalEosPropertyContexts");assertNotNull(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));
        i.put("schemaVersion","eclipse-data-inspection/22");assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper));
        for(int j=0;j<3;j++){var bad=inspection();bad.withArray("compositionalEosPropertyContexts").remove(j);assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(bad,mapper));}
    }
    @Test void unboundOrDuplicatePropertyArraysCannotSurvivePersistence(){
        for(var fault:new String[]{"source","field","unit","zero","missing","extra","duplicate"}){
            var i=inspection();var c=(ObjectNode)i.path("compositionalEosPropertyContexts").get(0);var row=(ObjectNode)c.path("records").get(2);
            switch(fault){case "source"->c.put("sourceSha256","0".repeat(64));case "field"->row.put("field","OMEGAA");case "unit"->row.put("unit","dimensionless");case "zero"->row.put("originalValue",0);case "missing"->c.withArray("records").remove(8);case "extra"->c.put("extra",true);default->i.withArray("compositionalEosPropertyContexts").set(1,c.deepCopy());}
            assertFalse(EclipseCompositionalFluidPropertyParameters.validEosPropertyContexts(i.get("compositionalEosPropertyContexts"),i));
            assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper));
        }
    }
    @Test void nativeSummariesPreserveAll330VectorsAndDuplicateFieldOilColumns(){
        var i=inspection();for(var field:new String[]{"MW","OMEGAA","OMEGAB"}){
            var all=mapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-eos-"+field+"-native.json"));var data=all.path("case");
            var result=mapper.createObjectNode().put("schemaVersion","eclipse-summary-result/2").put("modelKind","eclipse_300").put("runTask","eclipse").put("resultContract","VALID_FULL").put("caseName",i.path("caseName").asText());
            result.set("simulation",i.get("simulation"));result.set("summary",data.get("summary"));result.set("eclEnd",data.get("eclEnd"));result.set("outputFiles",all.get("outputFiles"));
            var checked=new EclipseSummaryResultValidator(mapper).validateCompositional(i.path("caseName").asText(),result,i);assertEquals(result,checked.result());assertEquals(330,checked.result().path("summary").path("series").size());
        }
    }
}
