package com.grdp.studio.project.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.project.dto.ProjectResponse;
import com.grdp.studio.project.dto.ProjectSaveRequest;
import com.grdp.studio.project.service.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/project")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping("/page")
    public ApiResponse<IPage<ProjectResponse>> page(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) long size,
            @RequestParam(required = false) String keyword
    ) {
        return ApiResponse.success(projectService.pageProjects(page, size, keyword));
    }

    @GetMapping("/tree")
    public ApiResponse<List<ProjectResponse>> tree() {
        return ApiResponse.success(projectService.listTree());
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectResponse> detail(@PathVariable @Min(1) long id) {
        return ApiResponse.success(projectService.getProject(id));
    }

    @PostMapping
    public ApiResponse<ProjectResponse> create(@Valid @RequestBody ProjectSaveRequest request) {
        return ApiResponse.success(projectService.createProject(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProjectResponse> update(
            @PathVariable @Min(1) long id,
            @Valid @RequestBody ProjectSaveRequest request
    ) {
        return ApiResponse.success(projectService.updateProject(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @Min(1) long id) {
        projectService.deleteProject(id);
        return ApiResponse.success();
    }
}
