package com.grdp.studio.wellbore;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.pvtstorage.service.PvtStorageService;
import com.grdp.studio.waterpvt.service.WaterPvtService;
import com.grdp.studio.waterpvt.dto.WaterPvtCurveRequest;
import org.springframework.stereotype.Service;

@Service
public class TemperatureService {
    private final PvtStorageService pvtStorage;
    private final WaterPvtService waterPvt;
    public TemperatureService(PvtStorageService pvtStorage, WaterPvtService waterPvt) {
        this.pvtStorage = pvtStorage;
        this.waterPvt = waterPvt;
    }

    public TemperatureCalculator.Result calculate(TemperatureRequest p, String token, String cookie, String processEnv) {
        var result = TemperatureCalculator.calculate(p); // Validate before calling the external calculator.
        if (p.pvtId == null || p.qLiq == 0) return result;
        if (p.projectId == null || p.gasReservoirId == null || p.wellName == null || p.wellName.isBlank())
            throw new BusinessException(400, "PVT迭代需要项目、气藏和井名");
        var detail = pvtStorage.getDetail(p.pvtId, p.projectId, p.gasReservoirId, p.wellName);
        if (detail.waterInput() == null || detail.waterInput().salinity() == null || detail.waterInput().formationPressure() == null)
            throw new BusinessException(400, "请先保存当前井的PVT地层水矿化度和原始地层压力");
        if (!Double.isFinite(detail.waterInput().salinity()) || detail.waterInput().salinity() < 0
                || !Double.isFinite(detail.waterInput().formationPressure()) || detail.waterInput().formationPressure() <= 0)
            throw new BusinessException(400, "PVT地层水矿化度必须非负，原始压力必须为正数");
        if (detail.gasInput() != null && detail.gasInput().specificGravity() != null) p.gammaG = detail.gasInput().specificGravity();
        result = TemperatureCalculator.calculate(p);
        var settings = detail.settings();
        String volume = settings == null ? "McCain方法" : settings.getOrDefault("volumeFactorMethod", "McCain方法");
        String compressibility = settings == null ? "Meehan方法" : settings.getOrDefault("compressibilityMethod", "Meehan方法");
        if (!java.util.List.of("McCain方法", "Standing方法").contains(volume) || !java.util.List.of("Meehan方法", "Dodson-Standing方法").contains(compressibility))
            throw new BusinessException(400, "无法识别PVT地层水计算方法");
        double meanTemperature = meanTemperature(result);
        var waterBase = new WaterPvtCurveRequest(p.projectId, detail.waterInput().salinity(),
                detail.waterInput().formationPressure(), meanTemperature, p.fWh, p.fWh, 1d,
                "Standing方法".equals(volume) ? 1 : 0, "Dodson-Standing方法".equals(compressibility) ? 1 : 0);
        var waterSession = waterPvt.flowSession(waterBase, false, token, cookie, processEnv);
        // Scalar thermal model: iterate representative water properties, not a fabricated pressure profile.
        for (int iteration = 1; iteration <= 8; iteration++) {
            double density = waterSession.apply(p.fWh, meanTemperature).density();
            double previousDensity = p.rhoL;
            p.rhoL = density;
            p.muL = null; // Temperature energy balance does not consume viscosity.
            result = TemperatureCalculator.calculate(p, iteration);
            double nextTemperature = meanTemperature(result);
            if (Math.abs(nextTemperature - meanTemperature) < 0.01 && Math.abs(density - previousDensity) / density < 1e-5) return result;
            meanTemperature = nextTemperature;
        }
        throw new BusinessException(422, "地层水物性迭代8次未收敛，请检查PVT参数和生产数据");
    }

    private static double meanTemperature(TemperatureCalculator.Result result) {
        double integral = 0;
        for (int i = 1; i < result.depth().size(); i++) integral += (result.temp().get(i) + result.temp().get(i - 1)) / 2
                * (result.depth().get(i) - result.depth().get(i - 1));
        return integral / result.depth().getLast();
    }
}
