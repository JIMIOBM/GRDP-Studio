package com.grdp.studio.pvtstorage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 六张 PVT 子表的公共关联字段。
 * pvt_id 统一指向 project_well_pvt.id，项目、气藏和井信息通过主表的 well_id 间接获取。
 */
public abstract class AbstractPvtChildEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long pvtId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPvtId() {
        return pvtId;
    }

    public void setPvtId(Long pvtId) {
        this.pvtId = pvtId;
    }
}
