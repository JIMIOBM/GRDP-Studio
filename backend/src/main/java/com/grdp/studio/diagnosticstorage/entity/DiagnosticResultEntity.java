package com.grdp.studio.diagnosticstorage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("project_well_diagnostic_result")
public class DiagnosticResultEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("diagnostic_id")
    private Long diagnosticId;

    @TableField("base_inventory")
    private Double baseInventory;

    @TableField("min_inventory")
    private Double minInventory;

    @TableField("max_inventory")
    private Double maxInventory;

    @TableField("standard_line_slope")
    private Double standardLineSlope;

    @TableField("result_snapshot")
    private String resultSnapshot;

    @TableField("calculation_version")
    private String calculationVersion;

    @TableField("calculated_at")
    private LocalDateTime calculatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDiagnosticId() {
        return diagnosticId;
    }

    public void setDiagnosticId(Long diagnosticId) {
        this.diagnosticId = diagnosticId;
    }

    public Double getBaseInventory() {
        return baseInventory;
    }

    public void setBaseInventory(Double baseInventory) {
        this.baseInventory = baseInventory;
    }

    public Double getMinInventory() {
        return minInventory;
    }

    public void setMinInventory(Double minInventory) {
        this.minInventory = minInventory;
    }

    public Double getMaxInventory() {
        return maxInventory;
    }

    public void setMaxInventory(Double maxInventory) {
        this.maxInventory = maxInventory;
    }

    public Double getStandardLineSlope() {
        return standardLineSlope;
    }

    public void setStandardLineSlope(Double standardLineSlope) {
        this.standardLineSlope = standardLineSlope;
    }

    public String getResultSnapshot() {
        return resultSnapshot;
    }

    public void setResultSnapshot(String resultSnapshot) {
        this.resultSnapshot = resultSnapshot;
    }

    public String getCalculationVersion() {
        return calculationVersion;
    }

    public void setCalculationVersion(String calculationVersion) {
        this.calculationVersion = calculationVersion;
    }

    public LocalDateTime getCalculatedAt() {
        return calculatedAt;
    }

    public void setCalculatedAt(LocalDateTime calculatedAt) {
        this.calculatedAt = calculatedAt;
    }
}
