package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.artifact.SoftwareIntegrationArtifactPublisher;
import com.grdp.studio.softwareintegration.client.WorkerRunArtifact;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationStorageKeyNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EclipseFormattedArtifactTests {
    @TempDir Path root;

    @Test
    void publishesNumericFormattedSummaryWithManifestAndExactBytes() throws Exception {
        var artifact = write("eclipse-output-CASE.FUNSMRY", " 'PARAMS  '           2 'REAL'\n 1 12.5\n", "application/octet-stream");
        var manifest = write("manifest.json", """
                {"schemaVersion":"grdp-worker-artifact-manifest/1","runId":42,
                 "generatedAtUtc":"2026-10-09T00:00:00Z","files":[
                 {"storageKey":"%s","size":%d,"sha256":"%s","contentType":"application/octet-stream"}]}
                """.formatted(artifact.storageKey(), artifact.size(), artifact.sha256()), "application/json");
        var result = publisher().publish(42, List.of(artifact, manifest), true);
        assertThat(result.artifacts()).hasSize(2);
        assertThat(Files.readAllBytes(root.resolve("artifacts/42/eclipse-output-CASE.FUNSMRY")))
                .isEqualTo(Files.readAllBytes(root.resolve(artifact.storageKey())));
    }

    @Test
    void formattedSpecificationAndRsmStillCannotBePublishedAsRawText() throws Exception {
        for (String name : List.of("eclipse-output-CASE.FSMSPEC", "eclipse-output-CASE.RSM")) {
            var artifact = write(name, "private restart path", "application/octet-stream");
            assertThatThrownBy(() -> publisher().publish(42, List.of(artifact), true))
                    .hasMessageContaining("sensitive content");
            assertThat(root.resolve("artifacts/42")).doesNotExist();
        }
    }

    @Test
    void publishesFormattedGridAndInitialisationFamiliesWithOriginalChecksum() throws Exception {
        var init=write("eclipse-output-CASE.FINIT"," 'PORO    '           1 'REAL'\n .2\n","application/octet-stream");
        var grid=write("eclipse-output-CASE.FEGRID"," 'ACTNUM  '           1 'INTE'\n 1\n","application/octet-stream");
        var manifest=write("manifest.json","""
                {"schemaVersion":"grdp-worker-artifact-manifest/1","runId":42,
                 "generatedAtUtc":"2026-10-13T00:00:00Z","files":[
                 {"storageKey":"%s","size":%d,"sha256":"%s","contentType":"application/octet-stream"},
                 {"storageKey":"%s","size":%d,"sha256":"%s","contentType":"application/octet-stream"}]}
                """.formatted(init.storageKey(),init.size(),init.sha256(),grid.storageKey(),grid.size(),grid.sha256()),"application/json");
        var result=publisher().publish(42,List.of(init,grid,manifest),true);assertThat(result.artifacts()).hasSize(3);
        for(var artifact:List.of(init,grid))assertThat(Files.readAllBytes(root.resolve("artifacts/42/"+Path.of(artifact.storageKey()).getFileName())))
                .isEqualTo(Files.readAllBytes(root.resolve(artifact.storageKey())));
    }

    private SoftwareIntegrationArtifactPublisher publisher() {
        var properties = new SoftwareIntegrationProperties();
        properties.setStorageRoot(root.toString());
        return new SoftwareIntegrationArtifactPublisher(new SoftwareIntegrationStorageKeyNormalizer(properties), properties, new ObjectMapper());
    }

    private WorkerRunArtifact write(String name, String text, String contentType) throws Exception {
        String key = "jobs/42/output/" + name;
        Path file = root.resolve(key);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return new WorkerRunArtifact(key, Files.size(file),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))), contentType);
    }
}
