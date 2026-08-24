package com.grdp.studio.pvtstorage.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** 地层水基础输入，一条 PVT 主记录最多对应一行。 */
@TableName("project_well_pvt_water_input")
public class PvtWaterInputEntity extends AbstractPvtChildEntity {
    private Double formationPressure;
    private Double formationTemperature;
    private Double salinity;

    public Double getFormationPressure() { return formationPressure; }
    public void setFormationPressure(Double formationPressure) { this.formationPressure = formationPressure; }
    public Double getFormationTemperature() { return formationTemperature; }
    public void setFormationTemperature(Double formationTemperature) { this.formationTemperature = formationTemperature; }
    public Double getSalinity() { return salinity; }
    public void setSalinity(Double salinity) { this.salinity = salinity; }
}
