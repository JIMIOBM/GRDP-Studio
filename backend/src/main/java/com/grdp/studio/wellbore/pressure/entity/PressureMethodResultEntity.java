package com.grdp.studio.wellbore.pressure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("project_well_pressure_method_result")
public class PressureMethodResultEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pressureId;
    private String methodCode;
    private String methodName;
    private String algorithmVersion;
    private String resultSummaryJson;
    private Integer iterationLimit;
    private Integer maxSegmentIterationCount;
    private Integer segmentCount;
    private Integer nonconvergedSegmentCount;
    private Double convergenceToleranceMpa;
    private Double relaxationFactor;
    private Double bottomPressureMpa;
    private Double pressureDifferenceMpa;
    private Boolean converged;
    private LocalDateTime calculatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getPressureId() { return pressureId; }
    public void setPressureId(Long value) { pressureId = value; }
    public String getMethodCode() { return methodCode; }
    public void setMethodCode(String value) { methodCode = value; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String value) { methodName = value; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public void setAlgorithmVersion(String value) { algorithmVersion = value; }
    public String getResultSummaryJson() { return resultSummaryJson; }
    public void setResultSummaryJson(String value) { resultSummaryJson = value; }
    public Integer getIterationLimit() { return iterationLimit; }
    public void setIterationLimit(Integer value) { iterationLimit = value; }
    public Integer getMaxSegmentIterationCount() { return maxSegmentIterationCount; }
    public void setMaxSegmentIterationCount(Integer value) { maxSegmentIterationCount = value; }
    public Integer getSegmentCount() { return segmentCount; }
    public void setSegmentCount(Integer value) { segmentCount = value; }
    public Integer getNonconvergedSegmentCount() { return nonconvergedSegmentCount; }
    public void setNonconvergedSegmentCount(Integer value) { nonconvergedSegmentCount = value; }
    public Double getConvergenceToleranceMpa() { return convergenceToleranceMpa; }
    public void setConvergenceToleranceMpa(Double value) { convergenceToleranceMpa = value; }
    public Double getRelaxationFactor() { return relaxationFactor; }
    public void setRelaxationFactor(Double value) { relaxationFactor = value; }
    public Double getBottomPressureMpa() { return bottomPressureMpa; }
    public void setBottomPressureMpa(Double value) { bottomPressureMpa = value; }
    public Double getPressureDifferenceMpa() { return pressureDifferenceMpa; }
    public void setPressureDifferenceMpa(Double value) { pressureDifferenceMpa = value; }
    public Boolean getConverged() { return converged; }
    public void setConverged(Boolean value) { converged = value; }
    public LocalDateTime getCalculatedAt() { return calculatedAt; }
    public void setCalculatedAt(LocalDateTime value) { calculatedAt = value; }
}
