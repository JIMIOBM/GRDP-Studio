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

class SoftwareIntegrationStorageArtifactTests {
    @TempDir
    Path root;

    @Test
    void normalizesControlledAbsoluteKeysAndRejectsEscape() throws Exception {
        SoftwareIntegrationStorageKeyNormalizer normalizer = normalizer();
        Path model = root.resolve("models/1/1/model.pips");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "model");
        assertThat(normalizer.normalizeStoredKey(model.toAbsolutePath().toString())).isEqualTo("models/1/1/model.pips");
        assertThatThrownBy(() -> normalizer.normalizeStoredKey(root.resolve("../outside.pips").toAbsolutePath().toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> normalizer.normalizeRelative("../escape"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishesOnlyHashAndSizeVerifiedRunOutputs() throws Exception {
        SoftwareIntegrationProperties properties = properties();
        SoftwareIntegrationStorageKeyNormalizer normalizer = new SoftwareIntegrationStorageKeyNormalizer(properties);
        SoftwareIntegrationArtifactPublisher publisher = new SoftwareIntegrationArtifactPublisher(normalizer, properties, new ObjectMapper());
        Path source = root.resolve("jobs/7/output/raw.json");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "real-result");
        String sha = sha256(source);
        Path manifest = source.getParent().resolve("manifest.json");
        Files.writeString(manifest, manifest(7, "jobs/7/output/raw.json", Files.size(source), sha));
        var published = publisher.publish(7, List.of(
                new WorkerRunArtifact("jobs/7/output/raw.json", Files.size(source), sha, "application/json"),
                new WorkerRunArtifact("jobs/7/output/manifest.json", Files.size(manifest), sha256(manifest), "application/json")));
        assertThat(Files.readString(root.resolve("artifacts/7/raw.json"))).isEqualTo("real-result");
        assertThat(published.artifacts().stream().filter(item -> item.name().equals("raw.json"))).singleElement().satisfies(item -> {
            assertThat(item.storageKey()).isEqualTo("artifacts/7/raw.json");
            assertThat(item.sha256()).isEqualTo(sha);
        });
    }

    @Test
    void rejectsArtifactPathAndHashMismatch() throws Exception {
        SoftwareIntegrationProperties properties = properties();
        SoftwareIntegrationArtifactPublisher publisher = new SoftwareIntegrationArtifactPublisher(
                new SoftwareIntegrationStorageKeyNormalizer(properties), properties, new ObjectMapper());
        Path source = root.resolve("jobs/8/output/raw.json");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "result");
        assertThatThrownBy(() -> publisher.publish(8, List.of(new WorkerRunArtifact(
                "models/1/model.pips", 6, "0".repeat(64), null))))
                .isInstanceOf(SoftwareIntegrationArtifactPublisher.ArtifactPublicationException.class);
        assertThatThrownBy(() -> publisher.publish(8, List.of(new WorkerRunArtifact(
                "jobs/8/output/raw.json", Files.size(source), "0".repeat(64), null))))
                .isInstanceOf(SoftwareIntegrationArtifactPublisher.ArtifactPublicationException.class);
    }

    @Test
    void rejectsArbitraryIncompleteExtraWrongHashAndWrongRunManifest() throws Exception {
        assertManifestRejected(20, "{}");
        assertManifestRejected(21, manifest(999, "jobs/21/output/raw.json", 6, "placeholder"));
        assertManifestRejected(22, """
                {"schemaVersion":"grdp-worker-artifact-manifest/1","runId":22,"generatedAtUtc":"2026-08-31T00:00:00Z","files":[]}
                """);
        assertManifestRejected(23, """
                {"schemaVersion":"grdp-worker-artifact-manifest/1","runId":23,"generatedAtUtc":"2026-08-31T00:00:00Z","files":[
                  {"storageKey":"jobs/23/output/raw.json","size":6,"sha256":"placeholder","contentType":"application/json"},
                  {"storageKey":"jobs/23/output/extra.json","size":1,"sha256":"placeholder","contentType":"application/json"}]}
                """);
        assertManifestRejected(24, manifest(24, "jobs/24/output/raw.json", 6, "0".repeat(64)));
    }

    private void assertManifestRejected(long runId, String manifestTemplate) throws Exception {
        SoftwareIntegrationProperties properties = properties();
        SoftwareIntegrationArtifactPublisher publisher = new SoftwareIntegrationArtifactPublisher(
                new SoftwareIntegrationStorageKeyNormalizer(properties), properties, new ObjectMapper());
        Path output = root.resolve("jobs/" + runId + "/output");
        Files.createDirectories(output);
        Path raw = output.resolve("raw.json");
        Files.writeString(raw, "result");
        String rawSha = sha256(raw);
        String manifestContent = manifestTemplate.replace("placeholder", rawSha);
        Path manifest = output.resolve("manifest.json");
        Files.writeString(manifest, manifestContent);
        List<WorkerRunArtifact> descriptors = List.of(
                new WorkerRunArtifact("jobs/" + runId + "/output/raw.json", Files.size(raw), rawSha, "application/json"),
                new WorkerRunArtifact("jobs/" + runId + "/output/manifest.json", Files.size(manifest), sha256(manifest), "application/json"));
        assertThatThrownBy(() -> publisher.publish(runId, descriptors))
                .isInstanceOf(SoftwareIntegrationArtifactPublisher.ArtifactPublicationException.class);
    }

    private static String manifest(long runId, String key, long size, String sha) {
        return """
                {"schemaVersion":"grdp-worker-artifact-manifest/1","runId":%d,"generatedAtUtc":"2026-08-31T00:00:00Z","files":[
                  {"storageKey":"%s","size":%d,"sha256":"%s","contentType":"application/json"}]}
                """.formatted(runId, key, size, sha);
    }

    private SoftwareIntegrationStorageKeyNormalizer normalizer() {
        return new SoftwareIntegrationStorageKeyNormalizer(properties());
    }

    private SoftwareIntegrationProperties properties() {
        SoftwareIntegrationProperties properties = new SoftwareIntegrationProperties();
        properties.setStorageRoot(root.toString());
        return properties;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
