package com.grdp.studio.pvtstorage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** PVT 保存时用于按项目、气藏和井名定位 project_well_heads 主键。 */
@TableName("project_well_heads")
public class WellHeadLookupEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String wellName;
    private Long projectGasReservoirId;
    private Long projectId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWellName() { return wellName; }
    public void setWellName(String wellName) { this.wellName = wellName; }
    public Long getProjectGasReservoirId() { return projectGasReservoirId; }
    public void setProjectGasReservoirId(Long projectGasReservoirId) {
        this.projectGasReservoirId = projectGasReservoirId;
    }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
}
