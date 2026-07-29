package com.grdp.studio.project.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import com.grdp.studio.project.dto.ProjectResponse;
import com.grdp.studio.project.dto.ProjectSaveRequest;
import com.grdp.studio.project.entity.ProjectEntity;

import java.util.List;

public interface ProjectService extends IService<ProjectEntity> {

    IPage<ProjectResponse> pageProjects(long page, long size, String keyword);

    List<ProjectResponse> listTree();

    ProjectResponse getProject(long id);

    ProjectResponse createProject(ProjectSaveRequest request);

    ProjectResponse updateProject(long id, ProjectSaveRequest request);

    void deleteProject(long id);
}
