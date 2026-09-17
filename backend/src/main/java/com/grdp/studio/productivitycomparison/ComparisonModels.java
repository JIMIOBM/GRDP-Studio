package com.grdp.studio.productivitycomparison;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public final class ComparisonModels {
    private ComparisonModels() {}
    public record Selection(@NotBlank String method, @Positive long recordId, String operationType) {
        public Selection { if (operationType == null) operationType = "production"; }
        public Selection(String method, long recordId) { this(method, recordId, "production"); }
    }
    public record CalculateRequest(@Positive long projectId, @Positive long gasReservoirId,
            @NotBlank String wellName, @NotBlank String pressureMethod,
            Double formationPressure, @NotEmpty @Size(max = 100) List<@Valid Selection> records,
            Double injectionPressure, @Valid PeriodSettings period) {
        public CalculateRequest(long projectId, long gasReservoirId, String wellName, String pressureMethod,
                Double formationPressure, List<Selection> records, Double injectionPressure) {
            this(projectId, gasReservoirId, wellName, pressureMethod, formationPressure, records, injectionPressure, null);
        }
        public CalculateRequest(long projectId, long gasReservoirId, String wellName, String pressureMethod,
                Double formationPressure, List<Selection> records) {
            this(projectId, gasReservoirId, wellName, pressureMethod, formationPressure, records, null);
        }
    }
    public record PeriodSettings(LocalDate startDate, LocalDate endDate,
            @NotNull @DecimalMin("1") @DecimalMax("1200") @Digits(integer = 4, fraction = 0) java.math.BigDecimal months) {
        public PeriodSettings(LocalDate startDate, LocalDate endDate, int months) {
            this(startDate, endDate, java.math.BigDecimal.valueOf(months));
        }
    }
    public record PeriodResult(int index, LocalDate startDate, LocalDate endDate, List<Result> results) {}
    public record DirectionGroup(LocalDate date, List<Result> results) {}
    public record DirectionComparisonResponse(CalculateResponse calculation, List<DirectionGroup> groups) {}
    public record RecordSummary(String key, String method, String methodName, long recordId,
            String recordName, LocalDate date, String dateKind, boolean available, String unavailableReason,
            String operationType) {}
    public record Result(RecordSummary record, double a, double b, double openFlowCapacity,
            Double reservoirPseudoPressure, Double flowingPseudoPressure, double formationPressure, double flowingPressure) {}
    public record CalculateResponse(String pressureMethod, Double formationPressure,
            double flowingPressure, List<Result> results, Double injectionPressure, List<PeriodResult> periods) {
        public CalculateResponse(String pressureMethod, Double formationPressure, double flowingPressure,
                List<Result> results, Double injectionPressure) {
            this(pressureMethod, formationPressure, flowingPressure, results, injectionPressure, List.of());
        }
    }
}
