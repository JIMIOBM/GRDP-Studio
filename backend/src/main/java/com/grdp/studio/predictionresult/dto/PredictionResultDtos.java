package com.grdp.studio.predictionresult.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 井组动态预测结果留存接口使用的请求和响应对象。 */
public final class PredictionResultDtos {
    private PredictionResultDtos() {}

    /** 单个预测点（日期 + 四项输出指标），整块序列化为 JSON 存储。 */
    public record PredictionPoint(String date, Double gas, Double cumulative,
                                  Double formation, Double recovery) {}

    /** 预测完成后的留存请求；同一（项目+气藏+任务编号）重复保存按覆盖处理。 */
    public record SaveRequest(
            @NotNull @Positive Long projectId,
            @NotNull @Positive Long gasReservoirId,
            Long storageId,
            @NotNull @Positive Long predictionId,
            @NotBlank String predictionName,
            @NotBlank @Pattern(regexp = "单目标|多目标") String mode,
            @NotNull List<String> wellNames,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotNull @Size(min = 1) List<PredictionPoint> points,
            Map<String, Object> request) {}

    public record SavedSummary(long id, long predictionId, String predictionName,
                               int pointCount, LocalDateTime calculatedAt) {}

    /** 留存记录摘要（列表查询用，不含逐日明细）。 */
    public record ResultSummary(long id, long predictionId, String predictionName, String mode,
                                int pointCount, LocalDate startDate, LocalDate endDate,
                                LocalDateTime calculatedAt) {}
}
