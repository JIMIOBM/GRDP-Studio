package com.grdp.studio.softwareintegration.dto.run;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class SoftwareIntegrationCreateRunRequest {
    @Size(max = 255)
    private String study;
    @NotBlank
    private String runType;
    private Object parameters;
    private boolean studyProvided;
    private boolean parametersProvided;

    public String getStudy() { return study; }
    @JsonSetter("study")
    public void setStudy(String study) { this.study = study; this.studyProvided = true; }
    public String getRunType() { return runType; }
    public void setRunType(String runType) { this.runType = runType; }
    public Object getParameters() { return parameters; }
    @JsonSetter("parameters")
    public void setParameters(Object parameters) {
        this.parameters = parameters;
        this.parametersProvided = true;
    }
    public boolean isParametersProvided() { return parametersProvided; }
    public boolean isStudyProvided() { return studyProvided; }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unsupported run request field: " + name);
    }
}
