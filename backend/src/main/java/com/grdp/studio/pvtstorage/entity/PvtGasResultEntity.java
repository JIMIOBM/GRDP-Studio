package com.grdp.studio.pvtstorage.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** 天然气 PVT 结果曲线中的一个压力点。 */
@TableName("project_well_pvt_gas_result")
public class PvtGasResultEntity extends AbstractPvtChildEntity {
    private Integer pointNo;
    private Double pressure;
    private Double temperature;
    private Double deviationFactor;
    private Double pseudoPressure;
    private Double volumeFactor;
    private Double density;
    private Double compressibility;
    private Double viscosity;

    public Integer getPointNo() { return pointNo; }
    public void setPointNo(Integer pointNo) { this.pointNo = pointNo; }
    public Double getPressure() { return pressure; }
    public void setPressure(Double pressure) { this.pressure = pressure; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getDeviationFactor() { return deviationFactor; }
    public void setDeviationFactor(Double deviationFactor) { this.deviationFactor = deviationFactor; }
    public Double getPseudoPressure() { return pseudoPressure; }
    public void setPseudoPressure(Double pseudoPressure) { this.pseudoPressure = pseudoPressure; }
    public Double getVolumeFactor() { return volumeFactor; }
    public void setVolumeFactor(Double volumeFactor) { this.volumeFactor = volumeFactor; }
    public Double getDensity() { return density; }
    public void setDensity(Double density) { this.density = density; }
    public Double getCompressibility() { return compressibility; }
    public void setCompressibility(Double compressibility) { this.compressibility = compressibility; }
    public Double getViscosity() { return viscosity; }
    public void setViscosity(Double viscosity) { this.viscosity = viscosity; }
}
