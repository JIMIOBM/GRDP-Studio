package com.grdp.studio.softwareintegration.support;

import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationArtifactEntity;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

/** Strict first L39 input contract: one global-grid PERMX cell multiplier bound to a full baseline. */
public final class EclipseGridFieldParameters {
    public static final String SCHEMA = "eclipse-grid-field-parameters/1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "baselineRunId", "fieldFile", "fieldArtifactSha256",
            "nx", "ny", "nz", "i", "j", "k", "originalValue", "multiplier");
    private static final Pattern INIT_FILE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.INIT$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private EclipseGridFieldParameters() {}

    public static boolean valid(JsonNode value) {
        if (value == null || !value.isObject()) return false;
        var names = new HashSet<String>();
        value.properties().forEach(field -> names.add(field.getKey()));
        if (!names.equals(FIELDS) || !SCHEMA.equals(value.path("schemaVersion").asText())) return false;
        if (!positiveLong(value.path("baselineRunId"))) return false;
        String fieldFile = value.path("fieldFile").asText(null);
        String sha = value.path("fieldArtifactSha256").asText(null);
        if (fieldFile == null || !INIT_FILE.matcher(fieldFile).matches() || sha == null || !SHA256.matcher(sha).matches()) return false;
        int nx = positiveDimension(value.path("nx"));
        int ny = positiveDimension(value.path("ny"));
        int nz = positiveDimension(value.path("nz"));
        int i = positiveDimension(value.path("i"));
        int j = positiveDimension(value.path("j"));
        int k = positiveDimension(value.path("k"));
        if (nx == 0 || ny == 0 || nz == 0 || i == 0 || j == 0 || k == 0 || i > nx || j > ny || k > nz) return false;
        if (!value.path("originalValue").isNumber() || !value.path("multiplier").isNumber()) return false;
        double original = value.path("originalValue").asDouble(Double.NaN);
        double multiplier = value.path("multiplier").asDouble(Double.NaN);
        return Double.isFinite(original) && original > 0 && Double.isFinite(multiplier)
                && multiplier >= 0.000001 && multiplier <= 100 && multiplier != 1
                && Double.isFinite(original * multiplier);
    }

    public static boolean matchesBaseline(JsonNode value, JsonNode baselineResult, List<SoftwareIntegrationArtifactEntity> artifacts) {
        if (!valid(value) || baselineResult == null || !baselineResult.isObject()
                || !"eclipse-summary-result/1".equals(baselineResult.path("schemaVersion").asText())
                || !"VALID_FULL".equals(baselineResult.path("resultContract").asText())) return false;
        int nx = positiveDimension(value.path("nx"));
        int ny = positiveDimension(value.path("ny"));
        int nz = positiveDimension(value.path("nz"));
        JsonNode grid = baselineResult.path("grid");
        if (!grid.isObject() || grid.path("nx").asInt(-1) != nx || grid.path("ny").asInt(-1) != ny
                || grid.path("nz").asInt(-1) != nz) return false;
        long expectedCount = (long) nx * ny * nz;
        String fieldFile = value.path("fieldFile").asText();
        String expectedSha = value.path("fieldArtifactSha256").asText();
        boolean indexed = false;
        for (JsonNode file : baselineResult.path("fieldIndex").path("files")) {
            if (!fieldFile.equalsIgnoreCase(file.path("name").asText())) continue;
            for (JsonNode field : file.path("fields")) {
                if ("PERMX".equals(field.path("keyword").asText()) && field.path("timeStep").isMissingNode()
                        && Set.of("REAL", "DOUB").contains(field.path("dataType").asText())
                        && field.path("count").asLong(-1) == expectedCount) indexed = true;
            }
        }
        if (!indexed) return false;
        boolean outputMatches = false;
        for (JsonNode output : baselineResult.path("outputFiles")) {
            if (fieldFile.equalsIgnoreCase(output.path("name").asText())
                    && expectedSha.equals(output.path("sha256").asText())) outputMatches = true;
        }
        if (!outputMatches) return false;
        String artifactName = "eclipse-output-" + fieldFile;
        return artifacts != null && artifacts.stream().anyMatch(artifact -> artifactName.equalsIgnoreCase(artifact.getArtifactName())
                && "application/octet-stream".equals(artifact.getContentType()) && expectedSha.equals(artifact.getSha256())
                && (artifact.getExpiresAt() == null || LocalDateTime.now().isBefore(artifact.getExpiresAt())));
    }

    private static boolean positiveLong(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0;
    }

    private static int positiveDimension(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.asInt() > 0 && value.asInt() <= 2_000_000
                ? value.asInt() : 0;
    }
}
