package com.grdp.studio.project.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.project.dto.ProjectResponse;
import com.grdp.studio.project.dto.ProjectSaveRequest;
import com.grdp.studio.project.entity.ProjectEntity;
import com.grdp.studio.project.mapper.ProjectMapper;
import com.grdp.studio.project.service.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class ProjectServiceImpl
        extends ServiceImpl<ProjectMapper, ProjectEntity>
        implements ProjectService {

    @Override
    public IPage<ProjectResponse> pageProjects(long page, long size, String keyword) {
        LambdaQueryWrapper<ProjectEntity> query = new LambdaQueryWrapper<ProjectEntity>()
                .like(StringUtils.hasText(keyword), ProjectEntity::getName, keyword)
                .orderByDesc(ProjectEntity::getUpdatedAt);

        return page(new Page<ProjectEntity>(page, size), query).convert(ProjectResponse::from);
    }

    @Override
    public List<ProjectResponse> listTree() {
        return list(new LambdaQueryWrapper<ProjectEntity>()
                .orderByAsc(ProjectEntity::getName))
                .stream()
                .map(ProjectResponse::from)
                .toList();
    }

    @Override
    public ProjectResponse getProject(long id) {
        return ProjectResponse.from(requireProject(id));
    }

    @Override
    @Transactional
    public ProjectResponse createProject(ProjectSaveRequest request) {
        ensureUniqueName(request.name(), null);
        ProjectEntity entity = new ProjectEntity();
        applyRequest(entity, request);
        save(entity);
        return ProjectResponse.from(entity);
    }

    @Override
    @Transactional
    public ProjectResponse updateProject(long id, ProjectSaveRequest request) {
        ProjectEntity entity = requireProject(id);
        ensureUniqueName(request.name(), id);
        applyRequest(entity, request);
        updateById(entity);
        return ProjectResponse.from(entity);
    }

    @Override
    @Transactional
    public void deleteProject(long id) {
        requireProject(id);
        removeById(id);
    }

    private ProjectEntity requireProject(long id) {
        ProjectEntity entity = getById(id);
        if (entity == null) {
            throw new BusinessException(404, "项目不存在");
        }
        return entity;
    }

    private void ensureUniqueName(String name, Long excludedId) {
        LambdaQueryWrapper<ProjectEntity> query = new LambdaQueryWrapper<ProjectEntity>()
                .eq(ProjectEntity::getName, name)
                .ne(excludedId != null, ProjectEntity::getId, excludedId);
        if (count(query) > 0) {
            throw new BusinessException(409, "项目名称已存在");
        }
    }

    private void applyRequest(ProjectEntity entity, ProjectSaveRequest request) {
        entity.setName(request.name().trim());
        entity.setDescription(
                request.description() == null ? null : request.description().trim()
        );
    }
}
