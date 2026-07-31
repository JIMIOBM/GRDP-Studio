package com.grdp.studio.gaspvt.dto;

/** 曲线 3 的一个压力点：天然气压缩系数。 */
public record GasCurveThreePoint(
        double pressure,
        double temperature,
        double compressibility
) {
}
