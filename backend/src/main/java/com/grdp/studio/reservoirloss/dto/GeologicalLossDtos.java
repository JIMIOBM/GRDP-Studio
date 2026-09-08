package com.grdp.studio.reservoirloss.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;
import java.util.List;

/** 库级地质损耗接口使用的请求和响应对象。 */
public final class GeologicalLossDtos {
    private GeologicalLossDtos() {}

    public record RenameRequest(@NotBlank String name) {}

    public record RecordSummary(long id, int recordNo, String recordName,
                                String lossType, LocalDateTime updatedAt) {}

    public record MicroscopicInput(
            @Positive double poreVolume,
            @NotNull @Min(0) @Max(100) Double previousResidualSaturation,
            @NotNull @Min(0) @Max(100) Double currentResidualSaturation,
            @Positive double lowerLimitPressure,
            @NotNull Double formationTemperature,
            @NotNull @Min(0) @Max(2) Integer gasType,
            @Positive double specificGravity,
            @NotNull @DecimalMin("0") Double h2SMoleFraction,
            @NotNull @DecimalMin("0") Double co2MoleFraction,
            @NotNull @DecimalMin("0") Double n2MoleFraction,
            @NotNull @Min(0) @Max(1) Integer modificationMethod,
            @NotNull @Min(0) @Max(2) Integer deviationFactorMethod,
            @NotNull @Min(0) @Max(2) Integer viscosityMethod,
            String importedFileName
    ) {}

    public record MicroscopicCalculateRequest(@Positive long projectId,
                                              @Positive long gasReservoirId,
                                              @NotNull MicroscopicInput input) {}

    public record MicroscopicCalculation(long volumeFactorToolboxId,
                                         double volumeFactor,
                                         double microscopicLossVolume) {}

    public record MicroscopicSaveRequest(Long recordId,
                                         @Positive long projectId,
                                         @Positive long gasReservoirId,
                                         @NotNull MicroscopicInput input,
                                         @NotNull MicroscopicCalculation calculation) {}

    public record MicroscopicDetail(RecordSummary summary, MicroscopicInput input,
                                    MicroscopicCalculation calculation) {}

    public record EscapeInput(
            @Positive double previousCushionGasVolume,
            @NotNull Double movableCushionGasVolume,
            @NotNull Double unusedInventoryVolume,
            @NotNull Double injectionVolume,
            @NotNull Double predictedChangeRate
    ) {}

    public record EscapeCalculateRequest(@Positive long projectId,
                                         @Positive long gasReservoirId,
                                         @NotNull EscapeInput input) {}

    public record EscapeCalculation(double actualChangeRate, double escapeLossVolume) {}

    public record EscapeSaveRequest(Long recordId,
                                    @Positive long projectId,
                                    @Positive long gasReservoirId,
                                    @NotNull EscapeInput input,
                                    @NotNull EscapeCalculation calculation) {}

    public record EscapeDetail(RecordSummary summary, EscapeInput input,
                               EscapeCalculation calculation) {}

    public record RecordLists(List<RecordSummary> microscopic, List<RecordSummary> escape) {}
}
