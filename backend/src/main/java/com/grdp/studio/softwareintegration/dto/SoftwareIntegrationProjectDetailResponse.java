package com.grdp.studio.softwareintegration.dto;

import java.util.List;

public record SoftwareIntegrationProjectDetailResponse(SoftwareIntegrationProjectResponse project, List<SoftwareIntegrationModelResponse> models) {}
