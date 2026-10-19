package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import com.grdp.studio.softwareintegration.support.EclipseThermalRockParameters;
import com.grdp.studio.softwareintegration.support.EclipseThermalPlanParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class EclipseThermalRockTests {
    private final JsonMapper mapper=new JsonMapper();
    private ObjectNode inspection(){return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-rock-official-inspection.json"));}
    private ObjectNode parameters(String field){
        var c=inspection().path("thermalRockContext");var p=mapper.createObjectNode().put("schemaVersion",EclipseThermalRockParameters.SCHEMA).put("baselineRunId",1).put("targetValue","HEATCR".equals(field)?45:12);
        for(var k:new String[]{"sourceFile","sourceSha256","cellCount"})p.set(k,c.get(k));
        for(var r:c.path("records"))if(field.equals(r.path("field").asText()))for(var k:new String[]{"field","lineNumber","unit","originalValue"})p.set(k,r.get(k));return p;
    }
    @Test void WorkerProduced23ContextAndBothPropertyChangesValidate(){
        var i=inspection();assertEquals(100,i.path("thermalRockContext").path("activeCellCount").asInt());
        assertEquals(i,mapper.readTree(EclipseDataInspectionValidator.validateAndSerialize(i,mapper)));
        for(var f:new String[]{"HEATCR","THCONR"}){var p=parameters(f);assertTrue(EclipseThermalRockParameters.valid(p));assertTrue(EclipseThermalRockParameters.matchesInspection(p,i));}
        var zero=parameters("THCONR").put("targetValue",0);assertTrue(EclipseThermalRockParameters.matchesInspection(zero,i));
    }
    @Test void Frozen15DoesNotAcceptRockContextOrNewPropertyScenario(){
        var i=inspection().put("schemaVersion","eclipse-data-inspection/15");assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper));
        i.remove("thermalRockContext");assertNotNull(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));assertFalse(EclipseThermalRockParameters.matchesInspection(parameters("HEATCR"),i));
    }
    @Test void WrongContextSourceArrayCountUnitOrRecordsAreRejected(){
        for(var fault:new String[]{"source","count","active","missing","duplicate","unit","line","extra"}){
            var i=inspection();var c=(ObjectNode)i.path("thermalRockContext");var record=(ObjectNode)c.path("records").get(0);
            switch(fault){case "source"->c.put("sourceSha256","0".repeat(64));case "count"->c.put("cellCount",100);case "active"->c.put("activeCellCount",181);case "missing"->c.withArray("records").remove(1);case "duplicate"->((ObjectNode)c.path("records").get(1)).put("field","HEATCR");case "unit"->record.put("unit","degF");case "line"->record.put("lineNumber",0);default->c.put("targetValue",1);}
            assertThrows(IllegalArgumentException.class,()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper),fault);
        }
    }
    @Test void InvalidRequestNeverMatchesAuthenticatedOriginals(){
        for(var fault:new String[]{"source","line","count","unit","original","zero","string","extra","schema","noop"}){
            var p=parameters("HEATCR");switch(fault){case "source"->p.put("sourceSha256","0".repeat(64));case "line"->p.put("lineNumber",1);case "count"->p.put("cellCount",100);case "unit"->p.put("unit","kJ/m3/K");case "original"->p.put("originalValue",34);case "zero"->p.put("targetValue",0);case "string"->p.put("targetValue","45");case "extra"->p.put("temperature",450);case "schema"->p.put("schemaVersion","eclipse-thermal-injection-parameters/1");default->p.put("targetValue",35);}
            assertFalse(EclipseThermalRockParameters.matchesInspection(p,inspection()),fault);
        }
    }
    @Test void ExistingMultiStageInjectionCanStillBindNew23Model(){
        var i=inspection();var c=i.path("thermalInjectionContext");var r=c.path("records").get(0);
        var p=mapper.createObjectNode().put("schemaVersion",EclipseThermalPlanParameters.SCHEMA).put("baselineRunId",1).put("temperatureUnit","degF").put("rateUnit","STB/day").put("pressureUnit","psia");
        for(var k:new String[]{"sourceFile","sourceSha256"})p.set(k,c.get(k));for(var k:new String[]{"well","lineNumber","controlLineNumber"})p.set(k,r.get(k));
        p.putObject("original").put("surfaceInjectionRate",37.5).put("injectionTemperature",450).put("steamQuality",.7).put("bhpUpperLimit",1000);
        var stages=p.putArray("stages");stages.addObject().put("startTimeDays",1095).put("surfaceInjectionRate",25).put("injectionTemperature",450).put("steamQuality",.7).put("bhpUpperLimit",1000);stages.addObject().put("startTimeDays",2190).put("surfaceInjectionRate",25).put("injectionTemperature",400).put("steamQuality",.7).put("bhpUpperLimit",1000);
        assertTrue(EclipseThermalPlanParameters.matchesInspection(p,i));
    }

    @Test void FullNative338ColumnResultUsesThermal23Contract() {
        var result=mapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-rock-native-baseline-result.json"));
        var validated=new com.grdp.studio.softwareintegration.execution.EclipseSummaryResultValidator(mapper).validateThermal("THERM3.DATA",result,inspection());
        assertEquals("SUCCEEDED",validated.terminalStatus().name());assertEquals("VALID_FULL",validated.contract());assertEquals(result,validated.result());
        assertEquals(338,validated.result().path("summary").path("series").size());
    }
    @Test void Native23StillRejectsWrongTemperatureUnitsAndWellIdentity() {
        for(var fault:new String[]{"unit","well"}){
            var result=(ObjectNode)mapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-rock-native-temperature-result.json"));
            boolean changed=false;for(var s:result.path("summary").path("series"))if("WTEMP".equals(s.path("keyword").asText())){((ObjectNode)s).put(fault.equals("unit")?"unit":"objectName",fault.equals("unit")?"DEG C":"UNKNOWN");changed=true;break;}assertTrue(changed,"Native temperature output must exist before corrupting it");
            assertThrows(com.grdp.studio.softwareintegration.execution.EclipseSummaryResultValidator.ResultValidationException.class,
                ()->new com.grdp.studio.softwareintegration.execution.EclipseSummaryResultValidator(mapper).validateThermal("THERM3.DATA",result,mapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-rock-output-inspection.json"))));
        }
    }
}
