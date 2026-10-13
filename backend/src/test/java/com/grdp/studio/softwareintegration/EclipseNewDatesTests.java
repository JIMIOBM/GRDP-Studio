package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseNewDatesParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;

class EclipseNewDatesTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode p() throws Exception { return (ObjectNode)mapper.readTree("""
        {"schemaVersion":"eclipse-new-dates-parameters/1","baselineRunId":1,"well":"P1","sourceFile":"CASE.DATA","beforeLineNumber":16,"beforeDate":"1996-01-06","afterLineNumber":22,"afterDate":"1996-01-26","shutDate":"1996-01-11","openDate":"1996-01-21"}
        """); }
    private ObjectNode inspection() throws Exception { return (ObjectNode)mapper.readTree("""
        {"schemaVersion":"eclipse-data-inspection/4","caseName":"CASE.DATA","packageFiles":[{"relativePath":"CASE.DATA"}],"scheduleTimeline":[{"kind":"DATES","records":[{"day":"6","month":"JAN","year":"1996"}],"sourceFile":"CASE.DATA","lineNumber":16},{"kind":"DATES","records":[{"day":"26","month":"JAN","year":"1996"}],"sourceFile":"CASE.DATA","lineNumber":22}],"scheduleMetadata":{"wells":[{"name":"P1","sourceFile":"CASE.DATA","lineNumber":8}],"completions":[{"well":"P1","status":"OPEN","sourceFile":"CASE.DATA","lineNumber":11}],"records":[{"keyword":"WCONPROD","values":["P1","OPEN","BHP","5*","3000"],"sourceFile":"CASE.DATA","lineNumber":14}]}}
        """); }
    @Test void adjacentDateAndOpenProducerMatch() throws Exception { assertThat(EclipseNewDatesParameters.matchesInspection(p(), inspection())).isTrue(); }
    @Test void boundaryCalendarOrderTypesAndExtraReject() throws Exception {
        for (var key : new String[] {"shutDate", "openDate", "beforeLineNumber", "extra"}) {
            var p = p(); switch(key) { case "shutDate" -> p.put(key,"1996-02-30"); case "openDate" -> p.put(key,"1996-01-26"); case "beforeLineNumber" -> p.put(key,"16"); default -> p.put(key,true); }
            assertThat(EclipseNewDatesParameters.valid(p)).as(key).isFalse();
        }
    }
    @Test void staleLineAndDateAndMixedTimelineReject() throws Exception {
        var p = p(); p.put("beforeLineNumber",15); assertThat(EclipseNewDatesParameters.matchesInspection(p,inspection())).isFalse();
        p = p(); p.put("beforeDate","1996-01-05"); assertThat(EclipseNewDatesParameters.matchesInspection(p,inspection())).isFalse();
        var i = inspection(); ((ObjectNode)i.path("scheduleTimeline").get(0)).put("kind","TSTEP"); assertThat(EclipseNewDatesParameters.matchesInspection(p(),i)).isFalse();
    }
}
