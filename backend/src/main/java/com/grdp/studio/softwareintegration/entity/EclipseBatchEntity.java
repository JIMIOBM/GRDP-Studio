package com.grdp.studio.softwareintegration.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
@TableName("software_integration_eclipse_batch")
public class EclipseBatchEntity {
    @TableId(type=IdType.AUTO) private Long id;
    private Long modelVersionId;
    private Long baselineRunId;
    private String requestKey;
    private String name;
    private String requestJson;
    private String membersJson;
    private LocalDateTime createdAt;
    public Long getId(){return id;}public void setId(Long value){id=value;}
    public Long getModelVersionId(){return modelVersionId;}public void setModelVersionId(Long value){modelVersionId=value;}
    public Long getBaselineRunId(){return baselineRunId;}public void setBaselineRunId(Long value){baselineRunId=value;}
    public String getRequestKey(){return requestKey;}public void setRequestKey(String value){requestKey=value;}
    public String getName(){return name;}public void setName(String value){name=value;}
    public String getRequestJson(){return requestJson;}public void setRequestJson(String value){requestJson=value;}
    public String getMembersJson(){return membersJson;}public void setMembersJson(String value){membersJson=value;}
    public LocalDateTime getCreatedAt(){return createdAt;}public void setCreatedAt(LocalDateTime value){createdAt=value;}
}
