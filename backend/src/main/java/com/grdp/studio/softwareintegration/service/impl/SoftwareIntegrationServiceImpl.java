package com.grdp.studio.softwareintegration.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationModelResponse;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectDetailResponse;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectRequest;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectResponse;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
public class SoftwareIntegrationServiceImpl implements SoftwareIntegrationService {
    private final SoftwareIntegrationProjectMapper projectMapper;
    private final SoftwareIntegrationModelMapper modelMapper;
    private final SoftwareIntegrationModelVersionMapper versionMapper;
    private final SoftwareIntegrationProperties properties;
    private final SoftwareIntegrationValidationDispatcher validationDispatcher;
    private final SoftwareIntegrationStorageKeyNormalizer storageKeyNormalizer;
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
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<SoftwareIntegrationProjectResponse> listProjects() {
        return projectMapper.selectList(new LambdaQueryWrapper<SoftwareIntegrationProjectEntity>()
                        .isNull(SoftwareIntegrationProjectEntity::getDeletedAt).orderByAsc(SoftwareIntegrationProjectEntity::getName))
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
                WHERE project_id = ? AND status IN ('CLAIMED','PREPARING','RUNNING_NODAL','RUNNING_PROFILE','COLLECTING','CANCEL_REQUESTED')
                """, Integer.class, projectId);
        if (active != null && active > 0) throw new BusinessException(409, "项目存在活动运行，不能删除");
        SoftwareIntegrationProjectEntity entity = requireProject(projectId);
        entity.setDeletedAt(LocalDateTime.now()); entity.setUpdatedAt(LocalDateTime.now()); projectMapper.updateById(entity);
    }

    @Override
    public SoftwareIntegrationProjectDetailResponse uploadModel(long projectId, MultipartFile file) {
        requireProject(projectId);
        if (file == null || file.isEmpty()) throw new BusinessException(400, "请选择模型文件");
        if (file.getSize() > properties.getMaxUploadBytes()) throw new BusinessException(400, "模型文件超过500MB限制");
        String originalName = file.getOriginalFilename() == null ? "model" : Path.of(file.getOriginalFilename()).getFileName().toString();
        String lowerName = originalName.toLowerCase();
        if (!lowerName.endsWith(".pips") && !lowerName.endsWith(".zip")) throw new BusinessException(400, "仅支持 .pips 或 ZIP 模型包");

        SoftwareIntegrationModelEntity model = findOrCreateModel(projectId, modelName(originalName));
        int nextVersion = versionMapper.selectCount(new LambdaQueryWrapper<SoftwareIntegrationModelVersionEntity>().eq(SoftwareIntegrationModelVersionEntity::getModelId, model.getId())).intValue() + 1;
        String storageKey = storageKeyNormalizer.normalizeRelative("models/" + model.getId() + "/" + nextVersion + "/" + originalName);
        Path target = storageKeyNormalizer.resolve(storageKey);
        Path directory = target.getParent();
        try {
            Files.createDirectories(directory);
            try (InputStream input = file.getInputStream()) { Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException exception) { throw new BusinessException(500, "模型文件保存失败"); }
        SoftwareIntegrationModelVersionEntity version = new SoftwareIntegrationModelVersionEntity();
        LocalDateTime now = LocalDateTime.now();
        version.setModelId(model.getId()); version.setVersionNo(nextVersion); version.setOriginalName(originalName);
        version.setStorageKey(storageKey); version.setSizeBytes(file.getSize()); version.setSha256(sha256(target));
        version.setStatus("UPLOADED"); version.setCreatedAt(now); version.setUpdatedAt(now); versionMapper.insert(version);
        validationDispatcher.validate(version.getId());
        return getProject(projectId);
    }

    @Override
    @Transactional
    public SoftwareIntegrationProjectDetailResponse revalidateModel(long projectId, long versionId) {
        requireProject(projectId);
        SoftwareIntegrationModelVersionEntity version = versionMapper.selectById(versionId);
        if (version == null) throw new BusinessException(404, "模型版本不存在");
        SoftwareIntegrationModelEntity model = modelMapper.selectById(version.getModelId());
        if (model == null || !Long.valueOf(projectId).equals(model.getProjectId())) throw new BusinessException(404, "模型版本不存在");
        version.setStatus("UPLOADED"); version.setValidationMessage(null); version.setStudiesJson(null); version.setUpdatedAt(LocalDateTime.now()); versionMapper.updateById(version);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { validationDispatcher.validate(versionId); }
        });
        return getProject(projectId);
    }

    private SoftwareIntegrationModelEntity findOrCreateModel(long projectId, String name) {
        SoftwareIntegrationModelEntity existing = modelMapper.selectOne(new LambdaQueryWrapper<SoftwareIntegrationModelEntity>()
                .eq(SoftwareIntegrationModelEntity::getProjectId, projectId).eq(SoftwareIntegrationModelEntity::getName, name)
                .isNull(SoftwareIntegrationModelEntity::getDeletedAt));
        if (existing != null) return existing;
        LocalDateTime now = LocalDateTime.now(); SoftwareIntegrationModelEntity model = new SoftwareIntegrationModelEntity();
        model.setProjectId(projectId); model.setName(name); model.setSimulatorType("PIPESIM_WELL"); model.setCreatedAt(now); model.setUpdatedAt(now); modelMapper.insert(model); return model;
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
    private String modelName(String originalName) { return originalName.replaceFirst("(?i)\\.(pips|zip)$", ""); }
    private String sha256(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (Exception exception) { throw new BusinessException(500, "模型校验失败"); }
    }
}
