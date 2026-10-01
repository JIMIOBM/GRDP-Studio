package com.grdp.studio.reservoirdiagnostic.dto;

import com.grdp.studio.diagnostic.dto.DiagnosticCurveModels;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/**
 * 库级诊断曲线 DTO。
 *
 * <p>职责：</p>
 * <p>
 * 1. 前端传项目、气藏、储气库、所选井、代表 PVT、压力上下限；<br>
 * 2. 后端校验并使用所选井的当前已计算诊断方案；<br>
 * 3. 后端只汇总所选井的 project_well_diagnostic_input；<br>
 * 4. 最终复用 DiagnosticCurveService。
 * </p>
 */
public final class GasReservoirDiagnosticModels {

    private GasReservoirDiagnosticModels() {
    }

    /**
     * 页面初始化上下文。
     */
    public record ContextResponse(
            List<WellOption> wells,
            List<PvtOption> pvtOptions
    ) {
    }

    /**
     * 储气库内可选择的井。
     */
    public record WellOption(
            long wellId,
            String wellName,
            boolean available
    ) {
    }

    /**
     * 可作为库级代表 PVT 的选项。
     *
     * <p>当前没有独立的“库级 PVT 表”，
     * 因此从各井当前有效诊断方案保存的 pvt_snapshot 中选一份。</p>
     */
    public record PvtOption(
            long pvtId,
            String pvtName,
            String sourceWellName
    ) {
    }

    /**
     * 库级诊断计算请求。
     *
     * <p>注意：这里没有 productionData。
     * productionData 完全由后端从各井诊断 input 汇总产生。</p>
     */
    public record CalculateRequest(
            @Positive(message = "项目ID必须大于0")
            long projectId,

            @Positive(message = "气藏ID必须大于0")
            long gasReservoirId,

            @Positive(message = "储气库ID必须大于0")
            long storageId,

            @NotNull(message = "请选择参与计算的井")
            List<Long> wellIds,

            @Positive(message = "PVT ID必须大于0")
            long pvtId,

            @NotNull(message = "压力上限不能为空")
            @Positive(message = "压力上限必须大于0")
            Double upperLimit,

            @NotNull(message = "压力下限不能为空")
            @Positive(message = "压力下限必须大于0")
            Double lowerLimit
    ) {
    }

    /**
     * 最终送进库诊断计算的聚合数据。
     */
    public record AggregatedRow(
            int sequence,

            String time,

            /**
             * 数据库单位：10^4 m3。
             */
            Double gasVolume1e4,

            /**
             * DiagnosticCurveService 使用单位：10^8 m3。
             */
            Double gasVolume1e8,

            /**
             * INJECTION / PRODUCTION。
             */
            String direction,

            /**
             * 例如：第1周期注气、第1周期采气。
             */
            String cycle,

            /**
             * 当前时间点实际有数据的井数。
             */
            int contributingWellCount
    ) {
    }

    /**
     * 库级计算返回。
     */
    public record CalculateResponse(
            int selectedWellCount,

            /**
             * 后端按时间汇总后的数据，便于前端检查。
             */
            List<AggregatedRow> aggregatedRows,

            /**
             * 直接复用现有单井算法产生的完整结果。
             */
            DiagnosticCurveModels.CalculateResponse result
    ) {
    }

    /*
     * ============================================================
     * 以下类仅供 Mapper 映射数据库结果使用。
     * 使用普通 JavaBean，而不是 record，
     * 可以减少 MyBatis 构造器映射兼容问题。
     * ============================================================
     */

    public static class WellRow {

        private Long wellId;
        private String wellName;

        public WellRow() {
        }

        public Long getWellId() {
            return wellId;
        }

        public void setWellId(Long wellId) {
            this.wellId = wellId;
        }

        public String getWellName() {
            return wellName;
        }

        public void setWellName(String wellName) {
            this.wellName = wellName;
        }
    }

    public static class LatestDiagnosticRow {

        private Long diagnosticId;
        private Long wellId;
        private String wellName;

        private Integer diagnosticNo;

        private Long pvtId;
        private String pvtName;
        private String pvtSnapshot;

        private Long inputCount;

        public LatestDiagnosticRow() {
        }

        public Long getDiagnosticId() {
            return diagnosticId;
        }

        public void setDiagnosticId(Long diagnosticId) {
            this.diagnosticId = diagnosticId;
        }

        public Long getWellId() {
            return wellId;
        }

        public void setWellId(Long wellId) {
            this.wellId = wellId;
        }

        public String getWellName() {
            return wellName;
        }

        public void setWellName(String wellName) {
            this.wellName = wellName;
        }

        public Integer getDiagnosticNo() {
            return diagnosticNo;
        }

        public void setDiagnosticNo(Integer diagnosticNo) {
            this.diagnosticNo = diagnosticNo;
        }

        public Long getPvtId() {
            return pvtId;
        }

        public void setPvtId(Long pvtId) {
            this.pvtId = pvtId;
        }

        public String getPvtName() {
            return pvtName;
        }

        public void setPvtName(String pvtName) {
            this.pvtName = pvtName;
        }

        public String getPvtSnapshot() {
            return pvtSnapshot;
        }

        public void setPvtSnapshot(String pvtSnapshot) {
            this.pvtSnapshot = pvtSnapshot;
        }

        public Long getInputCount() {
            return inputCount;
        }

        public void setInputCount(Long inputCount) {
            this.inputCount = inputCount;
        }
    }

    public static class DiagnosticInputRow {

        private Long diagnosticId;

        private Long wellId;
        private String wellName;

        private Integer sequenceNo;

        private String timeText;
        private String cycleName;

        /**
         * 数据库单位 10^4m3。
         */
        private BigDecimal gasVolume;

        public DiagnosticInputRow() {
        }

        public Long getDiagnosticId() {
            return diagnosticId;
        }

        public void setDiagnosticId(Long diagnosticId) {
            this.diagnosticId = diagnosticId;
        }

        public Long getWellId() {
            return wellId;
        }

        public void setWellId(Long wellId) {
            this.wellId = wellId;
        }

        public String getWellName() {
            return wellName;
        }

        public void setWellName(String wellName) {
            this.wellName = wellName;
        }

        public Integer getSequenceNo() {
            return sequenceNo;
        }

        public void setSequenceNo(Integer sequenceNo) {
            this.sequenceNo = sequenceNo;
        }

        public String getTimeText() {
            return timeText;
        }

        public void setTimeText(String timeText) {
            this.timeText = timeText;
        }

        public String getCycleName() {
            return cycleName;
        }

        public void setCycleName(String cycleName) {
            this.cycleName = cycleName;
        }

        public BigDecimal getGasVolume() {
            return gasVolume;
        }

        public void setGasVolume(BigDecimal gasVolume) {
            this.gasVolume = gasVolume;
        }
    }
}