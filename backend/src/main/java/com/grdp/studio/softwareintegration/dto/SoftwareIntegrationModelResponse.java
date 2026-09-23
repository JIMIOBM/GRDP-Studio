package com.grdp.studio.softwareintegration.dto;

import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;

import java.time.LocalDateTime;
import java.util.List;

public record SoftwareIntegrationModelResponse(
        Long id, String name, String simulatorType, LocalDateTime updatedAt,
        List<SoftwareIntegrationModelVersionResponse> versions
) {
    public static SoftwareIntegrationModelResponse from(SoftwareIntegrationModelEntity model, List<SoftwareIntegrationModelVersionEntity> versions) {
        return new SoftwareIntegrationModelResponse(model.getId(), model.getName(), model.getSimulatorType(), model.getUpdatedAt(), versions.stream().map(SoftwareIntegrationModelVersionResponse::from).toList());
    }
}
