package com.grdp.studio.wellbore.pressure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("project_well_pressure_profile")
public class PressureProfileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long methodResultId;
    private Integer pointNo;
    private Integer segmentIterationCount;
    private Double depthM;
    private Double temperatureC;
    private Double pressureMpa;
    private Double segmentAvgPressureMpa;
    private Double segmentAvgTemperatureC;
    private Double gasDensityKgM3;
    private Double gasViscosityMpas;
    private Double gasVolumeFactor;
    private Double liquidDensityKgM3;
    private Double liquidViscosityMpas;
    private Double pressureGradientMpaPerM;
    private Boolean segmentConverged;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMethodResultId() { return methodResultId; }
    public void setMethodResultId(Long value) { methodResultId = value; }
    public Integer getPointNo() { return pointNo; }
    public void setPointNo(Integer value) { pointNo = value; }
    public Integer getSegmentIterationCount() { return segmentIterationCount; }
    public void setSegmentIterationCount(Integer value) { segmentIterationCount = value; }
    public Double getDepthM() { return depthM; }
    public void setDepthM(Double value) { depthM = value; }
    public Double getTemperatureC() { return temperatureC; }
    public void setTemperatureC(Double value) { temperatureC = value; }
    public Double getPressureMpa() { return pressureMpa; }
    public void setPressureMpa(Double value) { pressureMpa = value; }
    public Double getSegmentAvgPressureMpa() { return segmentAvgPressureMpa; }
    public void setSegmentAvgPressureMpa(Double value) { segmentAvgPressureMpa = value; }
    public Double getSegmentAvgTemperatureC() { return segmentAvgTemperatureC; }
    public void setSegmentAvgTemperatureC(Double value) { segmentAvgTemperatureC = value; }
    public Double getGasDensityKgM3() { return gasDensityKgM3; }
    public void setGasDensityKgM3(Double value) { gasDensityKgM3 = value; }
    public Double getGasViscosityMpas() { return gasViscosityMpas; }
    public void setGasViscosityMpas(Double value) { gasViscosityMpas = value; }
    public Double getGasVolumeFactor() { return gasVolumeFactor; }
    public void setGasVolumeFactor(Double value) { gasVolumeFactor = value; }
    public Double getLiquidDensityKgM3() { return liquidDensityKgM3; }
    public void setLiquidDensityKgM3(Double value) { liquidDensityKgM3 = value; }
    public Double getLiquidViscosityMpas() { return liquidViscosityMpas; }
    public void setLiquidViscosityMpas(Double value) { liquidViscosityMpas = value; }
    public Double getPressureGradientMpaPerM() { return pressureGradientMpaPerM; }
    public void setPressureGradientMpaPerM(Double value) { pressureGradientMpaPerM = value; }
    public Boolean getSegmentConverged() { return segmentConverged; }
    public void setSegmentConverged(Boolean value) { segmentConverged = value; }
}
