package com.grdp.studio.softwareintegration.support;

import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationArtifactEntity;
import tools.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Rectangular static input region with independent INIT and ACTNUM binding. */
public final class EclipseGridRegionParameters {
    public static final String SCHEMA = "eclipse-grid-region-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "fieldFile", "fieldArtifactSha256",
            "gridFile", "gridArtifactSha256", "keyword", "nx", "ny", "nz", "i1", "i2", "j1", "j2", "k1", "k2", "originalValues", "multiplier");
    private EclipseGridRegionParameters() {}
    public static boolean valid(JsonNode value) {
        if (value == null || !value.isObject()) return false;
        var names = new HashSet<String>(); value.properties().forEach(item -> names.add(item.getKey()));
        if (!names.equals(FIELDS) || !SCHEMA.equals(value.path("schemaVersion").asText())
                || !value.path("baselineRunId").isIntegralNumber() || !value.path("baselineRunId").canConvertToLong() || value.path("baselineRunId").asLong() <= 0) return false;
        for (String key : List.of("fieldFile", "gridFile", "fieldArtifactSha256", "gridArtifactSha256", "keyword")) if (!value.path(key).isString()) return false;
        if (!value.path("fieldFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.INIT$")
                || !value.path("gridFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.EGRID$")
                || !value.path("fieldArtifactSha256").asText().matches("^[0-9a-f]{64}$")
                || !value.path("gridArtifactSha256").asText().matches("^[0-9a-f]{64}$")
                || !Set.of("PERMX", "PERMY", "PERMZ", "PORO").contains(value.path("keyword").asText())) return false;
        for (String key : List.of("nx", "ny", "nz", "i1", "i2", "j1", "j2", "k1", "k2")) if (integer(value.path(key)) == 0) return false;
        int nx = value.path("nx").asInt(), ny = value.path("ny").asInt(), nz = value.path("nz").asInt();
        int i1 = value.path("i1").asInt(), i2 = value.path("i2").asInt(), j1 = value.path("j1").asInt(), j2 = value.path("j2").asInt(), k1 = value.path("k1").asInt(), k2 = value.path("k2").asInt();
        if ((long)nx * ny * nz > 2_000_000 || i2 < i1 || j2 < j1 || k2 < k1 || i2 > nx || j2 > ny || k2 > nz) return false;
        long count = (long)(i2 - i1 + 1) * (j2 - j1 + 1) * (k2 - k1 + 1);
        JsonNode originals = value.path("originalValues"), scalar = value.path("multiplier");
        if (count > 4096 || !originals.isArray() || originals.size() != count || !scalar.isNumber()) return false;
        double multiplier = scalar.asDouble(Double.NaN);
        if (!Double.isFinite(multiplier) || multiplier < 0.000001 || multiplier > 100 || multiplier == 1) return false;
        for (JsonNode item : originals) {
            if (!item.isNumber()) return false;
            double original = item.asDouble(Double.NaN), target = original * multiplier;
            if (!Double.isFinite(original) || original <= 0 || !Double.isFinite(target) || target <= 0
                    || "PORO".equals(value.path("keyword").asText()) && (original > 1 || target > 1)) return false;
        }
        return true;
    }

    public static boolean matchesBaseline(JsonNode value, JsonNode result, List<SoftwareIntegrationArtifactEntity> artifacts) {
        if (!valid(value) || result == null || !"eclipse-summary-result/1".equals(result.path("schemaVersion").asText())
                || !"VALID_FULL".equals(result.path("resultContract").asText())) return false;
        for (String axis : List.of("nx", "ny", "nz")) if (result.path("grid").path(axis).asInt(-1) != value.path(axis).asInt()) return false;
        if (!value.path("gridFile").asText().equalsIgnoreCase(result.path("grid").path("fileName").asText())) return false;
        long count = (long)value.path("nx").asInt() * value.path("ny").asInt() * value.path("nz").asInt();
        return bound(value, result, artifacts, "fieldFile", "fieldArtifactSha256", value.path("keyword").asText(), count, Set.of("REAL", "DOUB"))
                && bound(value, result, artifacts, "gridFile", "gridArtifactSha256", "ACTNUM", count, Set.of("INTE"));
    }
    private static boolean bound(JsonNode value, JsonNode result, List<SoftwareIntegrationArtifactEntity> artifacts,
                                 String fileKey, String shaKey, String keyword, long count, Set<String> types) {
        String name = value.path(fileKey).asText(), sha = value.path(shaKey).asText();
        long indexed = 0;
        for (JsonNode file : result.path("fieldIndex").path("files")) if (name.equalsIgnoreCase(file.path("name").asText()))
            for (JsonNode field : file.path("fields")) if (keyword.equals(field.path("keyword").asText()) && field.path("timeStep").isMissingNode()
                    && types.contains(field.path("dataType").asText()) && field.path("count").asLong(-1) == count) indexed++;
        boolean output = false;
        for (JsonNode item : result.path("outputFiles")) if (name.equalsIgnoreCase(item.path("name").asText()) && sha.equals(item.path("sha256").asText())) output = true;
        if (indexed != 1 || !output || artifacts == null) return false;
        return artifacts.stream().filter(a -> ("eclipse-output-" + name).equalsIgnoreCase(a.getArtifactName()) && "application/octet-stream".equals(a.getContentType())
                && sha.equals(a.getSha256()) && (a.getExpiresAt() == null || LocalDateTime.now().isBefore(a.getExpiresAt()))).count() == 1;
    }
    private static int integer(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() > 0 && value.asInt() <= 2_000_000 ? value.asInt() : 0;
    }
}
