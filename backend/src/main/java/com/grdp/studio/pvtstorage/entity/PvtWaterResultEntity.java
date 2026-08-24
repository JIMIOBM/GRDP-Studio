package com.grdp.studio.pvtstorage.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** 地层水 PVT 结果曲线中的一个压力点。 */
@TableName("project_well_pvt_water_result")
public class PvtWaterResultEntity extends AbstractPvtChildEntity {
    private Integer pointNo;
    private Double pressure;
    private Double temperature;
    private Double salinity;
    private Double gasSolubility;
    private Double volumeFactor;
    private Double density;
    private Double isothermalCompressibility;
    private Double viscosity;

    public Integer getPointNo() { return pointNo; }
    public void setPointNo(Integer pointNo) { this.pointNo = pointNo; }
    public Double getPressure() { return pressure; }
    public void setPressure(Double pressure) { this.pressure = pressure; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getSalinity() { return salinity; }
    public void setSalinity(Double salinity) { this.salinity = salinity; }
    public Double getGasSolubility() { return gasSolubility; }
    public void setGasSolubility(Double gasSolubility) { this.gasSolubility = gasSolubility; }
    public Double getVolumeFactor() { return volumeFactor; }
    public void setVolumeFactor(Double volumeFactor) { this.volumeFactor = volumeFactor; }
    public Double getDensity() { return density; }
    public void setDensity(Double density) { this.density = density; }
    public Double getIsothermalCompressibility() { return isothermalCompressibility; }
    public void setIsothermalCompressibility(Double isothermalCompressibility) { this.isothermalCompressibility = isothermalCompressibility; }
    public Double getViscosity() { return viscosity; }
    public void setViscosity(Double viscosity) { this.viscosity = viscosity; }
}
