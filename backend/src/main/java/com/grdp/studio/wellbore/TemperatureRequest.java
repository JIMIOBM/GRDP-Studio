package com.grdp.studio.wellbore;

import java.util.List;

/** SI conversions occur in the calculator; UI units are documented in docs/wellbore-temperature.md. */
public class TemperatureRequest {
    public String tempModel = "alves";
    public String calculationPosition = "wellhead";
    public Long projectId, gasReservoirId, pvtId;
    public String wellName;
    public double fWh = 3.8, roughness = 0.016;
    public Double muL;
    public double depth = 3100, step = 50, idTubing = 62, tGrad = 3, angle = 0;
    public double gammaG = 0.65, rhoL = 1000, tSurf = 20, uTo = 8, wallMm = 6.35;
    public double muJt = 9, cpGas = 2200, formationK = 2.5, formationRhoCp = 2.3;
    public double tWh = 30, qGas = 2.5, qLiq = 2;
    /** Optional external pressures in MPa, one per generated depth; no pressure solver is invoked. */
    public List<Double> pressureProfile;
}
