package com.grdp.studio.storagewaterinvasion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

/** 库级请求只接收来源批次，不信任浏览器提交的储量、权重或计算结果。 */
public final class StorageWaterInvasionModels {
    private StorageWaterInvasionModels() {}
    public record Scope(@Positive long projectId, @Positive long gasReservoirId, @Positive long storageId) {}
    public record Selection(@Positive long wellId, @Positive long recordId) {}
    public record Start(@Positive long projectId, @Positive long gasReservoirId, @Positive long storageId,
                        @NotBlank @Size(max=64) String requestId,
                        @NotEmpty @Size(max=2000) List<@NotNull @Valid Selection> sources,
                        @NotNull Double waterGasRatioLimit) {
        public Scope scope() { return new Scope(projectId, gasReservoirId, storageId); }
    }
    public record Source(long wellId, String wellName, long recordId, double dynamicGasVolume, Map<String,Object> snapshot) {}
    public record Aggregate(Map<String,Object> payload, Map<String,Object> rules, List<Double> weights) {}
    public record Preview(String storageName, String carrierWellName, Aggregate aggregate) {}
    public record Task(long id, long storageId, String storageName, String carrierWellName, String taskStatus,
                       String resultCompleteness, String errorMessage, boolean carrierBlocked,
                       String createdAt, String finishedAt) {}
    public record Detail(Task record, Map<String,Object> input, Map<String,Object> rules,
                         List<Map<String,Object>> sources, Map<String,Object> result) {}
    public record Created(Task task, boolean created) {}
}
