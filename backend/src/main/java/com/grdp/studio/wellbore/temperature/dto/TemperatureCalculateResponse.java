package com.grdp.studio.wellbore.temperature.dto;
import com.grdp.studio.wellbore.temperature.model.TemperatureCalculator;
/** 计算结果由模型记录承载，保留DTO名称作为API边界类型。 */
public record TemperatureCalculateResponse(TemperatureCalculator.Result result) { public static TemperatureCalculateResponse from(TemperatureCalculator.Result result){return new TemperatureCalculateResponse(result);} }
