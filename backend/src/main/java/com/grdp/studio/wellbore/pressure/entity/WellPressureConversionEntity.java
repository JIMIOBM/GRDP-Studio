package com.grdp.studio.wellbore.pressure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("project_well_pressure_conversion")
public class WellPressureConversionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long wellId;
    private Long temperatureId;
    private Long pvtId;
    private Integer pressureNo;
    private String pressureName;
    private String operationMode;
    private String boundaryPosition;
    private String status;
    private String inputJson;
    private String remark;
    private Double boundaryPressureMpa;
    private Double idTubingMm;
    private Double roughnessMm;
    private Double angleDeg;

    @TableField("q_gas_1e4_m3d")
    private Double qGas1e4M3d;

    @TableField("q_liq_m3d")
    private Double qLiqM3d;

    private Double gasSpecificGravity;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getWellId() { return wellId; }
    public void setWellId(Long value) { wellId = value; }
    public Long getTemperatureId() { return temperatureId; }
    public void setTemperatureId(Long value) { temperatureId = value; }
    public Long getPvtId() { return pvtId; }
    public void setPvtId(Long value) { pvtId = value; }
    public Integer getPressureNo() { return pressureNo; }
    public void setPressureNo(Integer value) { pressureNo = value; }
    public String getPressureName() { return pressureName; }
    public void setPressureName(String value) { pressureName = value; }
    public String getOperationMode() { return operationMode; }
    public void setOperationMode(String value) { operationMode = value; }
    public String getBoundaryPosition() { return boundaryPosition; }
    public void setBoundaryPosition(String value) { boundaryPosition = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String value) { inputJson = value; }
    public String getRemark() { return remark; }
    public void setRemark(String value) { remark = value; }
    public Double getBoundaryPressureMpa() { return boundaryPressureMpa; }
    public void setBoundaryPressureMpa(Double value) { boundaryPressureMpa = value; }
    public Double getIdTubingMm() { return idTubingMm; }
    public void setIdTubingMm(Double value) { idTubingMm = value; }
    public Double getRoughnessMm() { return roughnessMm; }
    public void setRoughnessMm(Double value) { roughnessMm = value; }
    public Double getAngleDeg() { return angleDeg; }
    public void setAngleDeg(Double value) { angleDeg = value; }
    public Double getQGas1e4M3d() { return qGas1e4M3d; }
    public void setQGas1e4M3d(Double value) { qGas1e4M3d = value; }
    public Double getQLiqM3d() { return qLiqM3d; }
    public void setQLiqM3d(Double value) { qLiqM3d = value; }
    public Double getGasSpecificGravity() { return gasSpecificGravity; }
    public void setGasSpecificGravity(Double value) { gasSpecificGravity = value; }
}
