package com.grdp.studio.gaspvt.dto;

/** 曲线 1 的一个压力点：偏差系数 Z 与拟压力 m(p)。 */
public record GasCurveOnePoint(
        double pressure,
        double temperature,
        double deviationFactor,
        double pseudoPressure
) {
}
