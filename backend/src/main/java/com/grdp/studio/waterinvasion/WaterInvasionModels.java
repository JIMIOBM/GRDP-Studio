package com.grdp.studio.waterinvasion;

import jakarta.validation.constraints.*;
import java.util.Map;

/** 单井任务和结果的接口模型；井与库共用结果数据，不共用页面。 */
public final class WaterInvasionModels {
    private WaterInvasionModels() {}
    public record Start(@Positive long projectId, @Positive long gasReservoirId,
                        @NotBlank @Size(max=200) String wellName,
                        @NotBlank @Size(max=64) String requestId,
                        Boolean isUseActualStaticPressure, Double waterGasRatioLimit) {}
    public record Scope(long projectId, long gasReservoirId, String wellName) {}
    public record Task(long id, String wellName, String sourceType, String taskStatus,
                       String resultCompleteness, String errorMessage, String createdAt,
                       String finishedAt, String savedAt) {}
    public record Created(Task task, boolean created) {}
    public record Detail(Task record, Map<String,Object> result) {}
}
