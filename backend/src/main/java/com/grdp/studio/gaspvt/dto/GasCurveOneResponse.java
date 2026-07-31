package com.grdp.studio.gaspvt.dto;

import java.util.List;

/**
 * 曲线 1 的完整响应。
 * 两个 toolboxId 分别对应偏差系数算法和拟压力算法，items 是前端表格/图表使用的压力点。
 */
public record GasCurveOneResponse(
        long deviationFactorToolboxId,
        long pseudoPressureToolboxId,
        List<GasCurveOnePoint> items
) {
}
