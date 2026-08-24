package com.grdp.studio.pvtstorage.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** 岩石性质基础输入；当前七表结构没有单独的岩石结果表。 */
@TableName("project_well_pvt_rock_input")
public class PvtRockInputEntity extends AbstractPvtChildEntity {
    private Double porosity;
    private String rockType;
    private String calculationMethod;

    public Double getPorosity() { return porosity; }
    public void setPorosity(Double porosity) { this.porosity = porosity; }
    public String getRockType() { return rockType; }
    public void setRockType(String rockType) { this.rockType = rockType; }
    public String getCalculationMethod() { return calculationMethod; }
    public void setCalculationMethod(String calculationMethod) { this.calculationMethod = calculationMethod; }
}
