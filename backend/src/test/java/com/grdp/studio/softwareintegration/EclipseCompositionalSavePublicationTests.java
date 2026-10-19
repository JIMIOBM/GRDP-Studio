package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.artifact.SoftwareIntegrationArtifactPublisher;
import com.grdp.studio.softwareintegration.client.WorkerRunArtifact;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationStorageKeyNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class EclipseCompositionalSavePublicationTests {
    @TempDir Path root;
    private final JsonMapper mapper = new JsonMapper();
    private ObjectNode load(String name) { return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/" + name + ".json")); }
    private SoftwareIntegrationArtifactPublisher publisher(Path folder) {
        var props = new SoftwareIntegrationProperties(); props.setStorageRoot(folder.toString());
        return new SoftwareIntegrationArtifactPublisher(new SoftwareIntegrationStorageKeyNormalizer(props), props, mapper);
    }
    private WorkerRunArtifact write(Path folder, String name, byte[] bytes, String type) throws Exception {
        var key = "jobs/42/output/" + name; var path = folder.resolve(key); Files.createDirectories(path.getParent()); Files.write(path, bytes);
        return new WorkerRunArtifact(key, bytes.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), type);
    }
    private List<WorkerRunArtifact> prepare(Path folder, ObjectNode c, ObjectNode r, String fault) throws Exception {
        var items = new ArrayList<WorkerRunArtifact>();
        for (int j = 0; j < c.path("files").size(); j++) {
            if ("missing-state".equals(fault) && j == 1) continue;
            var file = c.path("files").get(j); var name = file.path("name").asText();
            try (var input = getClass().getResourceAsStream("/eclipse-compositional-save-files/" + name)) {
                var publishedName = file.path("artifactName").asText();
                if ("bad-extension".equals(fault) && j == 1) publishedName += "0";
                items.add(write(folder, publishedName, input.readAllBytes(), "application/octet-stream"));
            }
        }
        if (!"missing-catalog".equals(fault)) items.add(write(folder, "eclipse-compositional-save-catalog.json", mapper.writeValueAsBytes(c), "application/json"));
        items.add(write(folder, "normalized-result.json", mapper.writeValueAsBytes(r), "application/json"));
        var manifest = mapper.createObjectNode().put("schemaVersion", "grdp-worker-artifact-manifest/1").put("runId", 42).put("generatedAtUtc", "2026-10-17T00:00:00Z");
        var files = manifest.putArray("files");
        for (var a : items) files.addObject().put("storageKey", a.storageKey()).put("size", a.size()).put("sha256", a.sha256()).put("contentType", a.contentType());
        items.add(write(folder, "manifest.json", mapper.writeValueAsBytes(manifest), "application/json")); return items;
    }
    @Test void completeNativeSaveStateIsPublishedWithExactChecksumsAndAllThreeRequiredFiles() throws Exception {
        var items = prepare(root, load("eclipse-compositional-save-catalog"), load("eclipse-compositional-save-native-result"), "none");
        var published = publisher(root).publish(42, items, true); assertThat(published.artifacts()).hasSize(6);
        for (var item : items) assertThat(Files.readAllBytes(root.resolve("artifacts/42/" + Path.of(item.storageKey()).getFileName())))
            .isEqualTo(Files.readAllBytes(root.resolve(item.storageKey())));
    }
    @Test void orphanSaveCatalogMissingStateAndInvalidExtensionNeverPublishPartialSets() throws Exception {
        for (var fault : List.of("missing-state", "missing-catalog", "bad-extension")) {
            var folder = root.resolve(fault); var items = prepare(folder, load("eclipse-compositional-save-catalog"), load("eclipse-compositional-save-native-result"), fault);
            assertThatThrownBy(() -> publisher(folder).publish(42, items, true)).isInstanceOf(SoftwareIntegrationArtifactPublisher.ArtifactPublicationException.class);
            assertThat(folder.resolve("artifacts/42")).doesNotExist();
        }
    }
    @Test void catalogHashMismatchOrForeignResultClockCannotPublishOpaqueState() throws Exception {
        for (var fault : List.of("catalog-hash", "result-clock", "e100")) {
            var folder = root.resolve(fault); var c = load("eclipse-compositional-save-catalog"); var r = load("eclipse-compositional-save-native-result");
            if ("catalog-hash".equals(fault)) ((ObjectNode)c.path("files").get(1)).put("sha256", "0".repeat(64));
            if ("result-clock".equals(fault)) ((ObjectNode)r.path("summary")).put("startDate", "2000-01-01");
            if ("e100".equals(fault)) r.put("modelKind", "eclipse_100");
            var items = prepare(folder, c, r, "none");
            assertThatThrownBy(() -> publisher(folder).publish(42, items, true)).isInstanceOf(SoftwareIntegrationArtifactPublisher.ArtifactPublicationException.class);
            assertThat(folder.resolve("artifacts/42")).doesNotExist();
        }
    }
}
