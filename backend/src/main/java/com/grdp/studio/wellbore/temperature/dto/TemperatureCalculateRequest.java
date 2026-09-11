package com.grdp.studio.wellbore.temperature.dto;

/** SI conversions occur in the calculator; UI units are documented in docs/wellbore-temperature.md. */
public class TemperatureCalculateRequest {
    public String tempModel = "alves";
    /** 独立 Alves 温度算法当前仅接受井口边界。 */
    public String boundaryPosition = "wellhead";
    public String propertySource = "MANUAL";
    public Long projectId, gasReservoirId, pvtId;
    public String wellName;
    /** 仅作为物性评价与输入快照保存，不构造压力剖面。 */
    public double referencePressure = 3.8;
    public double depth = 3100, step = 50, idTubing = 62, tGrad = 3, angle = 0;
    public double gammaG = 0.65, rhoL = 1000, muL = 0.9, roughness = 0.016, tSurf = 20, uTo = 8, wallMm = 6.35;
    public double muJt = 9, cpGas = 2200, formationK = 2.5, formationRhoCp = 2.3;
    public double tWh = 30, qGas = 2.5, qLiq = 2;
}
