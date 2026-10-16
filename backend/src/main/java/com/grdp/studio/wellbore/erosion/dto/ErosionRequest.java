package com.grdp.studio.wellbore.erosion.dto;

import java.util.Map;

/** Percent fields store paper percentage numerals: 0.01 means 0.01%, and equation (11) receives 0.01. */
public record ErosionRequest(
        Long projectId, Long gasReservoirId, String wellName, Long pvtId,
        Map<String, Object> pvtSnapshot,
        Double pressureMpa, Double temperatureC, Double actualGasRate1e4M3d,
        Double tubingInnerDiameterMm, Double gasDensityKgM3, Double liquidDensityKgM3,
        Double gasVolumeFactor, Double liquidHoldupPercent, Double sandContentPercent,
        Double sandDensityKgM3) {
    public ErosionRequest withProperties(Long id, Map<String, Object> snapshot, double gas, double liquid, double bg) {
        return new ErosionRequest(projectId, gasReservoirId, wellName, id, snapshot,
                pressureMpa, temperatureC, actualGasRate1e4M3d, tubingInnerDiameterMm,
                gas, liquid, bg, liquidHoldupPercent, sandContentPercent, sandDensityKgM3);
    }
}
