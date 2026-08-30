package com.grdp.studio.pvtstorage.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用于按项目、气藏和井名定位井主键的只读映射。
 *
 * <p>PVT 数据只需要读取井记录，不会通过该实体新增或修改井。</p>
 */
@TableName("project_well_heads")
public class WellHeadLookupEntity {

    @TableId
    private Long id;
    private Long projectId;
    private Long projectGasReservoirId;
    private String wellName;

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public Long getProjectGasReservoirId() { return projectGasReservoirId; }
    public String getWellName() { return wellName; }
}
