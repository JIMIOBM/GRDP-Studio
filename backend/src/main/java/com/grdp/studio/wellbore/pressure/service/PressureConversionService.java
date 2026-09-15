package com.grdp.studio.wellbore.pressure.service;

import com.grdp.studio.wellbore.pressure.dto.PressureCalculateRequest;
import com.grdp.studio.wellbore.pressure.method.PressureCalculator;
import org.springframework.stereotype.Service;

@Service
public class PressureConversionService {
    private final PvtPropertyProvider pvt;
    public PressureConversionService(PvtPropertyProvider pvt) { this.pvt = pvt; }
    public record Calculation(
            PressureCalculator.Result result,
            double gasSpecificGravity
    ) {}

    public PressureCalculator.Result calculate(
            PressureCalculateRequest request,
            String token,
            String cookie,
            String environment
    ) {
        return calculateDetailed(request, token, cookie, environment).result();
    }

    public Calculation calculateDetailed(
            PressureCalculateRequest request,
            String token,
            String cookie,
            String environment
    ) {
        var session = pvt.open(request, token, cookie, environment);
        return new Calculation(PressureCalculator.calculate(request, session.properties()), session.gasSpecificGravity());
    }
}
