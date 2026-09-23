package com.grdp.studio.softwareintegration.support;

import com.grdp.studio.common.BusinessException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Validates and expands uploaded simulator archives into an isolated model-version directory. */
public final class SoftwareIntegrationModelArchiveExtractor {
    private final SoftwareIntegrationProperties properties;
    private final SoftwareIntegrationStorageKeyNormalizer normalizer;

    public SoftwareIntegrationModelArchiveExtractor(SoftwareIntegrationProperties properties,
                                                    SoftwareIntegrationStorageKeyNormalizer normalizer) {
        this.properties = properties;
        this.normalizer = normalizer;
    }

    public ArchiveDescriptor inspect(MultipartFile file) {
        Set<String> entries = new HashSet<>();
        List<MainCandidate> mainCandidates = new ArrayList<>();
        int entryCount = 0;
        try (InputStream input = file.getInputStream(); ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String normalized = normalizeEntryName(entry.getName());
                if (!entries.add(normalized)) throw invalid("ZIP_ENTRY_DUPLICATE", "ZIP 包包含重复路径");
                entryCount = checkedEntryCount(entryCount);
                if (entry.isDirectory()) continue;
                String extension = supportedMainExtension(normalized);
                if (extension != null) mainCandidates.add(new MainCandidate(normalized, extension));
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("ZIP_INVALID", "ZIP 模型包无法读取");
        }
        if (mainCandidates.isEmpty()) throw invalid("ZIP_MAIN_MISSING", "ZIP 包中未找到 .pips 或 .DATA 主模型");
        return new ArchiveDescriptor(mainCandidates.size() == 1 ? mainCandidates.get(0).extension() : null,
                entryCount, List.copyOf(mainCandidates));
    }

    public ExtractedModel extract(MultipartFile file, String versionRootKey) {
        return extract(file, versionRootKey, null);
    }

    public ExtractedModel extract(MultipartFile file, String versionRootKey, String selectedMainFile) {
        ArchiveDescriptor descriptor = inspect(file);
        String selected = descriptor.select(selectedMainFile);
        String rootKey = normalizer.normalizeRelative(versionRootKey);
        Path root = normalizer.resolve(rootKey);
        Set<String> entries = new HashSet<>();
        String mainKey = null;
        long expandedBytes = 0;
        int entryCount = 0;
        try {
            Files.createDirectories(root);
            ensureNoReparsePoint(root, root);
            try (InputStream input = file.getInputStream(); ZipInputStream zip = new ZipInputStream(input)) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                    String normalized = normalizeEntryName(entry.getName());
                    if (!entries.add(normalized)) throw invalid("ZIP_ENTRY_DUPLICATE", "ZIP 包包含重复路径");
                    entryCount = checkedEntryCount(entryCount);
                    Path target = root.resolve(normalized).normalize();
                    if (!target.startsWith(root) || target.equals(root)) throw invalid("ZIP_PATH_ESCAPE", "ZIP 条目路径越界");
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                        ensureNoReparsePoint(root, parent);
                    }
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw invalid("ZIP_ENTRY_DUPLICATE", "ZIP 包包含重复路径");
                    long entryBytes = 0;
                    try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        byte[] buffer = new byte[8192];
                        for (int read; (read = zip.read(buffer)) != -1; ) {
                            entryBytes = checkedSize(entryBytes, read, properties.getMaxArchiveEntryBytes(), "ZIP_ENTRY_TOO_LARGE");
                            expandedBytes = checkedSize(expandedBytes, read, properties.getMaxArchiveExpandedBytes(), "ZIP_EXPANDED_TOO_LARGE");
                            output.write(buffer, 0, read);
                        }
                    }
                    if (normalized.equals(selected)) {
                        mainKey = rootKey + "/" + normalized;
                    }
                }
            }
            if (mainKey == null) throw invalid("ZIP_MAIN_INVALID", "所选 ZIP 主模型文件未能解包");
            Path mainPath = normalizer.resolve(mainKey);
            Path realRoot = root.toRealPath();
            Path realMain = mainPath.toRealPath();
            if (!realMain.startsWith(realRoot) || !Files.isRegularFile(realMain, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("ZIP_MAIN_INVALID", "ZIP 主模型文件不可用");
            }
            return new ExtractedModel(mainKey, realMain, expandedBytes, entryCount);
        } catch (BusinessException exception) {
            deleteTree(root);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            deleteTree(root);
            throw invalid("ZIP_INVALID", "ZIP 模型包解压失败");
        }
    }

    private int checkedEntryCount(int current) {
        if (current >= properties.getMaxArchiveEntries()) throw invalid("ZIP_ENTRY_COUNT_EXCEEDED", "ZIP 条目数量超过限制");
        return current + 1;
    }

    private static long checkedSize(long current, long increment, long limit, String code) {
        if (increment < 0 || current > limit - increment) throw invalid(code, "ZIP 解压后大小超过限制");
        return current + increment;
    }

    private String normalizeEntryName(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.length() > 1024) {
            throw invalid("ZIP_ENTRY_NAME_INVALID", "ZIP 条目名称无效");
        }
        String portable = name.replace('\\', '/');
        if (portable.startsWith("/") || portable.matches("^[A-Za-z]:.*")) throw invalid("ZIP_PATH_ESCAPE", "ZIP 条目路径越界");
        String[] segments = portable.split("/", -1);
        int depth = 0;
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty()) {
                if (index == segments.length - 1) continue;
                throw invalid("ZIP_ENTRY_NAME_INVALID", "ZIP 条目名称包含空路径段");
            }
            if (segment.equals(".") || segment.equals("..") || segment.indexOf(':') >= 0 ||
                    segment.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
                throw invalid("ZIP_ENTRY_NAME_INVALID", "ZIP 条目名称包含不安全路径段");
            }
            if (normalized.length() > 0) normalized.append('/');
            normalized.append(segment);
            depth++;
        }
        if (depth == 0 || depth > properties.getMaxArchiveDepth()) throw invalid("ZIP_DEPTH_EXCEEDED", "ZIP 目录深度超过限制");
        return normalized.toString();
    }

    private static String supportedMainExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pips")) return ".pips";
        if (lower.endsWith(".data")) return ".data";
        return null;
    }

    private static void ensureNoReparsePoint(Path root, Path candidate) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedRoot)) throw invalid("ZIP_PATH_ESCAPE", "ZIP 条目路径越界");
        for (Path current = normalizedCandidate; current != null && current.startsWith(normalizedRoot); current = current.getParent()) {
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw invalid("ZIP_REPARSE_POINT", "ZIP 解包路径包含符号链接或重解析点");
            if (current.equals(normalizedCandidate)) break;
        }
    }

    private static BusinessException invalid(String code, String message) {
        return new BusinessException(400, code + ": " + message);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    public record ArchiveDescriptor(String mainExtension, int entryCount, List<MainCandidate> mainCandidates) {
        public String select(String requested) {
            if (mainCandidates == null || mainCandidates.isEmpty()) throw invalid("ZIP_MAIN_MISSING", "ZIP 包中未找到 .pips 或 .DATA 主模型");
            String selected = requested == null || requested.isBlank() ? null : requested.replace('\\', '/');
            if (selected == null) {
                if (mainCandidates.size() != 1) throw invalid("ZIP_MAIN_SELECTION_REQUIRED", "ZIP 包包含多个主模型文件，请明确选择主 DATA 或 .pips 文件");
                return mainCandidates.get(0).path();
            }
            if (selected.startsWith("/") || selected.matches("^[A-Za-z]:.*") || selected.contains("../") || selected.equals("..")) {
                throw invalid("ZIP_MAIN_SELECTION_INVALID", "所选 ZIP 主模型路径无效");
            }
            return mainCandidates.stream().map(MainCandidate::path).filter(selected::equals).findFirst()
                    .orElseThrow(() -> invalid("ZIP_MAIN_SELECTION_INVALID", "所选 ZIP 主模型不在候选列表中"));
        }
    }
    public record MainCandidate(String path, String extension) {}
    public record ExtractedModel(String storageKey, Path path, long expandedBytes, int entryCount) {}
}
