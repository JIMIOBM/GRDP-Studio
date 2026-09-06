package com.grdp.studio.wellbore.temperature.service;

import com.grdp.studio.wellbore.temperature.dto.TemperatureCalculateRequest;
import com.grdp.studio.wellbore.temperature.model.TemperatureCalculator;
import org.springframework.stereotype.Service;

@Service
public class TemperatureService {
    public TemperatureCalculator.Result calculate(TemperatureCalculateRequest request) {
        return TemperatureCalculator.calculate(request);
    }
}
