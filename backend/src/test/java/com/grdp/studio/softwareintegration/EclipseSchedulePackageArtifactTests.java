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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EclipseSchedulePackageArtifactTests {
    @TempDir Path root;

    @Test
    void publishesApprovedScheduleZipWithExactBytesAndManifest() throws Exception {
        var artifact = archive("eclipse-schedule-applied-package.zip", "application/zip");
        var result = publisher().publish(42, List.of(artifact, manifest(artifact)), true);
        assertThat(result.artifacts()).hasSize(2);
        assertThat(Files.readAllBytes(root.resolve("artifacts/42/eclipse-schedule-applied-package.zip")))
                .isEqualTo(Files.readAllBytes(root.resolve(artifact.storageKey())));
    }

    @Test
    void unknownZipNameAndIncorrectMediaTypeStayRejected() throws Exception {
        for (var artifact : List.of(archive("arbitrary.zip", "application/zip"),
                archive("eclipse-schedule-applied-package.zip", "application/octet-stream"))) {
            assertThatThrownBy(() -> publisher().publish(42, List.of(artifact), true))
                    .hasMessageContaining("sensitive content");
            assertThat(root.resolve("artifacts/42")).doesNotExist();
        }
    }

    @Test
    void mismatchedChecksumNeverPublishesSchedulePackage() throws Exception {
        var valid = archive("eclipse-schedule-applied-package.zip", "application/zip");
        var invalid = new WorkerRunArtifact(valid.storageKey(), valid.size(), "0".repeat(64), valid.contentType());
        assertThatThrownBy(() -> publisher().publish(42, List.of(invalid, manifest(invalid)), true))
                .hasMessageContaining("checksum");
        assertThat(root.resolve("artifacts/42")).doesNotExist();
    }

    private SoftwareIntegrationArtifactPublisher publisher() {
        var properties = new SoftwareIntegrationProperties();
        properties.setStorageRoot(root.toString());
        return new SoftwareIntegrationArtifactPublisher(new SoftwareIntegrationStorageKeyNormalizer(properties), properties, new ObjectMapper());
    }

    private WorkerRunArtifact archive(String name, String mediaType) throws Exception {
        var path = root.resolve("jobs/42/output/" + name);
        Files.createDirectories(path.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("CASE.DATA"));
            zip.write("SCHEDULE\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return descriptor(path, mediaType);
    }

    private WorkerRunArtifact manifest(WorkerRunArtifact artifact) throws Exception {
        var path = root.resolve("jobs/42/output/manifest.json");
        Files.writeString(path, """
                {"schemaVersion":"grdp-worker-artifact-manifest/1","runId":42,
                 "generatedAtUtc":"2026-10-09T00:00:00Z","files":[
                 {"storageKey":"%s","size":%d,"sha256":"%s","contentType":"%s"}]}
                """.formatted(artifact.storageKey(), artifact.size(), artifact.sha256(), artifact.contentType()));
        return descriptor(path, "application/json");
    }

    private WorkerRunArtifact descriptor(Path path, String mediaType) throws Exception {
        return new WorkerRunArtifact(root.relativize(path).toString().replace('\\', '/'), Files.size(path),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))), mediaType);
    }
}
