package com.grdp.studio.wellbore.temperature.service;

import com.grdp.studio.wellbore.temperature.dto.TemperatureCalculateRequest;
import com.grdp.studio.wellbore.temperature.model.TemperatureCalculator;
import org.springframework.stereotype.Service;

@Service
public class TemperatureService {
    private final com.grdp.studio.wellbore.pvt.WellborePvtService pvt;
    public TemperatureService(com.grdp.studio.wellbore.pvt.WellborePvtService pvt) { this.pvt = pvt; }

    public TemperatureCalculator.Result calculate(TemperatureCalculateRequest request, String token, String cookie, String environment) {
        var source = pvt.selected(request.pvtId, request.projectId, request.gasReservoirId, request.wellName);
        pvt.validateSnapshot(source, request.pvtId, request.pvtSnapshot);
        var session = pvt.open(source, request.projectId, token, cookie, environment, true);
        var water = session.water().apply(request.referencePressure, request.tWh);
        request.pvtId = source.pvtId();
        request.pvtSnapshot = source.snapshot();
        request.propertySource = "PVT";
        request.gammaG = source.specificGravity();
        request.rhoL = water.density();
        request.muL = water.viscosity();
        return TemperatureCalculator.calculate(request);
    }
}
