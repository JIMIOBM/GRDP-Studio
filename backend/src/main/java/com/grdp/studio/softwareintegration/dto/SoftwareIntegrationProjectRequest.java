package com.grdp.studio.softwareintegration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SoftwareIntegrationProjectRequest(
        @NotBlank(message = "项目名称不能为空") @Size(max = 100, message = "项目名称不能超过100个字符") String name,
        @Size(max = 500, message = "项目说明不能超过500个字符") String description
) {}
