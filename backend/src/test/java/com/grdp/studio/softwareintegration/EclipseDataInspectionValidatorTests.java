package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EclipseDataInspectionValidatorTests {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsEveryFrozenUnitSystemIncludingPvtM() {
        for (String unitSystem : List.of("METRIC", "FIELD", "LAB", "PVT-M")) {
            ObjectNode inspection = inspection();
            inspection.put("unitSystem", unitSystem);
            assertThat(validate(inspection)).contains("\"unitSystem\":\"" + unitSystem + "\"");
        }
    }

    @Test
    void rejectsUnknownUnitSystem() {
        ObjectNode inspection = inspection();
        inspection.put("unitSystem", "SI");
        assertInvalid(inspection);
    }

    @Test
    void acceptsMaxDimensionBoundInclusively() {
        ObjectNode inspection = inspection();
        inspection.set("dimensions", objectMapper.readTree("{\"nx\":1000000,\"ny\":1000000,\"nz\":1000000}"));
        assertThat(validate(inspection)).contains("\"nx\":1000000", "\"ny\":1000000", "\"nz\":1000000");
    }

    @Test
    void rejectsDimensionAboveBoundOrNonPositive() {
        ObjectNode above = inspection();
        above.set("dimensions", objectMapper.readTree("{\"nx\":1000001,\"ny\":1,\"nz\":1}"));
        assertInvalid(above);
        ObjectNode zero = inspection();
        zero.set("dimensions", objectMapper.readTree("{\"nx\":0,\"ny\":1,\"nz\":1}"));
        assertInvalid(zero);
    }

    @Test
    void acceptsEmptySections() {
        ObjectNode inspection = inspection();
        inspection.set("sections", objectMapper.createArrayNode());
        assertThat(validate(inspection)).contains("\"sections\":[]");
    }

    @Test
    void acceptsBasenameCaseNameWithoutLeadingAsciiOrDataSuffixRequirement() {
        for (String caseName : List.of("CASE.DATA", "_CASE.DATA", "-CASE.DATA", "MODEL", "模型.DATA")) {
            ObjectNode inspection = inspection();
            inspection.put("caseName", caseName);
            assertThat(validate(inspection)).contains("\"caseName\":\"" + caseName + "\"");
        }
    }

    @Test
    void rejectsCaseNameWithPathSeparators() {
        for (String caseName : List.of("C:\\private\\CASE.DATA", "/tmp/CASE.DATA", "nested/CASE.DATA")) {
            ObjectNode inspection = inspection();
            inspection.put("caseName", caseName);
            assertInvalid(inspection);
        }
    }

    @Test
    void rejectsEmptyOrOversizedCaseName() {
        ObjectNode empty = inspection();
        empty.put("caseName", "");
        assertInvalid(empty);
        ObjectNode oversized = inspection();
        oversized.put("caseName", "A".repeat(256));
        assertInvalid(oversized);
        ObjectNode boundary = inspection();
        boundary.put("caseName", "A".repeat(255));
        assertThat(validate(boundary)).isNotNull();
    }

    @Test
    void acceptsV2AndPreservesOnlyFrozenFieldsInSourceOrder() throws Exception {
        ObjectNode inspection = inspectionV2();
        inspection.set("wellNames", objectMapper.readTree("[\"WELL-2\",\"WELL-1\"]"));
        inspection.set("scheduleTimeline", objectMapper.readTree("""
                [{"kind":"DATES","records":[{"day":"2","month":"JAN","year":"2025","time":null},{"day":"1","month":"FEB","year":"2026","time":"23:59:58"}]},
                 {"kind":"TSTEP","steps":["0.5","+2","3.25","1E-3"]}]
                """));

        JsonNode validated = objectMapper.readTree(validate(inspection));

        assertThat(validated).isEqualTo(objectMapper.readTree("""
                {"schemaVersion":"eclipse-data-inspection/2","caseName":"CASE.DATA","sections":[],"unitSystem":null,"phases":[],"dimensions":null,
                 "wellNames":["WELL-2","WELL-1"],"scheduleTimeline":[{"kind":"DATES","records":[{"day":"2","month":"JAN","year":"2025","time":null},{"day":"1","month":"FEB","year":"2026","time":"23:59:58"}]},{"kind":"TSTEP","steps":["0.5","+2","3.25","1E-3"]}]}
                """));
    }

    @Test
    void acceptsV3PackageManifestAndRejectsUnsafeEntries() throws Exception {
        ObjectNode inspection = inspectionV3();
        ArrayNode files = inspection.withArray("packageFiles");
        files.add(objectMapper.readTree("""
                {"relativePath":"CASE.DATA","sizeBytes":128,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """));
        files.add(objectMapper.readTree("""
                {"relativePath":"include/grid.inc","sizeBytes":256,"sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
                """));
        JsonNode validated = objectMapper.readTree(validate(inspection));
        assertThat(validated).isEqualTo(objectMapper.readTree("""
                {"schemaVersion":"eclipse-data-inspection/3","caseName":"CASE.DATA","sections":[],"unitSystem":null,"phases":[],"dimensions":null,
                 "wellNames":[],"scheduleTimeline":[],"packageFiles":[
                   {"relativePath":"CASE.DATA","sizeBytes":128,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                   {"relativePath":"include/grid.inc","sizeBytes":256,"sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]}
                """));
        for (String path : List.of("../outside.inc", "/absolute.inc", "include\\grid.inc", "include//grid.inc", "include:bad")) {
            ObjectNode invalid = inspectionV3();
            invalid.withArray("packageFiles").add(objectMapper.readTree("{\"relativePath\":\"" + path.replace("\\", "\\\\") + "\",\"sizeBytes\":1,\"sha256\":\"" + "a".repeat(64) + "\"}"));
            assertInvalid(invalid);
        }
    }

    @Test
    void acceptsV4ScheduleMetadataAndPreservesRawLexemes() throws Exception {
        ObjectNode inspection = inspectionV4();
        inspection.set("scheduleMetadata", objectMapper.readTree("""
                {"wells":[{"name":"WELL_1","group":"GROUP_1","sourceFile":"BASE.SCH","lineNumber":4}],
                 "groups":[{"name":"GROUP_1","parent":"FIELD","sourceFile":"BASE.SCH","lineNumber":7}],
                 "records":[{"keyword":"COMPDAT","values":["WELL_1","1","1","1","1","OPEN"],"sourceFile":"BASE.SCH","lineNumber":10},
                            {"keyword":"WCONHIST","values":["WELL_1","OPEN","ORAT","10"],"sourceFile":"BASE.SCH","lineNumber":13}],
                 "completions":[{"keyword":"COMPDAT","well":"WELL_1","i":"1","j":"1","k1":"1","k2":"1","status":"OPEN","sourceFile":"BASE.SCH","lineNumber":10}]}
                """));

        JsonNode validated = objectMapper.readTree(validate(inspection));

        assertThat(validated).isEqualTo(objectMapper.readTree("""
                 {"schemaVersion":"eclipse-data-inspection/4","caseName":"CASE.DATA","sections":[],"unitSystem":null,"phases":[],"dimensions":null,
                 "wellNames":[],"scheduleTimeline":[],"packageFiles":[{"relativePath":"CASE.DATA","sizeBytes":1,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"scheduleMetadata":
                 {"wells":[{"name":"WELL_1","group":"GROUP_1","sourceFile":"BASE.SCH","lineNumber":4}],"groups":[{"name":"GROUP_1","parent":"FIELD","sourceFile":"BASE.SCH","lineNumber":7}],
                  "records":[{"keyword":"COMPDAT","values":["WELL_1","1","1","1","1","OPEN"],"sourceFile":"BASE.SCH","lineNumber":10},
                             {"keyword":"WCONHIST","values":["WELL_1","OPEN","ORAT","10"],"sourceFile":"BASE.SCH","lineNumber":13}],
                  "completions":[{"keyword":"COMPDAT","well":"WELL_1","i":"1","j":"1","k1":"1","k2":"1","status":"OPEN","sourceFile":"BASE.SCH","lineNumber":10}]}}
                """));
    }

    @Test
    void rejectsUnsafeOrUnknownScheduleMetadataRecords() throws Exception {
        for (String keyword : List.of("UNKNOWN", "ECLIPSE", "WELSPECS/")) {
            ObjectNode inspection = inspectionV4();
            ((ObjectNode) inspection.get("scheduleMetadata")).withArray("records")
                    .add(objectMapper.readTree("{\"keyword\":\"" + keyword + "\",\"values\":[\"WELL_1\"]}"));
            assertInvalid(inspection);
        }
        ObjectNode unsafe = inspectionV4();
        ((ObjectNode) unsafe.get("scheduleMetadata")).withArray("records")
                .add(objectMapper.readTree("{\"keyword\":\"COMPDAT\",\"values\":[\"C:\\\\private\\\\deck\"]}"));
        assertInvalid(unsafe);
    }

    @Test
    void v1RemainsClosedAndIsNotUpConverted() {
        ObjectNode inspection = inspection();
        inspection.set("wellNames", objectMapper.createArrayNode());
        inspection.set("scheduleTimeline", objectMapper.createArrayNode());

        assertInvalid(inspection);
    }

    @Test
    void rejectsV2UnknownFieldsAndMixedScheduleEventShapes() {
        ObjectNode root = inspectionV2();
        root.put("deck", "RUNSPEC\nprivate deck content");
        assertInvalid(root);

        ObjectNode event = datesEvent();
        event.put("steps", "1");
        ObjectNode mixed = inspectionV2();
        mixed.set("scheduleTimeline", objectMapper.createArrayNode().add(event));
        assertInvalid(mixed);

        ObjectNode nestedEvent = datesEvent();
        ObjectNode record = (ObjectNode) nestedEvent.withArray("records").get(0);
        record.put("raw", "DATES 1 JAN 2025 /");
        ObjectNode nested = inspectionV2();
        nested.set("scheduleTimeline", objectMapper.createArrayNode().add(nestedEvent));
        assertInvalid(nested);
    }

    @Test
    void rejectsUnsafeOrOversizedWellNames() {
        for (String name : List.of("C:\\private\\WELL", "nested/WELL", "../WELL", "net.pipe://localhost/pipe/private",
                "WELL\n2", "WELL_SECRET", "WELL_PASSWD", "WELL_AUTHORIZATION", "LICENSE_SERVER", "ENVIRONMENT_NAME", "A".repeat(129))) {
            ObjectNode inspection = inspectionV2();
            inspection.set("wellNames", objectMapper.createArrayNode().add(name));
            assertInvalid(inspection);
        }
        ObjectNode unicodeBoundary = inspectionV2();
        unicodeBoundary.set("wellNames", objectMapper.createArrayNode().add("\uD83D\uDE00".repeat(128)));
        assertThat(validate(unicodeBoundary)).contains("wellNames");
    }

    @Test
    void enforcesV2CollectionBounds() {
        ObjectNode names = inspectionV2();
        names.set("wellNames", strings(1_001, "WELL"));
        assertInvalid(names);
        ObjectNode events = inspectionV2();
        ArrayNode schedule = objectMapper.createArrayNode();
        for (int index = 0; index < 1_001; index++) schedule.add(tstepEvent());
        events.set("scheduleTimeline", schedule);
        assertInvalid(events);
        ObjectNode dates = inspectionV2();
        ObjectNode datesEvent = datesEvent();
        ArrayNode records = datesEvent.withArray("records");
        for (int index = 1; index < 4_000; index++) records.add(dateRecord());
        dates.set("scheduleTimeline", objectMapper.createArrayNode().add(datesEvent));
        assertThat(validate(dates)).contains("DATES");
        records.add(dateRecord());
        assertInvalid(dates);
        ObjectNode steps = inspectionV2();
        ObjectNode tstepEvent = tstepEvent();
        ArrayNode values = tstepEvent.withArray("steps");
        for (int index = 1; index < 8_000; index++) values.add("1");
        steps.set("scheduleTimeline", objectMapper.createArrayNode().add(tstepEvent));
        assertThat(validate(steps)).contains("TSTEP");
        values.add("1");
        assertInvalid(steps);
    }

    @Test
    void rejectsScheduleDateAndStepTotalsDistributedAcrossEvents() {
        ObjectNode dates = inspectionV2();
        dates.set("scheduleTimeline", objectMapper.createArrayNode()
                .add(datesEvent(2_000))
                .add(datesEvent(2_001)));
        assertInvalid(dates);

        ObjectNode steps = inspectionV2();
        steps.set("scheduleTimeline", objectMapper.createArrayNode()
                .add(tstepEvent(4_000))
                .add(tstepEvent(4_000))
                .add(tstepEvent(1)));
        assertInvalid(steps);
    }

    @Test
    void rejectsInvalidDateAndDecimalLexemes() {
        for (String date : List.of("{\"day\":\"01\",\"month\":\"JAN\",\"year\":\"2025\",\"time\":null}",
                "{\"day\":\"1\",\"month\":\"JANUARY\",\"year\":\"2025\",\"time\":null}",
                "{\"day\":\"1\",\"month\":\"JAN\",\"year\":\"25\",\"time\":null}",
                "{\"day\":\"1\",\"month\":\"JAN\",\"year\":\"2025\",\"time\":\"24:00\"}")) {
            ObjectNode inspection = inspectionV2();
            ObjectNode event = datesEvent();
            event.set("records", objectMapper.createArrayNode().add(objectMapper.readTree(date)));
            inspection.set("scheduleTimeline", objectMapper.createArrayNode().add(event));
            assertInvalid(inspection);
        }
        for (String step : List.of("1e", "1.", ".5", "NaN", "-0", "-3.25", "1e9999")) {
            ObjectNode inspection = inspectionV2();
            ObjectNode event = tstepEvent();
            event.set("steps", objectMapper.createArrayNode().add(step));
            inspection.set("scheduleTimeline", objectMapper.createArrayNode().add(event));
            assertInvalid(inspection);
        }
    }

    private ObjectNode inspection() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schemaVersion", "eclipse-data-inspection/1");
        node.put("caseName", "CASE.DATA");
        node.set("sections", objectMapper.createArrayNode());
        node.putNull("unitSystem");
        node.set("phases", objectMapper.createArrayNode());
        node.putNull("dimensions");
        return node;
    }

    private ObjectNode inspectionV2() {
        ObjectNode node = inspection();
        node.put("schemaVersion", "eclipse-data-inspection/2");
        node.set("wellNames", objectMapper.createArrayNode());
        node.set("scheduleTimeline", objectMapper.createArrayNode());
        return node;
    }

    private ObjectNode inspectionV3() {
        ObjectNode node = inspectionV2();
        node.put("schemaVersion", "eclipse-data-inspection/3");
        node.set("packageFiles", objectMapper.createArrayNode());
        return node;
    }

    private ObjectNode inspectionV4() throws Exception {
        ObjectNode node = inspectionV3();
        node.put("schemaVersion", "eclipse-data-inspection/4");
        node.withArray("packageFiles").add(objectMapper.readTree("{\"relativePath\":\"CASE.DATA\",\"sizeBytes\":1,\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}"));
        node.set("scheduleMetadata", objectMapper.readTree("{\"wells\":[],\"groups\":[],\"records\":[],\"completions\":[]}"));
        return node;
    }

    private ObjectNode datesEvent() {
        return datesEvent(1);
    }

    private ObjectNode datesEvent(int records) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("kind", "DATES");
        ArrayNode values = objectMapper.createArrayNode();
        for (int index = 0; index < records; index++) values.add(dateRecord());
        event.set("records", values);
        return event;
    }

    private ObjectNode dateRecord() {
        ObjectNode record = objectMapper.createObjectNode();
        record.put("day", "1");
        record.put("month", "JAN");
        record.put("year", "2025");
        record.putNull("time");
        return record;
    }

    private ObjectNode tstepEvent() {
        return tstepEvent(1);
    }

    private ObjectNode tstepEvent(int steps) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("kind", "TSTEP");
        ArrayNode values = objectMapper.createArrayNode();
        for (int index = 0; index < steps; index++) values.add("1");
        event.set("steps", values);
        return event;
    }

    private ArrayNode strings(int count, String value) {
        ArrayNode result = objectMapper.createArrayNode();
        for (int index = 0; index < count; index++) result.add(value);
        return result;
    }

    private String validate(JsonNode value) {
        return EclipseDataInspectionValidator.validateAndSerialize(value, objectMapper);
    }

    private void assertInvalid(JsonNode value) {
        assertThatThrownBy(() -> validate(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
