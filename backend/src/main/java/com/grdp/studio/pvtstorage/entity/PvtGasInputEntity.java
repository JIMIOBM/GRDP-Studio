package com.grdp.studio.pvtstorage.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** 天然气基础输入，一条 PVT 主记录最多对应一行。 */
@TableName("project_well_pvt_gas_input")
public class PvtGasInputEntity extends AbstractPvtChildEntity {
    private String gasType;
    private Double specificGravity;
    private Double hydrogenSulfide;
    private Double carbonDioxide;
    private Double nitrogen;
    private Double condensateOilDensity;

    public String getGasType() { return gasType; }
    public void setGasType(String gasType) { this.gasType = gasType; }
    public Double getSpecificGravity() { return specificGravity; }
    public void setSpecificGravity(Double specificGravity) { this.specificGravity = specificGravity; }
    public Double getHydrogenSulfide() { return hydrogenSulfide; }
    public void setHydrogenSulfide(Double hydrogenSulfide) { this.hydrogenSulfide = hydrogenSulfide; }
    public Double getCarbonDioxide() { return carbonDioxide; }
    public void setCarbonDioxide(Double carbonDioxide) { this.carbonDioxide = carbonDioxide; }
    public Double getNitrogen() { return nitrogen; }
    public void setNitrogen(Double nitrogen) { this.nitrogen = nitrogen; }
    public Double getCondensateOilDensity() { return condensateOilDensity; }
    public void setCondensateOilDensity(Double condensateOilDensity) { this.condensateOilDensity = condensateOilDensity; }
}
