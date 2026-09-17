package com.grdp.studio.wellbore.risk.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.wellbore.pvt.WellborePvtService;
import com.grdp.studio.wellbore.risk.dto.*;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class WellboreRiskCalculationService {
    public record LiquidCalculation(LiquidLoadingRequest request, LiquidLoadingResult result) {}
    private final WellborePvtService pvt;
    private final LiquidLoadingCalculator liquid;
    private final HydrateCalculator hydrate;
    public WellboreRiskCalculationService(WellborePvtService pvt, LiquidLoadingCalculator liquid, HydrateCalculator hydrate) {
        this.pvt = pvt; this.liquid = liquid; this.hydrate = hydrate;
    }

    public LiquidCalculation calculateLiquid(LiquidLoadingRequest input, String token, String cookie, String environment) {
        WellborePvtService.state(input.pressureMpa(), input.temperatureC());
        var source = pvt.first(input.projectId(), input.gasReservoirId(), input.wellName());
        pvt.validateSnapshot(source, input.pvtId(), input.pvtSnapshot());
        var session = pvt.open(source, input.projectId(), token, cookie, environment, false);
        var gas = session.gas().apply(input.pressureMpa(), input.temperatureC());
        var water = session.water().apply(input.pressureMpa(), input.temperatureC());
        double z = session.z().apply(input.pressureMpa(), input.temperatureC());
        var request = new LiquidLoadingRequest(input.projectId(), input.gasReservoirId(), input.wellName(), input.qg(), input.qw(),
                input.pressureMpa(), input.temperatureC(), source.specificGravity(), water.density(), input.surfaceTensionMnM(),
                input.tubingIdMm(), source.pvtId(), source.snapshot());
        return new LiquidCalculation(request, liquid.calculate(request, gas.density(), z, gas.volumeFactor()));
    }

    public HydrateRequest prepareHydrate(HydrateRequest input) {
        var source = pvt.first(input.projectId(), input.gasReservoirId(), input.wellName());
        pvt.validateSnapshot(source, input.pvtId(), input.pvtSnapshot());
        var base = pvt.gasRequest(source, input.projectId(), input.pressureMpa(), input.actualTemperatureC());
        Map<String, Double> composition = new LinkedHashMap<>();
        if (input.composition() == null || input.composition().isEmpty())
            throw new BusinessException(400, "请补充当前流体的完整气体组成");
        input.composition().forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null || !Double.isFinite(value) || value < 0)
                throw new BusinessException(400, "气体组成必须为非负有效数值");
            composition.merge(name.trim().toUpperCase(java.util.Locale.ROOT), value, Double::sum);
        });
        double total = composition.values().stream().mapToDouble(Double::doubleValue).sum();
        // 保留原接口的摩尔分数输入能力，统一成百分数后与PVT非烃含量核对。
        if (Math.abs(total - 1) < 1e-6) composition.replaceAll((name, value) -> value * 100);
        else if (Math.abs(total - 100) > 0.01)
            throw new BusinessException(400, "请补齐真实气体组成，摩尔百分数总和应为100%");
        var nonHydrocarbons = Map.of("H2S", base.h2SMoleFraction(), "CO2", base.co2MoleFraction(), "N2", base.n2MoleFraction());
        nonHydrocarbons.forEach((name, value) -> {
            if (Math.abs(composition.getOrDefault(name, 0d) - value) > 1e-6)
                throw new BusinessException(400, name + "须与当前井第一条PVT性质一致，请重新读取井数据");
            composition.put(name, value);
        });
        Map<String, Object> snapshot = new LinkedHashMap<>(source.snapshot());
        snapshot.put("compositionSource", "PVT(H2S,CO2,N2); MANUAL(other components)");
        return new HydrateRequest(input.projectId(), input.gasReservoirId(), input.wellName(), source.pvtId(),
                input.temperatureId(), input.pressureConversionId(), composition, input.pressureMpa(),
                input.actualTemperatureC(), input.fugacityScale(), snapshot);
    }

    public HydrateResult calculateHydrate(HydrateRequest input) { return hydrate.calculate(prepareHydrate(input)); }
}
