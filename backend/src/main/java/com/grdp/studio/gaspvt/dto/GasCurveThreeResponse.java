package com.grdp.studio.gaspvt.dto;

import java.util.List;

/** 曲线 3 的完整响应：一个压缩系数 toolbox 和全部压力点。 */
public record GasCurveThreeResponse(
        long toolboxId,
        List<GasCurveThreePoint> items
) {
}
