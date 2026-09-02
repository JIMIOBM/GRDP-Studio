package com.grdp.studio.softwareintegration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("software_integration_run")
public class SoftwareIntegrationRunEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long modelId;
    private Long modelVersionId;
    private String studyName;
    private String runType;
    private String parametersJson;
    private String status;
    private Integer statusVersion;
    private Integer timeoutSeconds;
    private String dispatcherId;
    private String workerId;
    private String generationId;
    private LocalDateTime acceptanceUncertainAt;
    private LocalDateTime acceptanceRecoveryDeadlineAt;
    private Long lastWorkerSequence;
    private String cancellationReason;
    private LocalDateTime createdAt;
    private LocalDateTime queuedAt;
    private LocalDateTime claimedAt;
    private LocalDateTime startedAt;
    private LocalDateTime deadlineAt;
    private LocalDateTime finishedAt;
    private Long elapsedMillis;
    private String resultContract;
    private String resultJson;
    private String errorCategory;
    private String errorCode;
    private String errorJson;
    private String cleanupJson;
    private String artifactManifestKey;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public Long getModelVersionId() { return modelVersionId; }
    public void setModelVersionId(Long modelVersionId) { this.modelVersionId = modelVersionId; }
    public String getStudyName() { return studyName; }
    public void setStudyName(String studyName) { this.studyName = studyName; }
    public String getRunType() { return runType; }
    public void setRunType(String runType) { this.runType = runType; }
    public String getParametersJson() { return parametersJson; }
    public void setParametersJson(String parametersJson) { this.parametersJson = parametersJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getStatusVersion() { return statusVersion; }
    public void setStatusVersion(Integer statusVersion) { this.statusVersion = statusVersion; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getDispatcherId() { return dispatcherId; }
    public void setDispatcherId(String dispatcherId) { this.dispatcherId = dispatcherId; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getGenerationId() { return generationId; }
    public void setGenerationId(String generationId) { this.generationId = generationId; }
    public LocalDateTime getAcceptanceUncertainAt() { return acceptanceUncertainAt; }
    public void setAcceptanceUncertainAt(LocalDateTime acceptanceUncertainAt) { this.acceptanceUncertainAt = acceptanceUncertainAt; }
    public LocalDateTime getAcceptanceRecoveryDeadlineAt() { return acceptanceRecoveryDeadlineAt; }
    public void setAcceptanceRecoveryDeadlineAt(LocalDateTime acceptanceRecoveryDeadlineAt) { this.acceptanceRecoveryDeadlineAt = acceptanceRecoveryDeadlineAt; }
    public Long getLastWorkerSequence() { return lastWorkerSequence; }
    public void setLastWorkerSequence(Long lastWorkerSequence) { this.lastWorkerSequence = lastWorkerSequence; }
    public String getCancellationReason() { return cancellationReason; }
    public void setCancellationReason(String cancellationReason) { this.cancellationReason = cancellationReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getQueuedAt() { return queuedAt; }
    public void setQueuedAt(LocalDateTime queuedAt) { this.queuedAt = queuedAt; }
    public LocalDateTime getClaimedAt() { return claimedAt; }
    public void setClaimedAt(LocalDateTime claimedAt) { this.claimedAt = claimedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(LocalDateTime deadlineAt) { this.deadlineAt = deadlineAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public Long getElapsedMillis() { return elapsedMillis; }
    public void setElapsedMillis(Long elapsedMillis) { this.elapsedMillis = elapsedMillis; }
    public String getResultContract() { return resultContract; }
    public void setResultContract(String resultContract) { this.resultContract = resultContract; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getErrorCategory() { return errorCategory; }
    public void setErrorCategory(String errorCategory) { this.errorCategory = errorCategory; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorJson() { return errorJson; }
    public void setErrorJson(String errorJson) { this.errorJson = errorJson; }
    public String getCleanupJson() { return cleanupJson; }
    public void setCleanupJson(String cleanupJson) { this.cleanupJson = cleanupJson; }
    public String getArtifactManifestKey() { return artifactManifestKey; }
    public void setArtifactManifestKey(String artifactManifestKey) { this.artifactManifestKey = artifactManifestKey; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
