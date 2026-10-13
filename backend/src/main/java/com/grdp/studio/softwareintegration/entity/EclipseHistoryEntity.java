package com.grdp.studio.softwareintegration.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
@TableName("software_integration_eclipse_history")
public class EclipseHistoryEntity {
    @TableId(type=IdType.AUTO) private Long id;
    private Long modelVersionId;
    private Long baselineRunId;
    private String name;
    private String sourceFile;
    private String sourceSha256;
    private String sourceCsv;
    private Integer observationCount;
    private LocalDateTime createdAt;
    public Long getId(){return id;}
    public void setId(Long value){id=value;}
    public Long getModelVersionId(){return modelVersionId;}
    public void setModelVersionId(Long value){modelVersionId=value;}
    public Long getBaselineRunId(){return baselineRunId;}
    public void setBaselineRunId(Long value){baselineRunId=value;}
    public String getName(){return name;}
    public void setName(String value){name=value;}
    public String getSourceFile(){return sourceFile;}
    public void setSourceFile(String value){sourceFile=value;}
    public String getSourceSha256(){return sourceSha256;}
    public void setSourceSha256(String value){sourceSha256=value;}
    public String getSourceCsv(){return sourceCsv;}
    public void setSourceCsv(String value){sourceCsv=value;}
    public Integer getObservationCount(){return observationCount;}
    public void setObservationCount(Integer value){observationCount=value;}
    public LocalDateTime getCreatedAt(){return createdAt;}
    public void setCreatedAt(LocalDateTime value){createdAt=value;}
}
