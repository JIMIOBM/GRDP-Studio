package com.grdp.studio.wellbore.sand.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 按支撑剂类型与闭合压力查询临界出砂流速（页面在填写闭合压力后自动回填输入框）。 */
public record SandCriticalVelocityRequest(
        @NotBlank(message = "支撑剂类型不能为空") String proppantType,
        @NotNull(message = "闭合压力不能为空") @Positive Double closurePressureMpa
) {}
