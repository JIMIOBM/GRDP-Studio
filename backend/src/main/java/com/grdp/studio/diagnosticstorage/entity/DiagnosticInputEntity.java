package com.grdp.studio.diagnosticstorage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("project_well_diagnostic_input")
public class DiagnosticInputEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("diagnostic_id")
    private Long diagnosticId;

    @TableField("sequence_no")
    private Integer sequenceNo;

    @TableField("time_text")
    private String timeText;

    @TableField("cycle_name")
    private String cycleName;

    /**
     * 数据库存储单位：10^4 m3。
     */
    @TableField("gas_volume")
    private Double gasVolume;

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

    public Integer getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(Integer sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public String getTimeText() {
        return timeText;
    }

    public void setTimeText(String timeText) {
        this.timeText = timeText;
    }

    public String getCycleName() {
        return cycleName;
    }

    public void setCycleName(String cycleName) {
        this.cycleName = cycleName;
    }

    public Double getGasVolume() {
        return gasVolume;
    }

    public void setGasVolume(Double gasVolume) {
        this.gasVolume = gasVolume;
    }
}
