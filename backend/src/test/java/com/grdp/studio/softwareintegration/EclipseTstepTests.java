package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseTstepParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;
class EclipseTstepTests {
 private final ObjectMapper mapper=new ObjectMapper();
 private ObjectNode p() throws Exception { return (ObjectNode)mapper.readTree("""
 {"schemaVersion":"eclipse-tstep-parameters/1","baselineRunId":1,"sourceFile":"CASE.DATA","lineNumber":7,"unit":"day","originalSteps":[5,5,5,5,5,5,5,5,5,5],"targetSteps":[1,4,5,10,30]}
 """); }
 private ObjectNode i() throws Exception { return (ObjectNode)mapper.readTree("""
 {"schemaVersion":"eclipse-data-inspection/4","caseName":"CASE.DATA","unitSystem":"FIELD","packageFiles":[{"relativePath":"CASE.DATA"}],"scheduleTimeline":[{"kind":"TSTEP","sourceFile":"CASE.DATA","lineNumber":7,"steps":["5","5","5","5","5","5","5","5","5","5"]}]}
 """); }
 @Test void sourceIntervalsAndDurationMatch() throws Exception { assertThat(EclipseTstepParameters.matchesInspection(p(),i())).isTrue(); }
 @Test void zeroDifferentDurationOrStaleSourceReject() throws Exception {
  var p=p();p.set("targetSteps",mapper.readTree("[0,50]"));assertThat(EclipseTstepParameters.valid(p)).isFalse();
  p=p();p.set("targetSteps",mapper.readTree("[1,2]"));assertThat(EclipseTstepParameters.valid(p)).isFalse();
  p=p();p.put("lineNumber",8);assertThat(EclipseTstepParameters.matchesInspection(p,i())).isFalse();
  var i=i();i.put("unitSystem","LAB");assertThat(EclipseTstepParameters.matchesInspection(p(),i)).isFalse();
 }
}
