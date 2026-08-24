package com.grdp.studio.pvtstorage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 单井 PVT 主记录。同一口井通过 (well_id, pvt_no) 区分多个 PVT 性质方案。
 */
@TableName("project_well_pvt")
public class WellPvtEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long wellId;
    private Integer pvtNo;
    private String pvtName;
    private String status;
    private String sourceType;
    private String lastCalculatedKind;
    private String remark;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWellId() { return wellId; }
    public void setWellId(Long wellId) { this.wellId = wellId; }
    public Integer getPvtNo() { return pvtNo; }
    public void setPvtNo(Integer pvtNo) { this.pvtNo = pvtNo; }
    public String getPvtName() { return pvtName; }
    public void setPvtName(String pvtName) { this.pvtName = pvtName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getLastCalculatedKind() { return lastCalculatedKind; }
    public void setLastCalculatedKind(String lastCalculatedKind) { this.lastCalculatedKind = lastCalculatedKind; }
    /** ORM 和后续备注编辑功能预留；当前业务暂不直接调用。 */
    @SuppressWarnings("unused")
    public String getRemark() { return remark; }
    /** ORM 和后续备注编辑功能预留；当前业务暂不直接调用。 */
    @SuppressWarnings("unused")
    public void setRemark(String remark) { this.remark = remark; }
}
