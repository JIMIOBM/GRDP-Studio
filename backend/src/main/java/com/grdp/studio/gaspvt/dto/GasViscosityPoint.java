package com.grdp.studio.gaspvt.dto;

/** 曲线 4 的一个压力点：天然气黏度。 */
public record GasViscosityPoint(
        double pressure,
        double temperature,
        double viscosity
) {
}
