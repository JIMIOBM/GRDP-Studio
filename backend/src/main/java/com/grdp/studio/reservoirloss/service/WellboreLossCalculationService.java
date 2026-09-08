package com.grdp.studio.reservoirloss.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.gaspvt.dto.GasViscosityCurveRequest;
import com.grdp.studio.gaspvt.service.GasPvtService;
import com.grdp.studio.reservoirloss.dto.WellboreLossDtos.*;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.grdp.studio.reservoirloss.dto.WellboreLossDtos.DIRECT_MODE;
import static com.grdp.studio.reservoirloss.dto.WellboreLossDtos.FORMULA_MODE;

/** 井筒损耗的两种计算方式；数据库只保存服务端返回的计算快照。 */
@Service
public class WellboreLossCalculationService {
    private static final double STANDARD_TEMPERATURE_K = 293.15d;
    private static final double STANDARD_PRESSURE_MPA = 0.101325d;
    private final GasPvtService gasPvtService;

    public WellboreLossCalculationService(GasPvtService gasPvtService) {
        this.gasPvtService = gasPvtService;
    }

    public Calculation calculate(CalculateRequest request,
                                 String token, String cookie, String processEnv) {
        WellboreInput input = request.input();
        if (DIRECT_MODE.equals(input.calculationMode())) return calculateDirect(input);
        if (!FORMULA_MODE.equals(input.calculationMode())) {
            throw new BusinessException(400, "井筒损耗计算方式不正确");
        }
        validateFormula(input);
        double totalVolume = input.segments().stream().mapToDouble(SegmentInput::segmentVolume).sum();

        var pvtRequest = new GasViscosityCurveRequest(
                request.projectId(), input.gasType(), input.specificGravity(),
                input.h2SMoleFraction(), input.co2MoleFraction(), input.n2MoleFraction(),
                input.averageTemperatureK(), input.pressureAfter(), input.pressureBefore(), 1d,
                input.modificationMethod(), input.deviationFactorMethod(), input.viscosityMethod());
        var z = gasPvtService.calculateWellboreDeviationFactors(
                pvtRequest, input.pressureBefore(), input.pressureAfter(), input.averageTemperatureK(),
                token, cookie, processEnv);

        // Qshw = 10^-4 × ΣVi × Tsc/(Psc×T) × (P1/Z1 - P2/Z2)。
        double loss = 1e-4d * totalVolume * STANDARD_TEMPERATURE_K
                / (STANDARD_PRESSURE_MPA * input.averageTemperatureK())
                * (input.pressureBefore() / z.deviationFactorBefore()
                - input.pressureAfter() / z.deviationFactorAfter());
        if (!Double.isFinite(loss) || loss < 0) {
            throw new BusinessException(400, "井筒损耗气量计算结果无效，请检查放空前后压力");
        }
        return new Calculation(z.toolboxId(), z.deviationFactorBefore(), z.deviationFactorAfter(),
                totalVolume, loss);
    }

    private Calculation calculateDirect(WellboreInput input) {
        Double value = input.inputLossVolume();
        if (value == null || !Double.isFinite(value) || value < 0) {
            throw new BusinessException(400, "井筒损耗气量必须是大于或等于0的有效数字");
        }
        return new Calculation(null, null, null, null, value);
    }

    static void validateFormula(WellboreInput input) {
        requirePositive(input.averageTemperatureK(), "放空井段天然气平均温度");
        // 工具箱公开范围为 -50～200℃，用户界面输入为K。
        if (input.averageTemperatureK() < 223.15 || input.averageTemperatureK() > 473.15) {
            throw new BusinessException(400, "平均温度应在223.15～473.15K之间（PVT范围为-50～200℃）");
        }
        requirePositive(input.pressureBefore(), "放空前井筒平均压力");
        requirePositive(input.pressureAfter(), "放空后井筒平均压力");
        if (input.pressureBefore() > 200 || input.pressureAfter() > 200) {
            throw new BusinessException(400, "PVT计算压力不能超过200MPa");
        }
        if (input.pressureBefore() <= input.pressureAfter()) {
            throw new BusinessException(400, "放空前井筒平均压力必须大于放空后压力");
        }
        List<SegmentInput> segments = input.segments();
        if (segments == null || segments.isEmpty()) throw new BusinessException(400, "至少需要一个放空井段");
        for (int index = 0; index < segments.size(); index++) {
            if (segments.get(index) == null || !Double.isFinite(segments.get(index).segmentVolume())
                    || segments.get(index).segmentVolume() <= 0) {
                throw new BusinessException(400, "第" + (index + 1) + "个放空井段容积必须大于0");
            }
        }
        if (input.gasType() == null || input.gasType() < 0 || input.gasType() > 2) {
            throw new BusinessException(400, "天然气类型不正确");
        }
        requirePositive(input.specificGravity(), "天然气比重");
        requirePercentage(input.h2SMoleFraction(), "H2S摩尔百分含量");
        requirePercentage(input.co2MoleFraction(), "CO2摩尔百分含量");
        requirePercentage(input.n2MoleFraction(), "N2摩尔百分含量");
        if (input.h2SMoleFraction() + input.co2MoleFraction() + input.n2MoleFraction() > 100) {
            throw new BusinessException(400, "气体组分百分含量之和不能超过100%");
        }
        if (input.modificationMethod() == null || input.modificationMethod() < 0 || input.modificationMethod() > 1
                || input.deviationFactorMethod() == null || input.deviationFactorMethod() < 0 || input.deviationFactorMethod() > 2
                || input.viscosityMethod() == null || input.viscosityMethod() < 0 || input.viscosityMethod() > 2) {
            throw new BusinessException(400, "PVT计算方法不正确");
        }
    }

    private static void requirePositive(Double value, String name) {
        if (value == null || !Double.isFinite(value) || value <= 0) {
            throw new BusinessException(400, name + "必须大于0");
        }
    }

    private static void requirePercentage(Double value, String name) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 100) {
            throw new BusinessException(400, name + "必须位于0~100之间");
        }
    }
}
