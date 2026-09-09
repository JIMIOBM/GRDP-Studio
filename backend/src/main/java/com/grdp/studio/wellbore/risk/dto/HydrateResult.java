package com.grdp.studio.wellbore.risk.dto;

import java.util.Map;

public record HydrateResult(
        double pressureMpa,
        double temperatureK,
        double rawHydrateTemperatureC,
        double hydrateTemperatureC,
        double actualTemperatureC,
        double temperatureMarginC,
        String riskLevel,
        String riskDescription,
        Map<String, Double> matchedComponents,
        Map<String, Double> droppedComponents,
        String method,
        double fugacityScale,
        double temperatureCorrectionFactor
) {}
