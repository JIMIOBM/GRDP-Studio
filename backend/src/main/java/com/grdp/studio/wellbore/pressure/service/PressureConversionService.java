package com.grdp.studio.wellbore.pressure.service;

import com.grdp.studio.wellbore.pressure.dto.PressureCalculateRequest;
import com.grdp.studio.wellbore.pressure.method.PressureCalculator;
import org.springframework.stereotype.Service;

@Service
public class PressureConversionService {
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
        return new Calculation(PressureCalculator.calculate(request), request.gammaG);
    }
}
