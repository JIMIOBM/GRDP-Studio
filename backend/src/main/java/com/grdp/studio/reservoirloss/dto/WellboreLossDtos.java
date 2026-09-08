package com.grdp.studio.reservoirloss.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** 储气库井筒损耗计算与存储接口的数据结构。 */
public final class WellboreLossDtos {
    private WellboreLossDtos() {}

    public static final String DIRECT_MODE = "direct";
    public static final String FORMULA_MODE = "formula";

    public record RenameRequest(@NotBlank @Size(max = 100) String name) {}

    public record SegmentInput(@Positive double segmentVolume) {}

    /**
     * 两种计算方式共用一个输入对象。不属于当前方式的字段允许为空，
     * 具体的条件校验由计算服务完成，避免直接输入也被要求填写 PVT 参数。
     */
    public record WellboreInput(
            @NotBlank String calculationMode,
            Double inputLossVolume,
            Double averageTemperatureK,
            Double pressureBefore,
            Double pressureAfter,
            @Size(max = 1000) List<@NotNull @Valid SegmentInput> segments,
            Integer gasType,
            Double specificGravity,
            Double h2SMoleFraction,
            Double co2MoleFraction,
            Double n2MoleFraction,
            Integer modificationMethod,
            Integer deviationFactorMethod,
            Integer viscosityMethod,
            @Size(max = 255) String importedFileName
    ) {}

    public record CalculateRequest(@Positive long projectId,
                                   @Positive long gasReservoirId,
                                   @NotNull @Valid WellboreInput input) {}

    public record Calculation(Long deviationFactorToolboxId,
                              Double deviationFactorBefore,
                              Double deviationFactorAfter,
                              Double totalSegmentVolume,
                              double wellboreLossVolume) {}

    public record SaveRequest(@Positive Long recordId,
                              @Positive long projectId,
                              @Positive long gasReservoirId,
                              @NotNull @Valid WellboreInput input,
                              @NotNull Calculation calculation) {}

    public record RecordSummary(long id, int recordNo, String recordName,
                                String lossType, LocalDateTime updatedAt) {}

    public record Detail(RecordSummary summary, WellboreInput input, Calculation calculation) {}
}
