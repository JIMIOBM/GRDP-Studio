package com.grdp.studio.diagnosticstorage.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 只用于把 projectId + gasReservoirId + wellName 定位成真实 well_id。
 * 字段命名与现有 PVT 存储服务使用的 project_well_heads 保持一致。
 */
@TableName("project_well_heads")
public class DiagnosticWellLookupEntity {

    @TableId("id")
    private Long id;

    @TableField("project_id")
    private Long projectId;

    @TableField("project_gas_reservoir_id")
    private Long projectGasReservoirId;

    @TableField("well_name")
    private String wellName;

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

    public Long getProjectGasReservoirId() {
        return projectGasReservoirId;
    }

    public void setProjectGasReservoirId(Long projectGasReservoirId) {
        this.projectGasReservoirId = projectGasReservoirId;
    }

    public String getWellName() {
        return wellName;
    }

    public void setWellName(String wellName) {
        this.wellName = wellName;
    }
}
