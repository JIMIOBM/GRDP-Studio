package com.grdp.studio.gaspvt.dto;

/** 曲线 2 的一个压力点：天然气体积系数与天然气密度。 */
public record GasCurveTwoPoint(
        double pressure,
        double temperature,
        double volumeFactor,
        double density
) {
}
