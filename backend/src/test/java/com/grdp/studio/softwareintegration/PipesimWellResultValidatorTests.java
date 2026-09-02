package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.PipesimWellResultValidator;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipesimWellResultValidatorTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PipesimWellResultValidator validator = new PipesimWellResultValidator();

    @Test
    void acceptsCombinedPartialWithoutRenamingOrAddingUnits() {
        var result = objectMapper.readTree(resultJson("black_oil_liquid", "combined", "VALID_PARTIAL", "[]"));
        var validated = validator.validate("combined", result);
        assertThat(validated.terminalStatus()).isEqualTo(SoftwareIntegrationRunStatus.PARTIAL_SUCCEEDED);
        assertThat(validated.result()).isSameAs(result);
    }

    @Test
    void enforcesGasUnitsAndFiniteNumbers() {
        var wrongUnits = objectMapper.readTree(resultJson("basic_gas", "combined", "VALID_PARTIAL", "[]"));
        assertThatThrownBy(() -> validator.validate("combined", wrongUnits))
                .isInstanceOf(PipesimWellResultValidator.ResultValidationException.class);

        var nonFinite = objectMapper.readTree(resultJson("black_oil_liquid", "combined", "VALID_PARTIAL", "[]"));
        ((tools.jackson.databind.node.ObjectNode) nonFinite.path("ipr").get(0)).put("flow", Double.NaN);
        assertThatThrownBy(() -> validator.validate("combined", nonFinite))
                .isInstanceOf(PipesimWellResultValidator.ResultValidationException.class);
    }

    @Test
    void rejectsAdditionalFieldsAndInvalidContract() {
        var result = (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(
                resultJson("black_oil_liquid", "combined", "VALID_PARTIAL", "[]"));
        result.put("invented", true);
        assertThatThrownBy(() -> validator.validate("combined", result))
                .isInstanceOf(PipesimWellResultValidator.ResultValidationException.class);
    }

    @Test
    void acceptsAllSixWp01GoldenResultsWithoutTransformation() throws Exception {
        Path repository = Path.of("").toAbsolutePath().normalize();
        if (repository.getFileName().toString().equalsIgnoreCase("backend")) repository = repository.getParent();
        for (String model : List.of("CSW_101", "CSW_102")) {
            for (String file : List.of("nodal", "pt-profile", "combined")) {
                Path golden = repository.resolve("docs/software-integration/golden/pipesim-well-result-v1")
                        .resolve(model).resolve(file + ".json");
                var result = objectMapper.readTree(Files.readString(golden));
                String task = file.equals("pt-profile") ? "profile" : file;
                assertThat(validator.validate(task, result).result()).isSameAs(result);
            }
        }
    }

    private static String resultJson(String modelKind, String task, String contract, String profile) {
        return """
                {"schemaVersion":"pipesim-well-result/1","model_kind":"%s","runTask":"%s","resultContract":"%s",
                 "units":{"flow":{"displayUnit":null,"semantics":"unspecified"},
                          "pressure":{"displayUnit":null,"semantics":"unspecified"},
                          "depth":{"displayUnit":null,"semantics":"unspecified"},
                          "temperature":{"displayUnit":null,"semantics":"unspecified"}},
                 "ipr":[{"flow":1.0,"pressure":2.0}],"vlp":[{"flow":1.0,"pressure":2.0}],"profile":%s}
                """.formatted(modelKind, task, contract, profile);
    }
}
