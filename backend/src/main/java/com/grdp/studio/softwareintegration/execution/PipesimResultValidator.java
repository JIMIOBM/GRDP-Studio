package com.grdp.studio.softwareintegration.execution;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class PipesimResultValidator {
    private final PipesimWellResultValidator wellValidator;
    private final PipesimNetworkResultValidator networkValidator;

    public PipesimResultValidator(PipesimWellResultValidator wellValidator,
                                  PipesimNetworkResultValidator networkValidator) {
        this.wellValidator = wellValidator;
        this.networkValidator = networkValidator;
    }

    public ValidatedResult validate(String expectedRunTask, String expectedStudy, JsonNode result) {
        try {
            if ("network".equals(expectedRunTask)) {
                PipesimNetworkResultValidator.ValidatedResult validated =
                        networkValidator.validate(expectedRunTask, expectedStudy, result);
                return new ValidatedResult(validated.terminalStatus(), validated.contract(), validated.result());
            }
            PipesimWellResultValidator.ValidatedResult validated = wellValidator.validate(expectedRunTask, result);
            return new ValidatedResult(validated.terminalStatus(), validated.contract(), validated.result());
        } catch (PipesimWellResultValidator.ResultValidationException
                 | PipesimNetworkResultValidator.ResultValidationException exception) {
            throw new ResultValidationException(exception.getMessage(), exception);
        }
    }

    public record ValidatedResult(SoftwareIntegrationRunStatus terminalStatus, String contract, JsonNode result) {}

    public static class ResultValidationException extends RuntimeException {
        public ResultValidationException(String message, Throwable cause) { super(message, cause); }
    }
}
