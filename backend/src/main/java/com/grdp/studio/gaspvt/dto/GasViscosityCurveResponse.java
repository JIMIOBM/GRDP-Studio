package com.grdp.studio.gaspvt.dto;

import java.util.List;

/** 曲线 4 的完整响应：一个黏度 toolbox 和全部压力点。 */
public record GasViscosityCurveResponse(
        long toolboxId,
        List<GasViscosityPoint> items
) {
}
