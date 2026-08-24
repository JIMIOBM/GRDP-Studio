package com.grdp.studio.pvtstorage.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** 按 gas/water/rock 保存计算方法及界面参数的 JSON 快照。 */
@TableName("project_well_pvt_settings")
public class PvtSettingsEntity extends AbstractPvtChildEntity {
    private String propertyKind;
    private String settingsJson;

    public String getPropertyKind() { return propertyKind; }
    public void setPropertyKind(String propertyKind) { this.propertyKind = propertyKind; }
    public String getSettingsJson() { return settingsJson; }
    public void setSettingsJson(String settingsJson) { this.settingsJson = settingsJson; }
}
