package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseGroupStructureParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class EclipseGroupStructureTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode inspection(String operation) throws Exception {
        var i=(ObjectNode)mapper.readTree("""
          {"schemaVersion":"eclipse-data-inspection/12","caseName":"CASE.DATA","sections":["RUNSPEC","SCHEDULE"],"unitSystem":"FIELD","phases":["OIL"],"dimensions":null,"wellNames":["P1","P2","P3","P4"],"scheduleTimeline":[{"kind":"TSTEP","steps":["10"],"sourceFile":"CASE.DATA","lineNumber":100}],"packageFiles":[{"relativePath":"CASE.DATA","sizeBytes":500,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"vfpTables":[],"scheduleMetadata":{"wells":[{"name":"P1","group":"GA","sourceFile":"CASE.DATA","lineNumber":5},{"name":"P2","group":"GA","sourceFile":"CASE.DATA","lineNumber":6},{"name":"P3","group":"GB","sourceFile":"CASE.DATA","lineNumber":7},{"name":"P4","group":"GB","sourceFile":"CASE.DATA","lineNumber":8}],"groups":[{"name":"GA","parent":"P","sourceFile":"CASE.DATA","lineNumber":20},{"name":"GB","parent":"P","sourceFile":"CASE.DATA","lineNumber":21}],"records":[],"completions":[]},"groupStructureControls":[]}
          """);
        var leaves=mapper.readTree("""
          [{"group":"GA","efficiency":1,"members":[{"well":"P1","bhpLimit":2500,"oilRateLimit":20000,"guideRate":0},{"well":"P2","bhpLimit":2500,"oilRateLimit":20000,"guideRate":0}]},{"group":"GB","efficiency":1,"members":[{"well":"P3","bhpLimit":2500,"oilRateLimit":20000,"guideRate":0},{"well":"P4","bhpLimit":2500,"oilRateLimit":20000,"guideRate":0}]}]
          """);
        if(operation.equals("WGRUPCON"))for(var leaf:leaves)for(var m:leaf.path("members"))((ObjectNode)m).put("guideRate",1);
        if(operation.equals("GEFAC"))((ObjectNode)leaves.get(0)).put("efficiency",0.8);
        var control=mapper.createObjectNode().put("parentGroup","P").put("objectName","P").put("keyword","GCONPROD").put("sourceFile","CASE.DATA").put("lineNumber",40).put("unit","STB/day").put("originalValue",2000).put("parentTarget",2000);
        control.set("leaves",leaves);i.withArray("groupStructureControls").add(control);
        if(operation.equals("WGRUPCON"))for(int n=1;n<=4;n++){var c=control.deepCopy();c.put("objectName","P"+n);c.put("keyword",operation);c.put("lineNumber",40+n);c.put("unit","dimensionless");c.put("originalValue",1);i.withArray("groupStructureControls").add(c);}
        if(operation.equals("GEFAC")){var c=control.deepCopy();c.put("objectName","GA");c.put("keyword",operation);c.put("lineNumber",50);c.put("unit","dimensionless");c.put("originalValue",0.8);i.withArray("groupStructureControls").add(c);}
        return i;
    }
    private ObjectNode parameters(String operation) throws Exception {
        var p=(ObjectNode)mapper.readTree("""
          {"schemaVersion":"eclipse-group-structure-parameters/1","baselineRunId":1,"phase":"INITIAL","parentGroup":"P","objectName":"P","keyword":"GCONPROD","sourceFile":"CASE.DATA","lineNumber":40,"unit":"STB/day","originalValue":2000,"targetValue":1000,"members":["P1","P2","P3","P4"]}
          """);
        if(operation.equals("WGRUPCON")){p.put("keyword",operation);p.put("objectName","P2");p.put("unit","dimensionless");p.put("lineNumber",42);p.put("originalValue",1);p.put("targetValue",3);}
        if(operation.equals("GEFAC")){p.put("keyword",operation);p.put("objectName","GA");p.put("unit","dimensionless");p.put("lineNumber",50);p.put("originalValue",0.8);p.put("targetValue",0.5);}
        return p;
    }
    @Test void separateTreeGuideAndDowntimeSnapshotsPersistAndBind() throws Exception {
        for(var operation:new String[]{"GCONPROD","WGRUPCON","GEFAC"}){
            var i=inspection(operation);var saved=EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));
            assertThat(saved.size()).isEqualTo(12);assertThat(EclipseGroupStructureParameters.matchesInspection(parameters(operation),saved)).as(operation).isTrue();
        }
    }
    @Test void staleSourceOriginalUnitsOrderAndGraphCannotBind() throws Exception {
        for(var key:new String[]{"parentGroup","objectName","sourceFile","lineNumber","unit","originalValue","members"}){
            var p=parameters("WGRUPCON");switch(key){case "lineNumber"->p.put(key,43);case "originalValue"->p.put(key,2);case "members"->p.set(key,mapper.readTree("[\"P2\",\"P1\",\"P3\",\"P4\"]"));default->p.put(key,"WRONG");}
            assertThat(EclipseGroupStructureParameters.matchesInspection(p,inspection("WGRUPCON"))).as(key).isFalse();
        }
    }
    @Test void graphCycleMissingMembersOrParentWellsRejectMetadata() throws Exception {
        for(var fault:new String[]{"cycle","missing","parentWell"}){
            var i=inspection("GCONPROD");
            if(fault.equals("cycle"))((ObjectNode)i.path("scheduleMetadata").path("groups").get(1)).put("parent","GA");
            else if(fault.equals("parentWell"))((ObjectNode)i.path("scheduleMetadata").path("wells").get(0)).put("group","P");
            else i.path("groupStructureControls").get(0).path("leaves").get(0).withArray("members").remove(1);
            assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).as(fault).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void everyGuideMustExistWithTheSameCompleteSnapshot() throws Exception {
        var i=inspection("WGRUPCON");i.withArray("groupStructureControls").remove(4);assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).isInstanceOf(IllegalArgumentException.class);
        var j=inspection("WGRUPCON");((ObjectNode)j.path("groupStructureControls").get(1).path("leaves").get(0).path("members").get(0)).put("guideRate",2);
        assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(j,mapper)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void efficiencyCannotExceedOneOrPretendToBeAnOilRate() throws Exception {
        var p=parameters("GEFAC");p.put("targetValue",1.1);assertThat(EclipseGroupStructureParameters.valid(p)).isFalse();
        p=parameters("GEFAC");p.put("unit","STB/day");assertThat(EclipseGroupStructureParameters.valid(p)).isFalse();
        var i=inspection("GEFAC");((ObjectNode)i.path("groupStructureControls").get(1)).put("originalValue",0.6);assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void wrongVersionsExtraFieldsAndLaterSourceReject() throws Exception {
        var i=inspection("GCONPROD");i.put("schemaVersion","eclipse-data-inspection/6");assertThat(EclipseGroupStructureParameters.matchesInspection(parameters("GCONPROD"),i)).isFalse();
        assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(i,mapper)).isInstanceOf(IllegalArgumentException.class);
        var p=parameters("GCONPROD");p.put("extra",0);assertThat(EclipseGroupStructureParameters.valid(p)).isFalse();
        var j=inspection("GCONPROD");((ObjectNode)j.path("groupStructureControls").get(0)).put("lineNumber",101);assertThatThrownBy(()->EclipseDataInspectionValidator.validateAndSerialize(j,mapper)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void metricSurfaceVolumeAndDimensionlessUnitsStayDistinct() throws Exception {
        for(var operation:new String[]{"GCONPROD","WGRUPCON","GEFAC"}){
            var i=inspection(operation);i.put("unitSystem","METRIC");((ObjectNode)i.path("groupStructureControls").get(0)).put("unit","sm3/day");var p=parameters(operation);if(operation.equals("GCONPROD"))p.put("unit","sm3/day");
            var saved=EclipseDataInspectionValidator.parsePersisted(EclipseDataInspectionValidator.validateAndSerialize(i,mapper));assertThat(EclipseGroupStructureParameters.matchesInspection(p,saved)).isTrue();
        }
    }
}
