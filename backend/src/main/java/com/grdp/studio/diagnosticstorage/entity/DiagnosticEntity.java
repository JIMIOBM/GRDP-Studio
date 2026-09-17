package com.grdp.studio.diagnosticstorage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("project_well_diagnostic")
public class DiagnosticEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("project_id")
    private Long projectId;

    @TableField("gas_reservoir_id")
    private Long gasReservoirId;

    @TableField("well_id")
    private Long wellId;

    @TableField("diagnostic_no")
    private Integer diagnosticNo;

    @TableField("diagnostic_name")
    private String diagnosticName;

    @TableField("pvt_id")
    private Long pvtId;

    @TableField("pvt_name")
    private String pvtName;

    @TableField("pvt_snapshot")
    private String pvtSnapshot;

    @TableField("upper_pressure_limit")
    private Double upperPressureLimit;

    @TableField("lower_pressure_limit")
    private Double lowerPressureLimit;

    @TableField("status")
    private String status;

    @TableField("remark")
    private String remark;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getGasReservoirId() {
        return gasReservoirId;
    }

    public void setGasReservoirId(Long gasReservoirId) {
        this.gasReservoirId = gasReservoirId;
    }

    public Long getWellId() {
        return wellId;
    }

    public void setWellId(Long wellId) {
        this.wellId = wellId;
    }

    public Integer getDiagnosticNo() {
        return diagnosticNo;
    }

    public void setDiagnosticNo(Integer diagnosticNo) {
        this.diagnosticNo = diagnosticNo;
    }

    public String getDiagnosticName() {
        return diagnosticName;
    }

    public void setDiagnosticName(String diagnosticName) {
        this.diagnosticName = diagnosticName;
    }

    public Long getPvtId() {
        return pvtId;
    }

    public void setPvtId(Long pvtId) {
        this.pvtId = pvtId;
    }

    public String getPvtName() {
        return pvtName;
    }

    public void setPvtName(String pvtName) {
        this.pvtName = pvtName;
    }

    public String getPvtSnapshot() {
        return pvtSnapshot;
    }

    public void setPvtSnapshot(String pvtSnapshot) {
        this.pvtSnapshot = pvtSnapshot;
    }

    public Double getUpperPressureLimit() {
        return upperPressureLimit;
    }

    public void setUpperPressureLimit(Double upperPressureLimit) {
        this.upperPressureLimit = upperPressureLimit;
    }

    public Double getLowerPressureLimit() {
        return lowerPressureLimit;
    }

    public void setLowerPressureLimit(Double lowerPressureLimit) {
        this.lowerPressureLimit = lowerPressureLimit;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
