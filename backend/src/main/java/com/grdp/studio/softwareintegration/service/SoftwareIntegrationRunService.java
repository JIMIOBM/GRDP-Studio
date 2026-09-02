package com.grdp.studio.softwareintegration.service;

import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationCreateRunRequest;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationRunDetailResponse;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationRunSummaryResponse;
import org.springframework.http.HttpStatus;

import java.util.List;

public interface SoftwareIntegrationRunService {
    SoftwareIntegrationRunSummaryResponse create(long versionId, SoftwareIntegrationCreateRunRequest request);
    SoftwareIntegrationRunDetailResponse get(long runId);
    List<SoftwareIntegrationRunSummaryResponse> list(long versionId, int limit);
    CancelResult cancel(long runId);

    record CancelResult(HttpStatus httpStatus, SoftwareIntegrationRunSummaryResponse run) {}
}
