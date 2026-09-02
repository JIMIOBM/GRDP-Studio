package com.grdp.studio.softwareintegration.dto.run;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class SoftwareIntegrationCreateRunRequest {
    @NotBlank
    @Size(max = 255)
    private String study;
    @NotBlank
    private String runType;
    private Object parameters;
    private boolean parametersProvided;

    public String getStudy() { return study; }
    public void setStudy(String study) { this.study = study; }
    public String getRunType() { return runType; }
    public void setRunType(String runType) { this.runType = runType; }
    public Object getParameters() { return parameters; }
    @JsonSetter("parameters")
    public void setParameters(Object parameters) {
        this.parameters = parameters;
        this.parametersProvided = true;
    }
    public boolean isParametersProvided() { return parametersProvided; }
}
