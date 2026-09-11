package com.grdp.studio.reservoirloss.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.reservoirloss.dto.SurfaceLossDtos.*;
import com.grdp.studio.reservoirloss.dto.WellboreLossDtos;
import org.springframework.stereotype.Service;

/** 地面系统放空损耗和凝液溶解携带损耗在同一次请求中计算，分别返回结果。 */
@Service
public class SurfaceLossCalculationService {
    private final WellboreLossCalculationService ventCalculation;

    public SurfaceLossCalculationService(WellboreLossCalculationService ventCalculation) {
        this.ventCalculation = ventCalculation;
    }

    public Calculation calculate(CalculateRequest request, String token, String cookie, String processEnv) {
        SurfaceInput input = request.input().normalized();
        // 先校验凝液参数，避免无效请求仍创建原平台PVT工具。
        double condensateLoss = calculateCondensate(input);
        try {
            var vent = ventCalculation.calculate(new WellboreLossDtos.CalculateRequest(
                    request.projectId(), request.gasReservoirId(), input.toVentInput()), token, cookie, processEnv);
            return new Calculation(vent.deviationFactorToolboxId(), vent.deviationFactorBefore(),
                    vent.deviationFactorAfter(), vent.totalSegmentVolume(), vent.wellboreLossVolume(), condensateLoss);
        } catch (BusinessException error) {
            throw new BusinessException(error.getCode(), error.getMessage()
                    .replace("井筒损耗", "地面系统放空损耗").replace("放空井段", "放空段")
                    .replace("井筒平均压力", "平均压力"));
        }
    }

    static double calculateCondensate(SurfaceInput input) {
        // 直接输入的是最终凝液损耗气量，不能要求凝析油体积和气油比，更不能相乘。
        if ("direct".equals(input.calculationMode())) {
            requireNonNegative(input.inputCondensateLossVolume(), "凝液溶解携带损耗气量");
            return input.inputCondensateLossVolume();
        }
        if (!"formula".equals(input.calculationMode())) {
            throw new BusinessException(400, "地面损耗计算方式不正确");
        }
        requireNonNegative(input.condensateVolume(), "分离器出口凝析油体积");
        requireNonNegative(input.gasOilRatio(), "凝析油溶解气油比");
        // 凝析油体积输入为10^4m³，气油比为m³/m³；乘积已经是10^4m³，不再换算。
        double loss = input.condensateVolume() * input.gasOilRatio();
        if (!Double.isFinite(loss)) throw new BusinessException(400, "凝液溶解携带损耗计算结果超出范围");
        return loss;
    }

    private static void requireNonNegative(Double value, String name) {
        if (value == null || !Double.isFinite(value) || value < 0) {
            throw new BusinessException(400, name + "必须是大于或等于0的有效数字");
        }
    }
}
