package com.grdp.studio.softwareintegration.service;

import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationCreateRunRequest;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationRunDetailResponse;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationRunSummaryResponse;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;
import java.util.List;

public interface SoftwareIntegrationRunService {
    SoftwareIntegrationRunSummaryResponse create(long versionId, SoftwareIntegrationCreateRunRequest request);
    SoftwareIntegrationRunDetailResponse get(long runId);
    ArtifactDownload downloadArtifact(long runId, long artifactId);
    ArtifactRangeDownload downloadArtifactRange(long runId, long artifactId, long offset, long length);
    List<SoftwareIntegrationRunSummaryResponse> list(long versionId, int limit);
    CancelResult cancel(long runId);
    SoftwareIntegrationRunSummaryResponse retry(long runId);

    record ArtifactDownload(Path path, String name, String contentType, long sizeBytes) {}
    record ArtifactRangeDownload(Path path, String name, String contentType, long offset, long length, long sizeBytes) {}
    record CancelResult(HttpStatus httpStatus, SoftwareIntegrationRunSummaryResponse run) {}
}
