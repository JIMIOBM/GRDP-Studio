package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationArtifactEntity;
import com.grdp.studio.softwareintegration.support.EclipseGridFieldParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EclipseGridFieldParametersTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String SHA = "a".repeat(64);

    @Test
    void acceptsOnlyBoundedSingleCellPermxMultipliers() throws Exception {
        var parameters = mapper.readTree("""
                {"schemaVersion":"eclipse-grid-field-parameters/1","baselineRunId":74,
                 "fieldFile":"BRILLIG.INIT","fieldArtifactSha256":"%s","nx":10,"ny":8,"nz":3,
                 "i":10,"j":8,"k":3,"originalValue":2.5,"multiplier":0.5}
                """.formatted(SHA));
        assertTrue(EclipseGridFieldParameters.valid(parameters));
        assertFalse(EclipseGridFieldParameters.valid(mapper.readTree("""
                {"schemaVersion":"eclipse-grid-field-parameters/1","baselineRunId":74,"fieldFile":"BRILLIG.INIT",
                 "fieldArtifactSha256":"%s","nx":10,"ny":8,"nz":3,"i":11,"j":8,"k":3,
                 "originalValue":2.5,"multiplier":0.5}
                """.formatted(SHA))));
        assertFalse(EclipseGridFieldParameters.valid(mapper.readTree("""
                {"schemaVersion":"eclipse-grid-field-parameters/1","baselineRunId":74,"fieldFile":"BRILLIG.INIT",
                 "fieldArtifactSha256":"%s","nx":10,"ny":8,"nz":3,"i":10,"j":8,"k":3,
                 "originalValue":2.5,"multiplier":1.0}
                """.formatted(SHA))));
    }

    @Test
    void rejectsStringValuesAndMultipliersBeforeQueueing() throws Exception {
        String request = """
                {"schemaVersion":"eclipse-grid-field-parameters/1","baselineRunId":74,
                 "fieldFile":"BRILLIG.INIT","fieldArtifactSha256":"%s","nx":2,"ny":2,"nz":1,
                 "i":2,"j":1,"k":1,"originalValue":2.5,"multiplier":0.5}
                """.formatted(SHA);
        assertFalse(EclipseGridFieldParameters.valid(mapper.readTree(request.replace("2.5", "\"2.5\""))));
        assertFalse(EclipseGridFieldParameters.valid(mapper.readTree(request.replace("0.5", "\"0.5\""))));
    }

    @Test
    void bindsDimensionsFieldIndexAndChecksumToFullBaselineRun() throws Exception {
        var parameters = mapper.readTree("""
                {"schemaVersion":"eclipse-grid-field-parameters/1","baselineRunId":74,
                 "fieldFile":"BRILLIG.INIT","fieldArtifactSha256":"%s","nx":2,"ny":2,"nz":1,
                 "i":2,"j":1,"k":1,"originalValue":2.5,"multiplier":0.5}
                """.formatted(SHA));
        var result = mapper.readTree("""
                {"schemaVersion":"eclipse-summary-result/1","resultContract":"VALID_FULL",
                 "grid":{"nx":2,"ny":2,"nz":1},
                 "fieldIndex":{"files":[{"name":"BRILLIG.INIT","fields":[
                   {"keyword":"PERMX","dataType":"REAL","count":4}]}]},
                 "outputFiles":[{"name":"BRILLIG.INIT","sha256":"%s"}]}
                """.formatted(SHA));
        var artifact = new SoftwareIntegrationArtifactEntity();
        artifact.setArtifactName("eclipse-output-BRILLIG.INIT");
        artifact.setContentType("application/octet-stream");
        artifact.setSha256(SHA);

        assertTrue(EclipseGridFieldParameters.matchesBaseline(parameters, result, List.of(artifact)));
        artifact.setSha256("b".repeat(64));
        assertFalse(EclipseGridFieldParameters.matchesBaseline(parameters, result, List.of(artifact)));
    }
}
