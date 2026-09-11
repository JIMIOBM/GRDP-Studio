package com.grdp.studio.reservoirloss.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.gaspvt.dto.GasViscosityCurveRequest;
import com.grdp.studio.gaspvt.service.GasPvtService;
import com.grdp.studio.reservoirloss.dto.GeologicalLossDtos.EscapeCalculation;
import com.grdp.studio.reservoirloss.dto.GeologicalLossDtos.EscapeCalculateRequest;
import com.grdp.studio.reservoirloss.dto.GeologicalLossDtos.MicroscopicCalculation;
import com.grdp.studio.reservoirloss.dto.GeologicalLossDtos.MicroscopicCalculateRequest;
import org.springframework.stereotype.Service;

/** 库级地质损耗计算全部在后端完成，前端不保存计算公式。 */
@Service
public class GeologicalLossCalculationService {
    private final GasPvtService gasPvtService;

    public GeologicalLossCalculationService(GasPvtService gasPvtService) {
        this.gasPvtService = gasPvtService;
    }

    public MicroscopicCalculation calculateMicroscopic(MicroscopicCalculateRequest request,
                                                        String token, String cookie, String processEnv) {
        var input = request.input();
        var pvtRequest = new GasViscosityCurveRequest(
                request.projectId(), input.gasType(), input.specificGravity(),
                input.h2SMoleFraction(), input.co2MoleFraction(), input.n2MoleFraction(),
                input.formationTemperature(), input.lowerLimitPressure(), input.lowerLimitPressure(), 1d,
                input.modificationMethod(), input.deviationFactorMethod(), input.viscosityMethod());
        var pvt = gasPvtService.calculateSingleVolumeFactor(
                pvtRequest, input.lowerLimitPressure(), input.formationTemperature(),
                token, cookie, processEnv);
        if (!Double.isFinite(pvt.volumeFactor()) || pvt.volumeFactor() <= 0) {
            throw new BusinessException(502, "天然气体积系数计算结果无效");
        }
        // 饱和度输入单位为百分数，因此差值需除以100后再进入标准体积计算。
        double loss = input.poreVolume()
                * (input.currentResidualSaturation() - input.previousResidualSaturation())
                / 100d / pvt.volumeFactor();
        if (!Double.isFinite(loss)) throw new BusinessException(400, "微观损耗气量计算结果无效");
        return new MicroscopicCalculation(pvt.toolboxId(), pvt.volumeFactor(), loss);
    }

    public EscapeCalculation calculateEscape(EscapeCalculateRequest request) {
        var input = request.input();
        double actualRate = ((input.movableCushionGasVolume() + input.unusedInventoryVolume())
                / input.previousCushionGasVolume() - 1d) * 100d;
        double loss = input.injectionVolume() * (actualRate - input.predictedChangeRate()) / 100d;
        if (!Double.isFinite(actualRate) || !Double.isFinite(loss)) {
            throw new BusinessException(400, "逸散性损耗计算结果无效");
        }
        return new EscapeCalculation(actualRate, loss);
    }
}
