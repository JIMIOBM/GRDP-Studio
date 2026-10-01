package com.grdp.studio.softwareintegration.execution;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class PipesimResultValidator {
    private final PipesimWellResultValidator wellValidator;
    private final PipesimSensitivityResultValidator sensitivityValidator;
    private final PipesimNetworkResultValidator networkValidator;
    private final PipesimSystemAnalysisResultValidator systemAnalysisValidator;
    private final PipesimGasLiftPerformanceResultValidator gasLiftPerformanceValidator;
    private final PipesimGasLiftDiagnosticsResultValidator gasLiftDiagnosticsValidator;
    private final PipesimVfpTablesResultValidator vfpTablesValidator;
    private final PipesimEspCurvesResultValidator espCurvesValidator;
    private final PipesimWellTrajectoryResultValidator trajectoryValidator;
    private final PipesimNetworkOptimizerResultValidator networkOptimizerValidator;

    @Autowired
    public PipesimResultValidator(PipesimWellResultValidator wellValidator,
                                  PipesimSensitivityResultValidator sensitivityValidator,
                                  PipesimNetworkResultValidator networkValidator,
                                  PipesimSystemAnalysisResultValidator systemAnalysisValidator,
                                  PipesimGasLiftPerformanceResultValidator gasLiftPerformanceValidator,
                                  PipesimGasLiftDiagnosticsResultValidator gasLiftDiagnosticsValidator,
                                  PipesimVfpTablesResultValidator vfpTablesValidator,
                                  PipesimEspCurvesResultValidator espCurvesValidator,
                                  PipesimWellTrajectoryResultValidator trajectoryValidator,
                                  PipesimNetworkOptimizerResultValidator networkOptimizerValidator) {
        this.wellValidator = wellValidator;
        this.sensitivityValidator = sensitivityValidator;
        this.networkValidator = networkValidator;
        this.systemAnalysisValidator = systemAnalysisValidator;
        this.gasLiftPerformanceValidator = gasLiftPerformanceValidator;
        this.gasLiftDiagnosticsValidator = gasLiftDiagnosticsValidator;
        this.vfpTablesValidator = vfpTablesValidator;
        this.espCurvesValidator = espCurvesValidator;
        this.trajectoryValidator = trajectoryValidator;
        this.networkOptimizerValidator = networkOptimizerValidator;
    }

    public PipesimResultValidator(PipesimWellResultValidator wellValidator,
                                  PipesimNetworkResultValidator networkValidator) {
        this(wellValidator, new PipesimSensitivityResultValidator(), networkValidator,
                new PipesimSystemAnalysisResultValidator(), new PipesimGasLiftPerformanceResultValidator(),
                new PipesimGasLiftDiagnosticsResultValidator(), new PipesimVfpTablesResultValidator(),
                new PipesimEspCurvesResultValidator(),
                new PipesimWellTrajectoryResultValidator(),
                new PipesimNetworkOptimizerResultValidator());
    }

    public ValidatedResult validate(String expectedRunTask, String expectedStudy, JsonNode result) {
        try {
            if ("network".equals(expectedRunTask)) {
                PipesimNetworkResultValidator.ValidatedResult validated =
                        networkValidator.validate(expectedRunTask, expectedStudy, result);
                return new ValidatedResult(validated.terminalStatus(), validated.contract(), validated.result());
            }
            if ("system-analysis".equals(expectedRunTask)) {
                PipesimWellResultValidator.ValidatedResult validated =
                        systemAnalysisValidator.validate(expectedStudy, result);
                return new ValidatedResult(validated.terminalStatus(), validated.contract(), validated.result());
            }
            if ("gas-lift-performance".equals(expectedRunTask)) {
                PipesimWellResultValidator.ValidatedResult validated = gasLiftPerformanceValidator.validate(result);
                return new ValidatedResult(validated.terminalStatus(), validated.contract(), validated.result());
            }
            if ("gas-lift-diagnostics".equals(expectedRunTask)) {
                PipesimWellResultValidator.ValidatedResult validated = gasLiftDiagnosticsValidator.validate(result);
                return new ValidatedResult(validated.terminalStatus(), validated.contract(), validated.result());
            }
            if ("vfp-tables".equals(expectedRunTask)) {
                PipesimWellResultValidator.ValidatedResult validated = vfpTablesValidator.validate(result);
                return new ValidatedResult(validated.terminalStatus(), validated.contract(), validated.result());
            }
            if ("esp-curves".equals(expectedRunTask)) {
                PipesimWellResultValidator.ValidatedResult validated = espCurvesValidator.validate(result);
                return new ValidatedResult(validated.terminalStatus(), validated.contract(), validated.result());
            }
            if ("trajectory".equals(expectedRunTask)) {
                PipesimWellResultValidator.ValidatedResult validated = trajectoryValidator.validate(result);
                return new ValidatedResult(validated.terminalStatus(), validated.contract(), validated.result());
            }
            if ("network-optimizer".equals(expectedRunTask)) {
                PipesimWellResultValidator.ValidatedResult validated = networkOptimizerValidator.validate(result);
                return new ValidatedResult(validated.terminalStatus(), validated.contract(), validated.result());
            }
            PipesimWellResultValidator.ValidatedResult validated = "sensitivity".equals(expectedRunTask)
                    ? sensitivityValidator.validate(expectedRunTask, result)
                    : wellValidator.validate(expectedRunTask, result);
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
