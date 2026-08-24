package com.grdp.studio.pvtstorage.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** 岩石 PVT 结果曲线中的一个孔隙度点。 */
@TableName("project_well_pvt_rock_result")
public class PvtRockResultEntity extends AbstractPvtChildEntity {
    private String curveType;
    private Integer pointNo;
    private Double porosity;
    private Double compressibilityFactor;

    public String getCurveType() { return curveType; }
    public void setCurveType(String curveType) { this.curveType = curveType; }
    public Integer getPointNo() { return pointNo; }
    public void setPointNo(Integer pointNo) { this.pointNo = pointNo; }
    public Double getPorosity() { return porosity; }
    public void setPorosity(Double porosity) { this.porosity = porosity; }
    public Double getCompressibilityFactor() { return compressibilityFactor; }
    public void setCompressibilityFactor(Double compressibilityFactor) {
        this.compressibilityFactor = compressibilityFactor;
    }
}
