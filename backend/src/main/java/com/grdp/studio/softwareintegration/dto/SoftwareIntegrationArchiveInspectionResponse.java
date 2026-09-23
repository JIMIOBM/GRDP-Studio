package com.grdp.studio.softwareintegration.dto;

import com.grdp.studio.softwareintegration.support.SoftwareIntegrationModelArchiveExtractor;

import java.util.List;

/** A safe preflight response used to choose the explicit simulator entry in a multi-case ZIP. */
public record SoftwareIntegrationArchiveInspectionResponse(
        String schemaVersion,
        int entryCount,
        List<MainCandidate> mainCandidates,
        boolean selectionRequired
) {
    public static SoftwareIntegrationArchiveInspectionResponse from(SoftwareIntegrationModelArchiveExtractor.ArchiveDescriptor descriptor) {
        List<MainCandidate> candidates = descriptor.mainCandidates().stream()
                .map(candidate -> new MainCandidate(candidate.path(), candidate.extension()))
                .toList();
        return new SoftwareIntegrationArchiveInspectionResponse(
                "software-integration-archive-inspection/1",
                descriptor.entryCount(),
                candidates,
                candidates.size() > 1);
    }

    public record MainCandidate(String path, String extension) {}
}
