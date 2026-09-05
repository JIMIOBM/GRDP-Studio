package com.grdp.studio.wellbore;

import com.grdp.studio.common.*;
import com.grdp.studio.pvtstorage.service.PvtStorageService;
import com.grdp.studio.gaspvt.service.GasPvtService;
import com.grdp.studio.gaspvt.dto.GasViscosityCurveRequest;
import com.grdp.studio.waterpvt.service.WaterPvtService;
import com.grdp.studio.waterpvt.dto.WaterPvtCurveRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/wellbore/pressure")
public class PressureController {
    private final PvtStorageService storage;
    private final GasPvtService gas;
    private final WaterPvtService water;
    public PressureController(PvtStorageService storage, GasPvtService gas, WaterPvtService water) {
        this.storage = storage; this.gas = gas; this.water = water;
    }
    @PostMapping("/calculate")
    public ApiResponse<PressureCalculator.Result> calculate(@RequestBody PressureRequest p,
            @RequestHeader(value="token", required=false) String token,
            @RequestHeader(value="Cookie", required=false) String cookie,
            @RequestHeader(value="Process-Env", required=false) String env) {
        PressureCalculator.validate(p);
        if (p.pvtId == null || p.projectId == null || p.gasReservoirId == null || p.wellName == null) throw new BusinessException(400,"请先完成当前井温度模型计算及PVT设置");
        var detail = storage.getDetail(p.pvtId, p.projectId, p.gasReservoirId, p.wellName);
        var g = detail.gasInput(); var w = detail.waterInput();
        if (g == null || w == null || w.salinity() == null || w.formationPressure() == null) throw new BusinessException(400,"压力折算需要完整的气体及地层水PVT输入");
        if (g.specificGravity() == null || !Double.isFinite(g.specificGravity()) || g.specificGravity() <= 0
                || !Double.isFinite(w.salinity()) || w.salinity() < 0 || !Double.isFinite(w.formationPressure()) || w.formationPressure() <= 0)
            throw new BusinessException(400,"PVT天然气比重、地层水矿化度或原始压力无效");
        var settings = detail.settings() == null ? java.util.Map.<String,String>of() : detail.settings();
        var base = new GasViscosityCurveRequest(p.projectId, method(g.gasType(), List.of("干气","湿气","凝析气")), g.specificGravity(),
                g.hydrogenSulfide(), g.carbonDioxide(), g.nitrogen(), p.tWh, p.fWh, p.fWh, 1d,
                method(settings.get("gasCorrectionMethod"), List.of("Wichert-Aziz 修正方法","Carr-Kobayashi-Burrous 修正方法")),
                method(settings.get("deviationFactorMethod"), List.of("Dranchuk-Abu-Kassem 方法","Dranchuk-Purvis-Robinson 方法","Hall-Yarborough 方法")),
                method(settings.get("viscosityMethod"), List.of("Lee-Gonzalez-Eakin 方法","Carr-Kobayashi-Burrous 方法","Sutton 方法")));
        var gasSession = gas.flowSession(base, token, cookie, env);
        var waterSession = water.flowSession(new WaterPvtCurveRequest(p.projectId, w.salinity(), w.formationPressure(), p.tWh, p.fWh, p.fWh, 1d,
                method(settings.get("volumeFactorMethod"), List.of("McCain方法","Standing方法")),
                method(settings.get("compressibilityMethod"), List.of("Meehan方法","Dodson-Standing方法"))), true, token, cookie, env);
        p.gammaG = g.specificGravity();
        return ApiResponse.success(PressureCalculator.calculate(p, (pressure, temperature) -> {
            var gp = gasSession.apply(pressure, temperature);
            var wp = waterSession.apply(pressure, temperature);
            return new PressureCalculator.Properties(gp.density(), gp.viscosity(), wp.density(), wp.viscosity());
        }));
    }
    private static int method(String value, List<String> options) {
        if (value == null || value.isBlank()) return 0;
        int index = options.indexOf(value);
        if (index < 0) throw new BusinessException(400,"无法识别PVT计算方法：" + value);
        return index;
    }
}
