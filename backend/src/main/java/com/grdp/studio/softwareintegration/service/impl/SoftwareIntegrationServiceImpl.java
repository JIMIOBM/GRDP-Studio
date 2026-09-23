package com.grdp.studio.softwareintegration.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationModelResponse;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectDetailResponse;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectRequest;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectResponse;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationArchiveInspectionResponse;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationProjectEntity;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelVersionMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationProjectMapper;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationService;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationValidationDispatcher;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationStorageKeyNormalizer;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationModelArchiveExtractor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class SoftwareIntegrationServiceImpl implements SoftwareIntegrationService {
    private final SoftwareIntegrationProjectMapper projectMapper;
    private final SoftwareIntegrationModelMapper modelMapper;
    private final SoftwareIntegrationModelVersionMapper versionMapper;
    private final SoftwareIntegrationProperties properties;
    private final SoftwareIntegrationValidationDispatcher validationDispatcher;
    private final SoftwareIntegrationStorageKeyNormalizer storageKeyNormalizer;
    private final SoftwareIntegrationModelArchiveExtractor archiveExtractor;
    private final JdbcTemplate jdbcTemplate;

    public SoftwareIntegrationServiceImpl(SoftwareIntegrationProjectMapper projectMapper, SoftwareIntegrationModelMapper modelMapper,
                                           SoftwareIntegrationModelVersionMapper versionMapper, SoftwareIntegrationProperties properties,
                                           SoftwareIntegrationValidationDispatcher validationDispatcher,
                                           SoftwareIntegrationStorageKeyNormalizer storageKeyNormalizer,
                                           JdbcTemplate jdbcTemplate) {
        this.projectMapper = projectMapper;
        this.modelMapper = modelMapper;
        this.versionMapper = versionMapper;
        this.properties = properties;
        this.validationDispatcher = validationDispatcher;
        this.storageKeyNormalizer = storageKeyNormalizer;
        this.archiveExtractor = new SoftwareIntegrationModelArchiveExtractor(properties, storageKeyNormalizer);
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<SoftwareIntegrationProjectResponse> listProjects() {
        return projectMapper.selectList(new LambdaQueryWrapper<SoftwareIntegrationProjectEntity>()
                        .isNull(SoftwareIntegrationProjectEntity::getDeletedAt).orderByAsc(SoftwareIntegrationProjectEntity::getName))
                .stream().map(SoftwareIntegrationProjectResponse::from).toList();
    }

    @Override
    public List<SoftwareIntegrationProjectResponse> listDeletedProjects() {
        return projectMapper.selectList(new LambdaQueryWrapper<SoftwareIntegrationProjectEntity>()
                        .isNotNull(SoftwareIntegrationProjectEntity::getDeletedAt)
                        .orderByDesc(SoftwareIntegrationProjectEntity::getDeletedAt))
                .stream().map(SoftwareIntegrationProjectResponse::from).toList();
    }

    @Override
    public SoftwareIntegrationProjectDetailResponse getProject(long projectId) {
        SoftwareIntegrationProjectEntity project = requireProject(projectId);
        List<SoftwareIntegrationModelResponse> models = modelMapper.selectList(new LambdaQueryWrapper<SoftwareIntegrationModelEntity>()
                        .eq(SoftwareIntegrationModelEntity::getProjectId, projectId).isNull(SoftwareIntegrationModelEntity::getDeletedAt)
                        .orderByAsc(SoftwareIntegrationModelEntity::getName)).stream()
                .map(model -> SoftwareIntegrationModelResponse.from(model, versionMapper.selectList(new LambdaQueryWrapper<SoftwareIntegrationModelVersionEntity>()
                        .eq(SoftwareIntegrationModelVersionEntity::getModelId, model.getId()).orderByDesc(SoftwareIntegrationModelVersionEntity::getVersionNo))))
                .toList();
        return new SoftwareIntegrationProjectDetailResponse(SoftwareIntegrationProjectResponse.from(project), models);
    }

    @Override
    @Transactional
    public SoftwareIntegrationProjectResponse createProject(SoftwareIntegrationProjectRequest request) {
        ensureNameAvailable(request.name(), null);
        LocalDateTime now = LocalDateTime.now();
        SoftwareIntegrationProjectEntity entity = new SoftwareIntegrationProjectEntity();
        entity.setName(request.name().trim()); entity.setDescription(trim(request.description())); entity.setCreatedBy("administrator");
        entity.setCreatedAt(now); entity.setUpdatedAt(now); projectMapper.insert(entity);
        return SoftwareIntegrationProjectResponse.from(entity);
    }

    @Override
    @Transactional
    public SoftwareIntegrationProjectResponse updateProject(long projectId, SoftwareIntegrationProjectRequest request) {
        SoftwareIntegrationProjectEntity entity = requireProject(projectId); ensureNameAvailable(request.name(), projectId);
        entity.setName(request.name().trim()); entity.setDescription(trim(request.description())); entity.setUpdatedAt(LocalDateTime.now()); projectMapper.updateById(entity);
        return SoftwareIntegrationProjectResponse.from(entity);
    }

    @Override
    @Transactional
    public void deleteProject(long projectId) {
        boolean exists = Boolean.TRUE.equals(jdbcTemplate.query(
                "SELECT deleted_at FROM software_integration_project WHERE id = ? FOR UPDATE",
                resultSet -> resultSet.next() && resultSet.getTimestamp(1) == null, projectId));
        if (!exists) throw new BusinessException(404, "软件集成项目不存在");
        Integer active = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM software_integration_run
                WHERE project_id = ? AND status IN ('CLAIMED','PREPARING','RUNNING_NODAL','RUNNING_PROFILE','RUNNING_NETWORK','RUNNING_ECLIPSE','COLLECTING','CANCEL_REQUESTED')
                """, Integer.class, projectId);
        if (active != null && active > 0) throw new BusinessException(409, "项目存在活动运行，不能删除");
        SoftwareIntegrationProjectEntity entity = requireProject(projectId);
        entity.setDeletedAt(LocalDateTime.now()); entity.setUpdatedAt(LocalDateTime.now()); projectMapper.updateById(entity);
    }

    @Override
    @Transactional
    public void deleteModel(long projectId, long modelId) {
        Boolean projectActive = jdbcTemplate.query(
                "SELECT deleted_at FROM software_integration_project WHERE id = ? FOR UPDATE",
                resultSet -> resultSet.next() && resultSet.getTimestamp(1) == null, projectId);
        if (!Boolean.TRUE.equals(projectActive)) throw new BusinessException(404, "软件集成项目不存在");

        SoftwareIntegrationModelEntity model = modelMapper.selectOne(new LambdaQueryWrapper<SoftwareIntegrationModelEntity>()
                .eq(SoftwareIntegrationModelEntity::getId, modelId)
                .eq(SoftwareIntegrationModelEntity::getProjectId, projectId)
                .isNull(SoftwareIntegrationModelEntity::getDeletedAt));
        if (model == null) throw new BusinessException(404, "软件集成模型不存在");

        Integer active = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM software_integration_run
                WHERE model_id = ? AND status IN ('CLAIMED','PREPARING','RUNNING_NODAL','RUNNING_PROFILE','RUNNING_NETWORK','RUNNING_ECLIPSE','COLLECTING','CANCEL_REQUESTED')
                """, Integer.class, modelId);
        if (active != null && active > 0) throw new BusinessException(409, "模型存在活动运行，不能删除");

        List<ModelVersionPath> versions = jdbcTemplate.query("""
                SELECT model_id, version_no, storage_key
                FROM software_integration_model_version
                WHERE model_id = ?
                """, (resultSet, rowNum) -> new ModelVersionPath(
                resultSet.getLong("model_id"), resultSet.getInt("version_no"), resultSet.getString("storage_key")), modelId);
        for (ModelVersionPath version : versions) {
            if (!deleteVersionDirectory(version)) throw new BusinessException(500, "模型文件清理失败，模型未删除");
        }

        List<Long> runIds = jdbcTemplate.queryForList(
                "SELECT id FROM software_integration_run WHERE model_id = ?", Long.class, modelId);
        for (Long runId : runIds) {
            if (!deleteArtifactDirectory(runId)) throw new BusinessException(500, "运行结果文件清理失败，模型未删除");
        }
        jdbcTemplate.update("DELETE FROM software_integration_artifact WHERE run_id IN (SELECT id FROM software_integration_run WHERE model_id = ?)", modelId);
        jdbcTemplate.update("DELETE FROM software_integration_run_event WHERE run_id IN (SELECT id FROM software_integration_run WHERE model_id = ?)", modelId);
        jdbcTemplate.update("DELETE FROM software_integration_run WHERE model_id = ?", modelId);
        jdbcTemplate.update("DELETE FROM software_integration_validation_job WHERE version_id IN (SELECT id FROM software_integration_model_version WHERE model_id = ?)", modelId);
        jdbcTemplate.update("DELETE FROM software_integration_model_version WHERE model_id = ?", modelId);
        modelMapper.deleteById(modelId);
    }

    @Override
    @Transactional
    public SoftwareIntegrationProjectDetailResponse restoreProject(long projectId) {
        SoftwareIntegrationProjectEntity entity = projectMapper.selectById(projectId);
        if (entity == null) throw new BusinessException(404, "软件集成项目不存在");
        if (entity.getDeletedAt() == null) throw new BusinessException(409, "软件集成项目不在回收站");
        LocalDateTime now = LocalDateTime.now();
        projectMapper.update(null, new LambdaUpdateWrapper<SoftwareIntegrationProjectEntity>()
                .eq(SoftwareIntegrationProjectEntity::getId, projectId)
                .isNotNull(SoftwareIntegrationProjectEntity::getDeletedAt)
                .set(SoftwareIntegrationProjectEntity::getDeletedAt, null)
                .set(SoftwareIntegrationProjectEntity::getUpdatedAt, now));
        return getProject(projectId);
    }

    @Override
    public SoftwareIntegrationProjectDetailResponse uploadModel(long projectId, MultipartFile file) {
        return uploadModel(projectId, file, null);
    }

    @Override
    public SoftwareIntegrationArchiveInspectionResponse inspectModelArchive(long projectId, MultipartFile file) {
        requireProject(projectId);
        if (file == null || file.isEmpty()) throw new BusinessException(400, "请选择 ZIP 模型包");
        if (file.getSize() > properties.getMaxUploadBytes()) throw new BusinessException(400, "模型文件超过500MB限制");
        String originalName = file.getOriginalFilename() == null ? "model" : Path.of(file.getOriginalFilename()).getFileName().toString();
        if (!originalName.toLowerCase(Locale.ROOT).endsWith(".zip")) throw new BusinessException(400, "主模型选择预检仅支持 ZIP 模型包");
        return SoftwareIntegrationArchiveInspectionResponse.from(archiveExtractor.inspect(file));
    }

    @Override
    public SoftwareIntegrationProjectDetailResponse uploadModel(long projectId, MultipartFile file, String mainFile) {
        requireProject(projectId);
        if (file == null || file.isEmpty()) throw new BusinessException(400, "请选择模型文件");
        if (file.getSize() > properties.getMaxUploadBytes()) throw new BusinessException(400, "模型文件超过500MB限制");
        String originalName = file.getOriginalFilename() == null ? "model" : Path.of(file.getOriginalFilename()).getFileName().toString();
        String lowerName = originalName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".pips") && !lowerName.endsWith(".zip") && !lowerName.endsWith(".data")) throw new BusinessException(400, "仅支持 .pips、.DATA 或 ZIP 模型包");
        SoftwareIntegrationModelArchiveExtractor.ArchiveDescriptor archive = lowerName.endsWith(".zip")
                ? archiveExtractor.inspect(file) : null;
        if (archive == null && mainFile != null && !mainFile.isBlank()) throw new BusinessException(400, "非 ZIP 模型不接受主模型选择");
        String selectedExtension = archive == null ? null : extensionOf(archive.select(mainFile));
        SoftwareIntegrationModelEntity model = findOrCreateModel(projectId, modelName(originalName),
                archive != null && ".data".equals(selectedExtension) || lowerName.endsWith(".data")
                        ? "ECLIPSE_100" : "PIPESIM_WELL");
        int nextVersion = versionMapper.selectCount(new LambdaQueryWrapper<SoftwareIntegrationModelVersionEntity>().eq(SoftwareIntegrationModelVersionEntity::getModelId, model.getId())).intValue() + 1;
        String versionRootKey = storageKeyNormalizer.normalizeRelative("models/" + model.getId() + "/" + nextVersion);
        Path target;
        String storageKey;
        try {
            if (archive != null) {
                var extracted = archiveExtractor.extract(file, versionRootKey, mainFile);
                storageKey = extracted.storageKey();
                target = extracted.path();
            } else {
                storageKey = storageKeyNormalizer.normalizeRelative(versionRootKey + "/" + originalName);
                target = storageKeyNormalizer.resolve(storageKey);
                Files.createDirectories(target.getParent());
                try (InputStream input = file.getInputStream()) { Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) { throw new BusinessException(500, "模型文件保存失败"); }
        SoftwareIntegrationModelVersionEntity version = new SoftwareIntegrationModelVersionEntity();
        LocalDateTime now = LocalDateTime.now();
        version.setModelId(model.getId()); version.setVersionNo(nextVersion); version.setOriginalName(originalName);
        version.setStorageKey(storageKey); version.setSizeBytes(fileSize(target)); version.setSha256(sha256(target));
        version.setStatus("UPLOADED"); version.setCreatedAt(now); version.setUpdatedAt(now); versionMapper.insert(version);
        validationDispatcher.enqueue(version.getId());
        return getProject(projectId);
    }

    private static String extensionOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".data")) return ".data";
        if (lower.endsWith(".pips")) return ".pips";
        throw new BusinessException(400, "ZIP 主模型扩展名不受支持");
    }

    @Override
    @Transactional
    public SoftwareIntegrationProjectDetailResponse revalidateModel(long projectId, long versionId) {
        requireProject(projectId);
        SoftwareIntegrationModelVersionEntity version = versionMapper.selectById(versionId);
        if (version == null) throw new BusinessException(404, "模型版本不存在");
        SoftwareIntegrationModelEntity model = modelMapper.selectById(version.getModelId());
        if (model == null || !Long.valueOf(projectId).equals(model.getProjectId())) throw new BusinessException(404, "模型版本不存在");
        LocalDateTime now = LocalDateTime.now();
        versionMapper.update(null, new LambdaUpdateWrapper<SoftwareIntegrationModelVersionEntity>()
                .eq(SoftwareIntegrationModelVersionEntity::getId, versionId)
                .set(SoftwareIntegrationModelVersionEntity::getStatus, "UPLOADED")
                .set(SoftwareIntegrationModelVersionEntity::getValidationMessage, null)
                .set(SoftwareIntegrationModelVersionEntity::getStudiesJson, null)
                .set(SoftwareIntegrationModelVersionEntity::getInspectionJson, null)
                .set(SoftwareIntegrationModelVersionEntity::getUpdatedAt, now));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { validationDispatcher.enqueue(versionId); }
        });
        return getProject(projectId);
    }

    private SoftwareIntegrationModelEntity findOrCreateModel(long projectId, String name, String initialSimulatorType) {
        SoftwareIntegrationModelEntity existing = modelMapper.selectOne(new LambdaQueryWrapper<SoftwareIntegrationModelEntity>()
                .eq(SoftwareIntegrationModelEntity::getProjectId, projectId).eq(SoftwareIntegrationModelEntity::getName, name)
                .isNull(SoftwareIntegrationModelEntity::getDeletedAt));
        if (existing != null) {
            boolean existingEclipse = "ECLIPSE_100".equals(existing.getSimulatorType());
            boolean requestedEclipse = "ECLIPSE_100".equals(initialSimulatorType);
            if (existingEclipse != requestedEclipse) {
                throw new BusinessException(409, "同名模型不能混用 PIPESIM 与 ECLIPSE 文件类型");
            }
            return existing;
        }
        LocalDateTime now = LocalDateTime.now(); SoftwareIntegrationModelEntity model = new SoftwareIntegrationModelEntity();
        model.setProjectId(projectId); model.setName(name); model.setSimulatorType(initialSimulatorType); model.setCreatedAt(now); model.setUpdatedAt(now); modelMapper.insert(model); return model;
    }

    private SoftwareIntegrationProjectEntity requireProject(long id) {
        SoftwareIntegrationProjectEntity entity = projectMapper.selectById(id);
        if (entity == null || entity.getDeletedAt() != null) throw new BusinessException(404, "软件集成项目不存在"); return entity;
    }
    private void ensureNameAvailable(String name, Long excludedId) {
        long count = projectMapper.selectCount(new LambdaQueryWrapper<SoftwareIntegrationProjectEntity>().eq(SoftwareIntegrationProjectEntity::getName, name.trim())
                .isNull(SoftwareIntegrationProjectEntity::getDeletedAt).ne(excludedId != null, SoftwareIntegrationProjectEntity::getId, excludedId));
        if (count > 0) throw new BusinessException(409, "项目名称已存在");
    }
    private String trim(String value) { return value == null ? null : value.trim(); }
    private String modelName(String originalName) { return originalName.replaceFirst("(?i)\\.(pips|data|zip)$", ""); }

    private boolean deleteVersionDirectory(ModelVersionPath version) {
        final String storageKey;
        try { storageKey = storageKeyNormalizer.normalizeStoredKey(version.storageKey()); }
        catch (IllegalArgumentException exception) { return false; }
        String prefix = "models/" + version.modelId() + "/" + version.versionNo() + "/";
        if (!storageKey.startsWith(prefix)) return false;
        return deleteTree(storageKeyNormalizer.resolve("models/" + version.modelId() + "/" + version.versionNo()));
    }

    private boolean deleteArtifactDirectory(long runId) {
        return deleteTree(storageKeyNormalizer.resolve("artifacts/" + runId));
    }

    private boolean deleteTree(Path root) {
        try {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return !Files.isSymbolicLink(root);
            Path realRoot = storageKeyNormalizer.root().toRealPath();
            Path realPath = root.toRealPath();
            if (!realPath.startsWith(realRoot) || realPath.equals(realRoot)) return false;
            try (var paths = Files.walk(realPath)) {
                List<Path> all = paths.toList();
                if (all.stream().anyMatch(path -> Files.isSymbolicLink(path)
                        || (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)))) {
                    return false;
                }
                all.stream().sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException exception) { throw new StorageCleanupFailure(exception); }
                });
            }
            return !Files.exists(realPath, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | SecurityException | StorageCleanupFailure exception) { return false; }
    }

    private record ModelVersionPath(long modelId, int versionNo, String storageKey) {}
    private static final class StorageCleanupFailure extends RuntimeException {
        private StorageCleanupFailure(IOException cause) { super(cause); }
    }

    private String sha256(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (Exception exception) { throw new BusinessException(500, "模型校验失败"); }
    }

    private long fileSize(Path file) {
        try { return Files.size(file); }
        catch (IOException exception) { throw new BusinessException(500, "模型校验失败"); }
    }
}
