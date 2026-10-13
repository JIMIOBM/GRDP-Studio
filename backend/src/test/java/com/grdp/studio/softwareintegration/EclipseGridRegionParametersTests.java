package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationArtifactEntity;
import com.grdp.studio.softwareintegration.support.EclipseGridRegionParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EclipseGridRegionParametersTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String request = """
            {"schemaVersion":"eclipse-grid-region-parameters/1","baselineRunId":74,"fieldFile":"CASE.INIT",
             "fieldArtifactSha256":"%s","gridFile":"CASE.EGRID","gridArtifactSha256":"%s","keyword":"PERMX",
             "nx":3,"ny":2,"nz":1,"i1":2,"i2":3,"j1":1,"j2":2,"k1":1,"k2":1,"originalValues":[20,30,50,60],"multiplier":0.5}
            """.formatted("a".repeat(64), "b".repeat(64));
    @Test void strictlyValidatesEveryScalarRangeAndValue() throws Exception {
        assertTrue(EclipseGridRegionParameters.valid(mapper.readTree(request)));
        for (String invalid : List.of(request.replace("\"i2\":3", "\"i2\":4"), request.replace("\"i1\":2", "\"i1\":4"),
                request.replace("\"i1\":2", "\"i1\":1.5"), request.replace("\"baselineRunId\":74", "\"baselineRunId\":\"74\""),
                request.replace("[20,30,50,60]", "[20,30,50]"), request.replace("[20,30,50,60]", "[20,30,50,\"60\"]"),
                request.replace("0.5", "\"0.5\""), request.replace("0.5", "1"), request.replace("PERMX", "PRESSURE")))
            assertFalse(EclipseGridRegionParameters.valid(mapper.readTree(invalid)));
    }
    @Test void porosityChecksAllCellsAndMultipliedBounds() throws Exception {
        String poro = request.replace("PERMX", "PORO").replace("[20,30,50,60]", "[0.1,0.2,0.3,0.4]");
        assertTrue(EclipseGridRegionParameters.valid(mapper.readTree(poro.replace("0.5", "2"))));
        assertFalse(EclipseGridRegionParameters.valid(mapper.readTree(poro.replace("0.4", "0.6").replace("0.5", "2"))));
    }
    @Test void bindsBothStaticInitAndExactGridMaskWithUniqueLiveArtifacts() throws Exception {
        var parameters = mapper.readTree(request);
        var result = mapper.readTree("""
                {"schemaVersion":"eclipse-summary-result/1","resultContract":"VALID_FULL","grid":{"fileName":"CASE.EGRID","nx":3,"ny":2,"nz":1},
                "fieldIndex":{"files":[{"name":"CASE.INIT","fields":[{"keyword":"PERMX","dataType":"REAL","count":6}]},
                 {"name":"CASE.EGRID","fields":[{"keyword":"ACTNUM","dataType":"INTE","count":6}]}]},
                 "outputFiles":[{"name":"CASE.INIT","sha256":"%s"},{"name":"CASE.EGRID","sha256":"%s"}]}
                """.formatted("a".repeat(64), "b".repeat(64)));
        var init = artifact("CASE.INIT", "a");
        var grid = artifact("CASE.EGRID", "b");
        assertTrue(EclipseGridRegionParameters.matchesBaseline(parameters, result, List.of(init, grid)));
        assertFalse(EclipseGridRegionParameters.matchesBaseline(parameters, result, List.of(init)));
        assertFalse(EclipseGridRegionParameters.matchesBaseline(parameters, result, List.of(init, grid, grid)));
        grid.setSha256("c".repeat(64));
        assertFalse(EclipseGridRegionParameters.matchesBaseline(parameters, result, List.of(init, grid)));
    }
    private SoftwareIntegrationArtifactEntity artifact(String file, String hex) {
        var artifact = new SoftwareIntegrationArtifactEntity(); artifact.setArtifactName("eclipse-output-" + file);
        artifact.setContentType("application/octet-stream"); artifact.setSha256(hex.repeat(64)); return artifact;
    }
}
