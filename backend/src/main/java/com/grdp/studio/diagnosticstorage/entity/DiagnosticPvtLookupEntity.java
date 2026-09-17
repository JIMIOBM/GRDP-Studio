package com.grdp.studio.diagnosticstorage.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 只用于校验所选 PVT 是否属于当前井，并取得保存时的 PVT 名称。
 */
@TableName("project_well_pvt")
public class DiagnosticPvtLookupEntity {

    @TableId("id")
    private Long id;

    @TableField("well_id")
    private Long wellId;

    @TableField("pvt_no")
    private Integer pvtNo;

    @TableField("pvt_name")
    private String pvtName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getWellId() {
        return wellId;
    }

    public void setWellId(Long wellId) {
        this.wellId = wellId;
    }

    public Integer getPvtNo() {
        return pvtNo;
    }

    public void setPvtNo(Integer pvtNo) {
        this.pvtNo = pvtNo;
    }

    public String getPvtName() {
        return pvtName;
    }

    public void setPvtName(String pvtName) {
        this.pvtName = pvtName;
    }
}
