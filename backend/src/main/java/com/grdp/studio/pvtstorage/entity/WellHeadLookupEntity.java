package com.grdp.studio.pvtstorage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 井头查询实体，只映射 PVT 定位所需的字段。
 */
@TableName("project_well_heads")
public class WellHeadLookupEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long projectGasReservoirId;
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
