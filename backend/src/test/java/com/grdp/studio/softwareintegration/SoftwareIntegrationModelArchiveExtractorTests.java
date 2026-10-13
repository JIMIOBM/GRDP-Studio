package com.grdp.studio.softwareintegration;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationModelArchiveExtractor;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationStorageKeyNormalizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoftwareIntegrationModelArchiveExtractorTests {
    private Path storageRoot;
    private SoftwareIntegrationModelArchiveExtractor extractor;

    @BeforeEach
    void setUp() throws IOException {
        storageRoot = Files.createTempDirectory("grdp-archive-test-");
        SoftwareIntegrationProperties properties = new SoftwareIntegrationProperties();
        properties.setStorageRoot(storageRoot.toString());
        extractor = new SoftwareIntegrationModelArchiveExtractor(properties,
                new SoftwareIntegrationStorageKeyNormalizer(properties));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (storageRoot != null && Files.exists(storageRoot)) {
            try (var paths = Files.walk(storageRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
        }
    }

    @Test
    void extractsOneNestedPipsMainModelAndPreservesRelativeFiles() throws Exception {
        MockMultipartFile archive = zip("bundle.zip", List.of(
                entry("case/model.pips", "PIPESIM"),
                entry("case/include.inc", "INCLUDE"),
                entry("README.txt", "readme")));

        var descriptor = extractor.inspect(archive);
        var extracted = extractor.extract(archive, "models/7/1");

        assertThat(descriptor.mainExtension()).isEqualTo(".pips");
        assertThat(descriptor.entryCount()).isEqualTo(3);
        assertThat(extracted.storageKey()).isEqualTo("models/7/1/case/model.pips");
        assertThat(Files.readString(extracted.path())).isEqualTo("PIPESIM");
        assertThat(Files.readString(storageRoot.resolve("models/7/1/case/include.inc"))).isEqualTo("INCLUDE");
    }

    @Test
    void rejectsTraversalAndRequiresExplicitSelectionForAmbiguousMainModels() throws Exception {
        MockMultipartFile traversal = zip("unsafe.zip", List.of(entry("../escape.pips", "bad")));
        assertThatThrownBy(() -> extractor.inspect(traversal))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ZIP_ENTRY_NAME_INVALID");

        MockMultipartFile ambiguous = zip("ambiguous.zip", List.of(
                entry("a.pips", "one"), entry("b.pips", "two")));
        assertThatCode(() -> extractor.inspect(ambiguous)).doesNotThrowAnyException();
        var descriptor = extractor.inspect(ambiguous);
        assertThat(descriptor.mainCandidates()).extracting(SoftwareIntegrationModelArchiveExtractor.MainCandidate::path)
                .containsExactlyInAnyOrder("a.pips", "b.pips");
        assertThatThrownBy(() -> extractor.extract(ambiguous, "models/7/2"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ZIP_MAIN_SELECTION_REQUIRED");
        var extracted = extractor.extract(ambiguous, "models/7/2", "b.pips");
        assertThat(extracted.storageKey()).isEqualTo("models/7/2/b.pips");
    }

    @Test
    void rejectsExpandedZipBombAndRemovesPartialExtraction() throws Exception {
        SoftwareIntegrationProperties properties = new SoftwareIntegrationProperties();
        properties.setStorageRoot(storageRoot.toString());
        properties.setMaxArchiveExpandedBytes(4);
        extractor = new SoftwareIntegrationModelArchiveExtractor(properties,
                new SoftwareIntegrationStorageKeyNormalizer(properties));
        MockMultipartFile archive = zip("large.zip", List.of(entry("model.pips", "12345")));

        assertThatThrownBy(() -> extractor.extract(archive, "models/8/1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ZIP_EXPANDED_TOO_LARGE");
        assertThat(Files.exists(storageRoot.resolve("models/8/1"))).isFalse();
    }

    private static Entry entry(String name, String content) { return new Entry(name, content.getBytes()); }

    private static MockMultipartFile zip(String name, List<Entry> entries) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (Entry item : entries) {
                    zip.putNextEntry(new ZipEntry(item.name()));
                    zip.write(item.content());
                    zip.closeEntry();
                }
            }
            return new MockMultipartFile("file", name, "application/zip", bytes.toByteArray());
        } catch (IOException exception) { throw new AssertionError(exception); }
    }

    private record Entry(String name, byte[] content) {}
}
