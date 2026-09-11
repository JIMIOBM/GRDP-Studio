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
            String networkReasonClass = exception instanceof PipesimNetworkResultValidator.ResultValidationException
                    ? networkReasonClass(exception.getMessage()) : null;
            throw new ResultValidationException(exception.getMessage(), exception, networkReasonClass);
        }
    }

    private static String networkReasonClass(String message) {
        if (message == null) return "schema";
        if (message.startsWith("topology")) return "topology";
        if (message.startsWith("profile") || message.startsWith("profiles")) return "profile";
        if (message.startsWith("quality") || message.startsWith("result contains duplicate")) return "quality";
        if (message.equals("Invalid study")) return "study";
        if (message.contains("finite numbers") || message.contains("unavailable sentinel")
                || message.contains("numeric null")) return "numeric";
        return "schema";
    }

    public record ValidatedResult(SoftwareIntegrationRunStatus terminalStatus, String contract, JsonNode result) {}

    public static class ResultValidationException extends RuntimeException {
        private final String networkReasonClass;

        public ResultValidationException(String message, Throwable cause, String networkReasonClass) {
            super(message, cause);
            this.networkReasonClass = networkReasonClass;
        }

        public String networkReasonClass() { return networkReasonClass; }
    }
}
