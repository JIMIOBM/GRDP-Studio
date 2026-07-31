package com.grdp.studio.gaspvt.dto;

import java.util.List;

/**
 * 曲线 2 的完整响应。
 * volumeFactorToolboxId 对应 GasPVT_VolumeFactor，densityToolboxId 对应 GasPVT_Density。
 */
public record GasCurveTwoResponse(
        long volumeFactorToolboxId,
        long densityToolboxId,
        List<GasCurveTwoPoint> items
) {
}
