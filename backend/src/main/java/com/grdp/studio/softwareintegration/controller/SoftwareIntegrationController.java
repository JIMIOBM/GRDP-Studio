package com.grdp.studio.softwareintegration.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectDetailResponse;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectRequest;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectResponse;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Validated
@RestController
@RequestMapping("/software-integration")
public class SoftwareIntegrationController {
    private final SoftwareIntegrationService service;
    public SoftwareIntegrationController(SoftwareIntegrationService service) { this.service = service; }

    @GetMapping("/projects")
    public ApiResponse<List<SoftwareIntegrationProjectResponse>> listProjects() { return ApiResponse.success(service.listProjects()); }
    @GetMapping("/projects/{projectId}")
    public ApiResponse<SoftwareIntegrationProjectDetailResponse> getProject(@PathVariable @Min(1) long projectId) { return ApiResponse.success(service.getProject(projectId)); }
    @PostMapping("/projects")
    public ApiResponse<SoftwareIntegrationProjectResponse> createProject(@Valid @RequestBody SoftwareIntegrationProjectRequest request) { return ApiResponse.success(service.createProject(request)); }
    @PutMapping("/projects/{projectId}")
    public ApiResponse<SoftwareIntegrationProjectResponse> updateProject(@PathVariable @Min(1) long projectId, @Valid @RequestBody SoftwareIntegrationProjectRequest request) { return ApiResponse.success(service.updateProject(projectId, request)); }
    @DeleteMapping("/projects/{projectId}")
    public ApiResponse<Void> deleteProject(@PathVariable @Min(1) long projectId) { service.deleteProject(projectId); return ApiResponse.success(); }
    @PostMapping(path = "/projects/{projectId}/models", consumes = "multipart/form-data")
    public ApiResponse<SoftwareIntegrationProjectDetailResponse> uploadModel(@PathVariable @Min(1) long projectId, @RequestPart("file") MultipartFile file) { return ApiResponse.success(service.uploadModel(projectId, file)); }
    @PostMapping("/projects/{projectId}/model-versions/{versionId}/validate")
    public ApiResponse<SoftwareIntegrationProjectDetailResponse> revalidateModel(@PathVariable @Min(1) long projectId, @PathVariable @Min(1) long versionId) { return ApiResponse.success(service.revalidateModel(projectId, versionId)); }
}
