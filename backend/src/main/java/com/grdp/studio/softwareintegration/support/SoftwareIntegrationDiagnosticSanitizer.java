package com.grdp.studio.softwareintegration.support;

import java.util.regex.Pattern;

public final class SoftwareIntegrationDiagnosticSanitizer {
    private static final Pattern LOCAL_PATH = Pattern.compile("(?im)(?<![a-z0-9])[a-z]:[\\\\/].*$");
    private static final Pattern LOCAL_PIPE = Pattern.compile("(?i)net\\.pipe://localhost/pipe/[^\\s'\"]+");

    private SoftwareIntegrationDiagnosticSanitizer() {}

    public static String sanitize(String value) {
        if (value == null) return null;
        String redacted = LOCAL_PIPE.matcher(value).replaceAll("net.pipe://localhost/pipe/[redacted]");
        return LOCAL_PATH.matcher(redacted).replaceAll("[local path]");
    }
}
