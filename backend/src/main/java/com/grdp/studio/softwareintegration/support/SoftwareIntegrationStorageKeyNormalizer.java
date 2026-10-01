package com.grdp.studio.softwareintegration.support;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class SoftwareIntegrationStorageKeyNormalizer {
    private final SoftwareIntegrationProperties properties;

    public SoftwareIntegrationStorageKeyNormalizer(SoftwareIntegrationProperties properties) {
        this.properties = properties;
    }

    public String normalizeStoredKey(String storedKey) {
        if (storedKey == null || storedKey.isBlank()) throw new IllegalArgumentException("Storage key is missing");
        Path root = root();
        Path candidate = Path.of(storedKey);
        if (candidate.isAbsolute()) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (!absolute.startsWith(root)) throw new IllegalArgumentException("Absolute storage key is outside the configured root");
            return portable(root.relativize(absolute));
        }
        return normalizeRelative(storedKey);
    }

    public String normalizeRelative(String key) {
        if (key == null || key.isBlank() || key.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid storage key");
        String portableInput = key.replace('\\', '/');
        if (portableInput.startsWith("/") || portableInput.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Storage key must be relative");
        }
        Path root = root();
        Path resolved = root.resolve(portableInput).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) throw new IllegalArgumentException("Storage key escapes the configured root");
        return portable(root.relativize(resolved));
    }

    public Path resolve(String relativeKey) {
        return root().resolve(normalizeRelative(relativeKey)).normalize();
    }

    public Path root() {
        return Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
    }

    private static String portable(Path relative) {
        String value = relative.normalize().toString().replace('\\', '/');
        if (value.isBlank() || value.equals(".") || value.startsWith("../") || value.equals("..")) {
            throw new IllegalArgumentException("Invalid relative storage key");
        }
        return value;
    }
}
