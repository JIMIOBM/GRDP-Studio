package com.grdp.studio.softwareintegration.service;

import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectDetailResponse;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectRequest;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface SoftwareIntegrationService {
    List<SoftwareIntegrationProjectResponse> listProjects();
    SoftwareIntegrationProjectDetailResponse getProject(long projectId);
    SoftwareIntegrationProjectResponse createProject(SoftwareIntegrationProjectRequest request);
    SoftwareIntegrationProjectResponse updateProject(long projectId, SoftwareIntegrationProjectRequest request);
    void deleteProject(long projectId);
    SoftwareIntegrationProjectDetailResponse uploadModel(long projectId, MultipartFile file);
    SoftwareIntegrationProjectDetailResponse revalidateModel(long projectId, long versionId);
}
