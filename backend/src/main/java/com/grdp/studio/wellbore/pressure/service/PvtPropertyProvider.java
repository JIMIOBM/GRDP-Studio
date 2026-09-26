package com.grdp.studio.wellbore.pressure.service;

import com.grdp.studio.wellbore.pvt.WellborePvtService;
import com.grdp.studio.wellbore.pressure.dto.PressureCalculateRequest;
import com.grdp.studio.wellbore.pressure.method.PressureCalculator;
import org.springframework.stereotype.Service;
import java.util.function.BiFunction;

/** 将用户所选PVT方案适配为HB/MB需要的局部(P,T)物性。 */
@Service
public class PvtPropertyProvider {
    public record Session(double gasSpecificGravity, BiFunction<Double, Double, PressureCalculator.Properties> properties) {}
    private final WellborePvtService pvt;
    public PvtPropertyProvider(WellborePvtService pvt) { this.pvt = pvt; }

    public Session open(PressureCalculateRequest request, String token, String cookie, String processEnv) {
        var source = pvt.selected(request.pvtId, request.projectId, request.gasReservoirId, request.wellName);
        pvt.validateSnapshot(source, request.pvtId, request.pvtSnapshot);
        if ("injection".equals(request.operationMode)) {
            // 单相注气沿用本地DAK/LGE，不创建含水会话，也不要求地层水输入。
            request.pvtId = source.pvtId();
            request.pvtSnapshot = source.snapshot();
            request.gammaG = source.specificGravity();
            request.rhoL = 0;
            request.muL = 0;
            return new Session(request.gammaG, (pressure, temperature) ->
                    com.grdp.studio.wellbore.pressure.method.PressureCorrelations.originalProperties(
                            pressure, temperature + 273.15, request.gammaG, 0, 0));
        }
        var session = pvt.open(source, request.projectId, token, cookie, processEnv, true);
        var boundaryWater = session.water().apply(request.boundaryPressure, request.tWh);
        request.pvtId = source.pvtId();
        request.pvtSnapshot = source.snapshot();
        request.gammaG = source.specificGravity();
        request.rhoL = boundaryWater.density();
        request.muL = boundaryWater.viscosity();
        return new Session(request.gammaG, (pressure, temperature) -> {
            var gp = session.gas().apply(pressure, temperature);
            var wp = session.water().apply(pressure, temperature);
            return new PressureCalculator.Properties(gp.volumeFactor(), gp.density(), gp.viscosity(), wp.density(), wp.viscosity());
        });
    }
}
