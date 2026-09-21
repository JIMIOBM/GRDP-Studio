package com.grdp.studio.wellbore.pressure.dto;

import java.util.List;

public class PressureCalculateRequest {
    public Long projectId;
    public Long gasReservoirId;
    public Long temperatureRecordId;
    public Long pvtId;
    public java.util.Map<String, Object> pvtSnapshot;
    public String wellName;
    /** 所选注采记录来源，仅用于计算快照追溯。 */
    public String productionRecordKey;
    public String productionDate;
    public String productionChannel;
    public String operationMode = "production";
    public String boundaryPosition = "wellhead";
    public double boundaryPressure = 3.8;
    public double depth = 3100;
    public double step = 50;
    public double idTubing = 62;
    public double roughness = 0.016;
    public double angle = 0;
    public double tWh = 30;
    public double tGrad = 3;
    public double gammaG = 0.65;
    public double rhoL = 1000;
    public double muL = 0.9;
    public double qGas = 2.5;
    public double qLiq = 2;
    public List<String> models = List.of("HB", "MB");
}
