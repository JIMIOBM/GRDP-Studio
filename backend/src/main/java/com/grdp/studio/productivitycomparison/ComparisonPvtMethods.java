package com.grdp.studio.productivitycomparison;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static com.grdp.studio.productivitycomparison.ComparisonRepository.PvtSnapshot;

/** Read-only compatibility for incomplete test snapshots; never replaces historical values. */
final class ComparisonPvtMethods {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ComparisonPvtMethods() {}

    static PvtSnapshot complete(PvtSnapshot saved, String linkedSettings) {
        if (saved == null || linkedSettings == null || linkedSettings.isBlank()
                || (present(saved.modificationMethod()) && present(saved.deviationFactorMethod())
                && present(saved.viscosityMethod()))) return saved;
        JsonNode gas;
        try {
            gas = MAPPER.readTree(linkedSettings);
        } catch (RuntimeException error) {
            // An unreadable linked record must not hide other comparison records or invent defaults.
            return saved;
        }
        if (gas == null || !gas.isObject()) return saved;
        return new PvtSnapshot(saved.gasType(), saved.specificGravity(), saved.hydrogenSulfide(),
                saved.carbonDioxide(), saved.nitrogen(),
                method(saved.modificationMethod(), gas, "modificationMethod", "gasCorrectionMethod", "correctionMethod"),
                method(saved.deviationFactorMethod(), gas, "deviationFactorMethod", "deviationMethod", "zFactorMethod"),
                method(saved.viscosityMethod(), gas, "viscosityMethod", "gasViscosityMethod"),
                saved.temperature(), saved.originalPressure());
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

    private static String method(String saved, JsonNode gas, String... fields) {
        if (present(saved)) return saved; // "0" is a valid saved enum, not an empty value.
        for (String field : fields) {
            JsonNode value = gas.get(field);
            if (value != null && (value.isTextual() || value.isNumber()) && present(value.asText()))
                return value.asText().trim();
        }
        return saved;
    }
}
