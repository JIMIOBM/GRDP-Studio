package com.grdp.studio.softwareintegration.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationArtifactEntity;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationArtifactMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/** Removes expired published artifacts without following paths outside the configured storage root. */
@Component
public class SoftwareIntegrationArtifactCleanup {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoftwareIntegrationArtifactCleanup.class);
    private final SoftwareIntegrationArtifactMapper artifactMapper;
    private final SoftwareIntegrationStorageKeyNormalizer normalizer;

    public SoftwareIntegrationArtifactCleanup(SoftwareIntegrationArtifactMapper artifactMapper,
                                              SoftwareIntegrationStorageKeyNormalizer normalizer) {
        this.artifactMapper = artifactMapper;
        this.normalizer = normalizer;
    }

    @Scheduled(
            fixedDelayString = "${grdp.software-integration.artifact-cleanup-delay:3600000}",
            initialDelayString = "${grdp.software-integration.artifact-cleanup-initial-delay:60000}")
    public void cleanupExpiredArtifacts() {
        LocalDateTime now = LocalDateTime.now();
        List<SoftwareIntegrationArtifactEntity> expired = artifactMapper.selectList(new LambdaQueryWrapper<SoftwareIntegrationArtifactEntity>()
                .isNotNull(SoftwareIntegrationArtifactEntity::getExpiresAt)
                .le(SoftwareIntegrationArtifactEntity::getExpiresAt, now)
                .orderByAsc(SoftwareIntegrationArtifactEntity::getId));
        for (SoftwareIntegrationArtifactEntity artifact : expired) {
            try {
                if (deleteStorage(artifact)) artifactMapper.deleteById(artifact.getId());
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to clean expired software-integration artifact {}", artifact.getId(), exception);
            }
        }
    }

    private boolean deleteStorage(SoftwareIntegrationArtifactEntity artifact) {
        if (artifact.getRunId() == null || artifact.getId() == null) return false;
        final String key;
        try {
            key = normalizer.normalizeRelative(artifact.getStorageKey());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        String prefix = "artifacts/" + artifact.getRunId() + "/";
        if (!key.startsWith(prefix)) return false;
        Path path = normalizer.resolve(key);
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true;
            if (Files.isSymbolicLink(path)) {
                Files.deleteIfExists(path);
                return true;
            }
            Path realRoot = normalizer.root().toRealPath();
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot) || !Files.isRegularFile(realPath, LinkOption.NOFOLLOW_LINKS)) return false;
            Files.deleteIfExists(realPath);
            removeEmptyArtifactDirectory(realPath.getParent(), realRoot);
            return !Files.exists(realPath, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | SecurityException exception) {
            return false;
        }
    }

    private void removeEmptyArtifactDirectory(Path directory, Path realRoot) throws IOException {
        if (directory == null || !directory.startsWith(realRoot) || !directory.getFileName().toString().matches("\\d+")) return;
        try (var children = Files.list(directory)) {
            if (children.findAny().isEmpty()) Files.deleteIfExists(directory);
        }
    }
}
