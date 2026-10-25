package com.grdp.studio.softwareintegration.preprocessing;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationStorageKeyNormalizer;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public final class EclipsePreprocessingSources {
    record SourceFile(String originalName, String storedName, long sizeBytes, String sha256) { }
    public record Source(UUID id, long projectId, String templateId, String templateVersion, List<SourceFile> files) { }
    private final SoftwareIntegrationStorageKeyNormalizer storage;
    private final ObjectMapper mapper;

    public EclipsePreprocessingSources(SoftwareIntegrationStorageKeyNormalizer storage, ObjectMapper mapper) {
        this.storage = storage; this.mapper = mapper;
    }

    public Source save(long projectId, JsonNode template, List<MultipartFile> files, byte[] example) {
        UUID id = UUID.randomUUID();
        Path folder = folder(id);
        try {
            Files.createDirectories(folder.getParent()); Files.createDirectory(folder);
            var entries = new ArrayList<SourceFile>();
            if (example != null) {
                Files.write(folder.resolve("teaching-input.json"), example, StandardOpenOption.CREATE_NEW);
                entries.add(new SourceFile("官方算例转换教学数据.json", "teaching-input.json", example.length, EclipsePreprocessingCatalog.sha256(example)));
            } else {
                for (int index = 0; index < files.size(); index++) {
                    var file = files.get(index); byte[] bytes = file.getBytes();
                    String original = file.getOriginalFilename();
                    String stored = "input-" + (index + 1) + (original.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx") ? ".xlsx" : ".csv");
                    Files.write(folder.resolve(stored), bytes, StandardOpenOption.CREATE_NEW);
                    entries.add(new SourceFile(original, stored, bytes.length, EclipsePreprocessingCatalog.sha256(bytes)));
                }
            }
            var source = new Source(id, projectId, template.path("id").asText(), template.path("version").asText(), List.copyOf(entries));
            Files.writeString(folder.resolve("manifest.json"), mapper.writeValueAsString(source), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return source;
        } catch (IOException exception) { throw new BusinessException(500, "导入来源保存失败，未生成模型"); }
    }

    public Source require(UUID id, long projectId, String templateId, String version) {
        if (id == null) throw new BusinessException(400, "请先导入表格或载入填写示例");
        try {
            var source = mapper.readValue(Files.readString(folder(id).resolve("manifest.json")), Source.class);
            if (source.projectId() != projectId || !source.templateId().equals(templateId) || !source.templateVersion().equals(version)) {
                throw new BusinessException(409, "导入来源与当前项目或模板版本不一致");
            }
            for (var file : source.files()) read(source, file);
            return source;
        } catch (IOException exception) { throw new BusinessException(404, "导入来源不存在或已失效，请重新导入"); }
    }

    byte[] read(Source source, SourceFile file) {
        try {
            if (!file.storedName().matches("(?:input-[0-9]+\\.(?:xlsx|csv)|teaching-input\\.json)")) throw new IOException("Invalid source entry");
            Path path = folder(source.id()).resolve(file.storedName());
            if (Files.size(path) != file.sizeBytes() || file.sizeBytes() > 8 * 1024 * 1024) throw new IOException("Invalid source size");
            byte[] bytes = Files.readAllBytes(path);
            if (!EclipsePreprocessingCatalog.sha256(bytes).equals(file.sha256())) throw new IOException("Source checksum changed");
            return bytes;
        } catch (IOException exception) { throw new BusinessException(409, "原始导入文件的大小或SHA-256核对失败"); }
    }

    private Path folder(UUID id) { return storage.resolve("preprocessing/sources/" + id); }
}
