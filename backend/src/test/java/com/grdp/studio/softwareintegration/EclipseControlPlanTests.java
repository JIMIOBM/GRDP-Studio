package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseControlPlanParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class EclipseControlPlanTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection() throws Exception {
        try(var in=getClass().getResourceAsStream("/eclipse-control-plan-inspection.json")){assertThat(in).isNotNull();return (ObjectNode)mapper.readTree(in);}
    }
    private ObjectNode parameters(ObjectNode i) {
        var p=mapper.createObjectNode().put("schemaVersion","eclipse-control-plan-parameters/1").put("baselineRunId",1);
        var edits=p.putArray("edits");
        for(var record:i.path("productionControlPlan").path("records"))if(record.path("timeDays").asInt()>0){
            var e=((ObjectNode)record).deepCopy();e.remove("bhpLimit");e.put("targetOilRate",e.path("timeDays").asInt()==365?250:e.path("timeDays").asInt()==730?350:450);edits.add(e);
        }
        return p;
    }
    @Test void actualWorkerSnapshotPersistsCompleteTenRecordChainAndSixEdits() throws Exception {
        var i=inspection();var saved=EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));
        assertThat(saved).isNotNull();assertThat(saved.size()).isEqualTo(12);assertThat(saved.path("productionControlPlan").path("records").size()).isEqualTo(10);
        assertThat(EclipseControlPlanParameters.matchesInspection(parameters(i),saved)).isTrue();
    }
    @Test void staleDateTimeLineOriginalUnitWellAndSourceCannotBind() throws Exception {
        for(var fault:new String[]{"well","sourceFile","lineNumber","date","timeDays","unit","originalOilRate"}){
            var i=inspection();var p=parameters(i);var e=(ObjectNode)p.path("edits").get(5);
            switch(fault){case "lineNumber"->e.put(fault,e.path(fault).asInt()+1);case "timeDays"->e.put(fault,1096);case "originalOilRate"->e.put(fault,501);case "date"->e.put(fault,"1985-10-19");default->e.put(fault,"WRONG");}
            assertThat(EclipseControlPlanParameters.matchesInspection(p,i)).as(fault).isFalse();
        }
    }
    @Test void metadataAndCompletePlanCannotDisagreeOrOmitALaterOverride() throws Exception {
        for(var fault:new String[]{"missing","original","bhp","date","time","extra"}){
            var i=inspection();var c=(ObjectNode)i.path("productionControlPlan").path("records").get(9);
            switch(fault){case "missing"->i.path("productionControlPlan").withArray("records").remove(9);case "original"->c.put("originalOilRate",501);case "bhp"->c.put("bhpLimit",2499);case "date"->c.put("date","1985-10-19");case "time"->c.put("timeDays",1096);default->c.put("extra",1);}
            assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).as(fault).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void incompleteInitialStateAndNonOpenCompletionReject() throws Exception {
        var i=inspection();i.path("productionControlPlan").withArray("records").remove(0);assertThat(EclipseControlPlanParameters.validPlan(i)).isFalse();
        i=inspection();((ObjectNode)i.path("scheduleMetadata").path("completions").get(0)).put("status","SHUT");assertThat(EclipseControlPlanParameters.validPlan(i)).isFalse();
    }
    @Test void mixedTimeWrongEndClockAndDateOrderingReject() throws Exception {
        for(var fault:new String[]{"mixed","end","clock","order"}){
            var i=inspection();
            switch(fault){case "mixed"->((ObjectNode)i.path("scheduleTimeline").get(0)).put("kind","TSTEP");case "end"->((ObjectNode)i.path("productionControlPlan")).put("endTimeDays",3651);case "clock"->((ObjectNode)i.path("scheduleTimeline").get(0).path("records").get(0)).put("time","06:00:00");default->((ObjectNode)i.path("scheduleTimeline").get(1).path("records").get(0)).put("year","1982");}
            assertThat(EclipseControlPlanParameters.validPlan(i)).as(fault).isFalse();
        }
    }
    @Test void duplicateReversedZeroSameOriginalAndExtraEditsReject() throws Exception {
        for(var fault:new String[]{"duplicate","reverse","zero","same","extra","schema"}){
            var i=inspection();var p=parameters(i);var e=(ObjectNode)p.path("edits").get(5);
            switch(fault){case "duplicate"->p.withArray("edits").add(e.deepCopy());case "reverse"->{var first=p.withArray("edits").remove(0);p.withArray("edits").add(first);}case "zero"->e.put("targetOilRate",0);case "same"->e.put("targetOilRate",500);case "extra"->p.put("extra",true);default->p.put("schemaVersion","eclipse-schedule-parameters/1");}
            assertThat(EclipseControlPlanParameters.valid(p)).as(fault).isFalse();
        }
    }
    @Test void metricUsesSurfaceVolumeAndKeepsCalendarLeapDays() throws Exception {
        var i=inspection();i.put("unitSystem","METRIC");for(var c:i.path("productionControlPlan").path("records"))((ObjectNode)c).put("unit","sm3/day");
        var p=parameters(i);var saved=EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));
        assertThat(EclipseControlPlanParameters.matchesInspection(p,saved)).isTrue();assertThat(saved.path("productionControlPlan").path("records").get(6).path("date").asText()).isEqualTo("1984-10-18");
    }
}
