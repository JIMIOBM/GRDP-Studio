package com.grdp.studio.productivitycomparison;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import static com.grdp.studio.productivitycomparison.ComparisonModels.*;

/** 库级多周期/多方法对比不保存副本；原始记录、井归属均在计算时由服务端读取。 */
public final class StorageComparisonModels {
    private StorageComparisonModels() {}
    public record Request(@Positive long projectId, @Positive long gasReservoirId, @Positive long storageId,
            @NotEmpty @Size(max = 2000) List<@NotNull @Positive Long> wellIds,
            @NotBlank String method, @NotBlank String operationType, @NotBlank String pressureMethod,
            Double formationPressure, Double injectionPressure, @NotNull @Valid PeriodSettings period) {}
    public record WellSummary(long wellId, String wellName, int recordCount, int excludedCount) {}
    public record Mean(long wellId, String wellName, Double averageOpenFlow, int recordCount) {}
    public record Period(int index, LocalDate startDate, LocalDate endDate, List<Mean> wells) {}
    public record Response(String method, String operationType, String pressureMethod,
            Double formationPressure, Double injectionPressure, List<WellSummary> wells, List<Period> periods) {}

    /** 多方法仍按周期统计；方法维度独立，不允许先混合不同方法再求平均。 */
    public record MultiMethodRequest(@Positive long projectId, @Positive long gasReservoirId, @Positive long storageId,
            @NotEmpty @Size(max = 2000) List<@NotNull @Positive Long> wellIds,
            @NotEmpty @Size(max = 6) List<@NotBlank String> methods,
            @NotBlank String operationType, @NotBlank String pressureMethod,
            Double formationPressure, Double injectionPressure, @NotNull @Valid PeriodSettings period) {}
    public record MethodMean(String method, Double averageOpenFlow, int recordCount) {}
    public record WellMethods(long wellId, String wellName, List<MethodMean> methods) {}
    public record MethodPeriod(int index, LocalDate startDate, LocalDate endDate, List<WellMethods> wells) {}
    public record MultiMethodResponse(List<String> methods, String operationType, String pressureMethod,
            Double formationPressure, Double injectionPressure, List<WellSummary> wells, List<MethodPeriod> periods) {}

    /** 注采对比固定同时读取两个方向；调用方不能只传某一方向，也不能传计算系数。 */
    public record DirectionRequest(@Positive long projectId, @Positive long gasReservoirId, @Positive long storageId,
            @NotEmpty @Size(max = 2000) List<@NotNull @Positive Long> wellIds,
            @NotBlank String method, @NotBlank String pressureMethod,
            @NotNull Double formationPressure, @NotNull Double injectionPressure, @NotNull @Valid PeriodSettings period) {}
    public record DirectionMean(String operationType, Double averageOpenFlow, int recordCount) {}
    public record WellDirections(long wellId, String wellName, List<DirectionMean> directions) {}
    public record DirectionPeriod(int index, LocalDate startDate, LocalDate endDate, List<WellDirections> wells) {}
    public record DirectionResponse(String method, String pressureMethod, double formationPressure, double injectionPressure,
            List<WellSummary> wells, List<DirectionPeriod> periods) {}
}
