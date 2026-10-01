package com.grdp.studio.storagecapacity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 储气库库容设计接口数据结构。
 * 本次只覆盖「运行压力」与「库容参数」两块；「孔隙体积」暂未纳入。
 * 单位口径与库级既有模块一致：压力 MPa（绝对压力），气量 10^4m3。
 */
public final class StorageCapacityDtos {
    private StorageCapacityDtos() {}

    /**
     * 各参数块允许只填一部分（分步录入是常态），因此字段均可空；
     * 空值由 {@code @PositiveOrZero} 放行，具体范围与压力大小关系由 Service 校验。
     */
    public record DesignInput(
            @PositiveOrZero Double upperLimitPressure,
            @PositiveOrZero Double lowerLimitPressure,
            @PositiveOrZero Double storageCapacity,
            @PositiveOrZero Double workingGasVolume,
            @PositiveOrZero Double cushionGasVolume,
            @PositiveOrZero Double supplementaryCushionGasVolume,
            @Size(max = 255) String importedFileName) {}

    /**
     * {@code @Valid} 必须保留：Java record 上只有加了它，嵌套 DesignInput 的
     * 各字段约束（@PositiveOrZero / @Size）才会级联校验。
     */
    public record SaveRequest(@Positive long projectId,
                              @Positive long gasReservoirId,
                              @Positive long storageId,
                              @NotNull @Valid DesignInput input) {}

    public record Design(long id,
                         long projectId,
                         long gasReservoirId,
                         long storageId,
                         DesignInput input,
                         LocalDateTime updatedAt) {}
}
