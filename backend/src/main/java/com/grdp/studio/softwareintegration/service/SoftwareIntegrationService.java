package com.grdp.studio.softwareintegration.service;

import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectDetailResponse;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectRequest;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectResponse;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationArchiveInspectionResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface SoftwareIntegrationService {
    List<SoftwareIntegrationProjectResponse> listProjects();
    List<SoftwareIntegrationProjectResponse> listDeletedProjects();
    SoftwareIntegrationProjectDetailResponse getProject(long projectId);
    SoftwareIntegrationProjectResponse createProject(SoftwareIntegrationProjectRequest request);
    SoftwareIntegrationProjectResponse updateProject(long projectId, SoftwareIntegrationProjectRequest request);
    void deleteProject(long projectId);
    void deleteModel(long projectId, long modelId);
    SoftwareIntegrationProjectDetailResponse restoreProject(long projectId);
    default SoftwareIntegrationProjectDetailResponse uploadModel(long projectId, MultipartFile file) {
        return uploadModel(projectId, file, null);
    }
    SoftwareIntegrationProjectDetailResponse uploadModel(long projectId, MultipartFile file, String mainFile);
    SoftwareIntegrationArchiveInspectionResponse inspectModelArchive(long projectId, MultipartFile file);
    SoftwareIntegrationProjectDetailResponse revalidateModel(long projectId, long versionId);
}
