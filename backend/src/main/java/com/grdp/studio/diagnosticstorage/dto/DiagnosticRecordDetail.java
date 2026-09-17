package com.grdp.studio.diagnosticstorage.dto;

import com.grdp.studio.diagnostic.dto.DiagnosticCurveModels;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 再次打开一个诊断方案时恢复页面所需的完整数据。
 *
 * <p>productionData.gas 的单位仍与当前计算接口一致：10^8 m3。
 * 数据库 project_well_diagnostic_input.gas_volume 则保存为 10^4 m3，
 * 转换由存储服务完成。</p>
 */
public record DiagnosticRecordDetail(
        DiagnosticRecordSummary record,
        long projectId,
        long gasReservoirId,
        long wellId,
        Long pvtId,
        String pvtName,
        DiagnosticCurveModels.PvtData pvtSnapshot,
        Double upperPressureLimit,
        Double lowerPressureLimit,
        String remark,
        List<DiagnosticCurveModels.ProductionDataItem> productionData,
        DiagnosticCurveModels.CalculateResponse result,
        String calculationVersion,
        LocalDateTime calculatedAt
) {
}
