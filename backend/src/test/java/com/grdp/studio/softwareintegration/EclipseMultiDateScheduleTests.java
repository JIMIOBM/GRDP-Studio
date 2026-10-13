package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseScheduleParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class EclipseMultiDateScheduleTests {
    private final JsonMapper mapper = new JsonMapper();
    private static final String REQUEST = """
        {"schemaVersion":"eclipse-schedule-parameters/7","baselineRunId":74,"wells":["G1","G2"],
        "events":[{"date":"1970-01-15","status":"SHUT"},{"date":"1970-02-01","status":"OPEN"},{"date":"1970-03-01","status":"SHUT"}]}
        """;
    @Test void newContractAcceptsThreeEventsAndRejectsUnorderedOrUnknownStates() {
        assertTrue(EclipseScheduleParameters.valid(mapper.readTree(REQUEST)));
        assertTrue(EclipseScheduleParameters.valid(mapper.readTree(REQUEST.replace("[\"G1\",\"G2\"]", "[\"G1\"]"))));
        assertFalse(EclipseScheduleParameters.valid(mapper.readTree(REQUEST.replace("parameters/7", "parameters/6"))));
        for (String date : new String[]{"1970-02-01", "1970-01-01", "1970-02-30"})
            assertFalse(EclipseScheduleParameters.valid(mapper.readTree(REQUEST.replace("1970-03-01", date))));
        assertFalse(EclipseScheduleParameters.valid(mapper.readTree(REQUEST.replace("SHUT", "AUTO"))));
    }
    @Test void everyDateAndEveryWellMustBeInTheInspection() {
        var inspection = mapper.readTree("""
            {"schemaVersion":"eclipse-data-inspection/4","scheduleMetadata":{
            "wells":[{"name":"G1"},{"name":"G2"}],
            "records":[{"keyword":"WCONHIST","values":["G1"]},{"keyword":"WCONPROD","values":["G2"]}]},
            "scheduleTimeline":[{"kind":"DATES","records":[{"day":"15","month":"JAN","year":"1970"}]},
            {"kind":"DATES","records":[{"day":"1","month":"FEB","year":"1970"}]},
            {"kind":"DATES","records":[{"day":"1","month":"MAR","year":"1970"}]}]}
            """);
        assertTrue(EclipseScheduleParameters.matchesInspection(mapper.readTree(REQUEST), inspection));
        assertFalse(EclipseScheduleParameters.matchesInspection(mapper.readTree(REQUEST.replace("G2", "UNKNOWN")), inspection));
        assertFalse(EclipseScheduleParameters.matchesInspection(mapper.readTree(REQUEST.replace("1970-03-01", "1970-04-01")), inspection));
    }
}
