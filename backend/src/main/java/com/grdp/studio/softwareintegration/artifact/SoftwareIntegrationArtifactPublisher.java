package com.grdp.studio.softwareintegration.artifact;

import com.grdp.studio.softwareintegration.client.WorkerRunArtifact;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationStorageKeyNormalizer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class SoftwareIntegrationArtifactPublisher {
    private final SoftwareIntegrationStorageKeyNormalizer normalizer;
    private final SoftwareIntegrationProperties properties;
    private final ObjectMapper objectMapper;

    public SoftwareIntegrationArtifactPublisher(SoftwareIntegrationStorageKeyNormalizer normalizer,
                                                SoftwareIntegrationProperties properties,
                                                ObjectMapper objectMapper) {
        this.normalizer = normalizer;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public PublishedArtifacts publish(long runId, List<WorkerRunArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) return new PublishedArtifacts(List.of(), null, null);
        String requiredPrefix = "jobs/" + runId + "/output/";
        Path artifactRoot = normalizer.resolve("artifacts/directory-placeholder").getParent();
        Path staging = artifactRoot.resolve(".staging-" + runId + "-" + UUID.randomUUID()).normalize();
        Path target = artifactRoot.resolve(Long.toString(runId)).normalize();
        List<PublishedArtifact> published = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Map<String, WorkerRunArtifact> descriptors = new HashMap<>();
        Path manifestSource = null;
        long totalBytes = 0;
        try {
            Files.createDirectories(artifactRoot);
            if (Files.exists(target)) throw new ArtifactPublicationException("Artifact target already exists");
            Files.createDirectory(staging);
            for (WorkerRunArtifact artifact : artifacts) {
                String sourceKey = normalizer.normalizeRelative(required(artifact.storageKey(), "artifact storageKey"));
                if (!sourceKey.startsWith(requiredPrefix)) throw new ArtifactPublicationException("Artifact is outside the run output directory");
                if (descriptors.putIfAbsent(sourceKey, artifact) != null) {
                    throw new ArtifactPublicationException("Duplicate artifact storage key");
                }
                String workerName = sourceKey.substring(requiredPrefix.length());
                String name = normalizer.normalizeRelative("artifact-name-root/" + required(workerName, "artifact name"))
                        .substring("artifact-name-root/".length());
                if (!names.add(name)) throw new ArtifactPublicationException("Duplicate artifact name");
                Path source = normalizer.resolve(sourceKey);
                Path realRoot = normalizer.root().toRealPath();
                Path realSource = source.toRealPath();
                if (!realSource.startsWith(realRoot) || !Files.isRegularFile(realSource)) {
                    throw new ArtifactPublicationException("Artifact source is invalid");
                }
                long actualSize = Files.size(realSource);
                if (artifact.size() < 0 || actualSize != artifact.size()
                        || actualSize > properties.getMaxArtifactBytes()) {
                    throw new ArtifactPublicationException("Artifact size does not match the manifest");
                }
                totalBytes = Math.addExact(totalBytes, actualSize);
                if (totalBytes > properties.getMaxArtifactTotalBytes()) throw new ArtifactPublicationException("Artifact set is too large");
                String actualSha = sha256(realSource);
                if (!actualSha.equalsIgnoreCase(required(artifact.sha256(), "artifact sha256"))) {
                    throw new ArtifactPublicationException("Artifact checksum does not match the manifest");
                }
                if (sourceKey.equals(requiredPrefix + "manifest.json")) manifestSource = realSource;
                Path destination = staging.resolve(name).normalize();
                if (!destination.startsWith(staging)) throw new ArtifactPublicationException("Artifact name escapes staging");
                Files.createDirectories(destination.getParent());
                Files.copy(realSource, destination, StandardCopyOption.COPY_ATTRIBUTES);
                String storageKey = "artifacts/" + runId + "/" + name;
                published.add(new PublishedArtifact(name, artifactType(name), artifact.contentType(),
                        storageKey, actualSize, actualSha, LocalDateTime.now()));
            }
            validateWorkerManifest(runId, requiredPrefix, descriptors, manifestSource);
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new ArtifactPublicationException("Atomic artifact publication is unavailable", exception);
            }
            String manifestKey = published.stream()
                    .filter(item -> item.type().equalsIgnoreCase("manifest") || item.name().equalsIgnoreCase("manifest.json"))
                    .map(PublishedArtifact::storageKey).findFirst().orElse(null);
            return new PublishedArtifacts(List.copyOf(published), manifestKey, target);
        } catch (ArtifactPublicationException exception) {
            deleteTree(staging);
            throw exception;
        } catch (Exception exception) {
            deleteTree(staging);
            throw new ArtifactPublicationException("Artifact publication failed", exception);
        }
    }

    public void discard(PublishedArtifacts artifacts) {
        if (artifacts != null && artifacts.publishedDirectory() != null) deleteTree(artifacts.publishedDirectory());
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new ArtifactPublicationException(name + " is missing");
        return value;
    }

    private static String artifactType(String name) {
        String fileName = name.substring(name.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (fileName) {
            case "manifest.json" -> "manifest";
            case "normalized-result.json" -> "normalized-result";
            case "raw-response.json" -> "raw-response";
            case "request.json" -> "request";
            case "run.log" -> "log";
            default -> "output";
        };
    }

    private void validateWorkerManifest(long runId, String requiredPrefix,
                                        Map<String, WorkerRunArtifact> descriptors, Path manifestSource) throws Exception {
        if (manifestSource == null) throw new ArtifactPublicationException("Worker manifest.json is missing");
        if (Files.size(manifestSource) > 1_048_576L) throw new ArtifactPublicationException("Worker manifest is too large");
        JsonNode manifest;
        try (InputStream input = Files.newInputStream(manifestSource)) {
            manifest = objectMapper.readTree(input);
        }
        if (manifest == null || !manifest.isObject() || manifest.size() != 4
                || !"grdp-worker-artifact-manifest/1".equals(manifest.path("schemaVersion").asText())
                || manifest.path("runId").asLong(-1) != runId
                || !manifest.path("generatedAtUtc").isTextual()
                || !manifest.path("files").isArray()) {
            throw new ArtifactPublicationException("Worker manifest header is invalid");
        }
        try { java.time.OffsetDateTime.parse(manifest.path("generatedAtUtc").asText()); }
        catch (RuntimeException exception) { throw new ArtifactPublicationException("Worker manifest timestamp is invalid"); }

        Set<String> expected = new HashSet<>(descriptors.keySet());
        expected.remove(requiredPrefix + "manifest.json");
        Set<String> seen = new HashSet<>();
        for (JsonNode file : manifest.path("files")) {
            if (!file.isObject() || file.size() != 4 || !file.path("storageKey").isTextual()
                    || !file.path("size").isIntegralNumber() || !file.path("sha256").isTextual()
                    || !file.path("contentType").isTextual()) {
                throw new ArtifactPublicationException("Worker manifest file entry is invalid");
            }
            String key = normalizer.normalizeRelative(file.path("storageKey").asText());
            if (!key.startsWith(requiredPrefix) || key.equals(requiredPrefix + "manifest.json") || !seen.add(key)) {
                throw new ArtifactPublicationException("Worker manifest contains an invalid or duplicate key");
            }
            WorkerRunArtifact descriptor = descriptors.get(key);
            if (descriptor == null || descriptor.size() != file.path("size").asLong(-1)
                    || !required(descriptor.sha256(), "artifact sha256").equalsIgnoreCase(file.path("sha256").asText())
                    || !required(descriptor.contentType(), "artifact contentType").equals(file.path("contentType").asText())) {
                throw new ArtifactPublicationException("Worker manifest does not match artifact descriptors");
            }
        }
        if (!seen.equals(expected)) throw new ArtifactPublicationException("Worker manifest file set is incomplete or has extras");
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    public record PublishedArtifact(String name, String type, String contentType, String storageKey,
                                    long sizeBytes, String sha256, LocalDateTime createdAt) {}
    public record PublishedArtifacts(List<PublishedArtifact> artifacts, String manifestKey, Path publishedDirectory) {}

    public static class ArtifactPublicationException extends RuntimeException {
        public ArtifactPublicationException(String message) { super(message); }
        public ArtifactPublicationException(String message, Throwable cause) { super(message, cause); }
    }
}
