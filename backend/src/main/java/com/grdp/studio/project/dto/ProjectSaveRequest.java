package com.grdp.studio.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectSaveRequest(
        @NotBlank(message = "项目名称不能为空")
        @Size(max = 100, message = "项目名称不能超过 100 个字符")
        String name,

        @Size(max = 500, message = "项目描述不能超过 500 个字符")
        String description
) {
}
