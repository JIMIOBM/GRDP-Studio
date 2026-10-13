package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseScheduleParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class EclipseMultiWellScheduleTests {
    private final JsonMapper mapper = new JsonMapper();
    private static final String REQUEST = """
        {"schemaVersion":"eclipse-schedule-parameters/6","baselineRunId":74,"wells":["G1","G2"],
        "events":[{"date":"1970-01-15","status":"SHUT"},{"date":"1970-02-01","status":"OPEN"}]}
        """;
    private static final String INSPECTION = """
        {"schemaVersion":"eclipse-data-inspection/4","scheduleMetadata":{
        "wells":[{"name":"G1"},{"name":"G2"},{"name":"D1"}],
        "records":[{"keyword":"WCONHIST","values":["G1","OPEN","ORAT","10"]},
                   {"keyword":"WCONPROD","values":["G2","OPEN","ORAT","10"]},
                   {"keyword":"WCONINJE","values":["D1","WATER","OPEN","RATE","10"]}]},
        "scheduleTimeline":[{"kind":"DATES","records":[{"day":"15","month":"JAN","year":"1970"}]},
                            {"kind":"DATES","records":[{"day":"1","month":"FEB","year":"1970"}]}]}
        """;

    @Test void bindsEveryGroupMemberToInspectedProducer() {
        assertTrue(EclipseScheduleParameters.valid(mapper.readTree(REQUEST)));
        assertTrue(EclipseScheduleParameters.matchesInspection(mapper.readTree(REQUEST), mapper.readTree(INSPECTION)));
        for (String second : new String[]{"D1", "MISSING"}) {
            assertFalse(EclipseScheduleParameters.matchesInspection(mapper.readTree(REQUEST.replace("\"G2\"", "\"" + second + "\"")), mapper.readTree(INSPECTION)));
        }
    }

    @Test void rejectsBadGroupsEventsAndExtraFields() {
        for (String group : new String[]{"[\"G1\"]", "[\"G1\",\"G1\"]", "[\"G1\",2]", "[\"G1\",\"*\"]"}) {
            assertFalse(EclipseScheduleParameters.valid(mapper.readTree(REQUEST.replace("[\"G1\",\"G2\"]", group))));
        }
        assertFalse(EclipseScheduleParameters.valid(mapper.readTree(REQUEST.replace("1970-02-01", "1970-01-15"))));
        assertFalse(EclipseScheduleParameters.valid(mapper.readTree(REQUEST.replace("\"baselineRunId\":74", "\"baselineRunId\":74,\"well\":\"G1\""))));
        assertFalse(EclipseScheduleParameters.matchesInspection(mapper.readTree(REQUEST.replace("1970-02-01", "1970-02-02")), mapper.readTree(INSPECTION)));
    }
}
