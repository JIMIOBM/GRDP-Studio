package com.grdp.studio.softwareintegration.dto;

import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationProjectEntity;

import java.time.LocalDateTime;

public record SoftwareIntegrationProjectResponse(Long id, String name, String description, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static SoftwareIntegrationProjectResponse from(SoftwareIntegrationProjectEntity entity) {
        return new SoftwareIntegrationProjectResponse(entity.getId(), entity.getName(), entity.getDescription(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
