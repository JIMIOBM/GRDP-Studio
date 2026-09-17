package com.grdp.studio.wellbore.pressure.method;

/** 原始JS Hagedorn & Brown 关联式的明确入口。 */
public final class HagedornBrownMethod {
    private HagedornBrownMethod() {}
    public static double gradient(double p, double tk, double diameter, double vsg, double vsl, double vm,
                                  double rhoL, double rhoG, double muL, double muG, double sigma, double roughness, double angle) {
        return PressureCorrelations.hagedornBrown(p, tk, diameter, vsg, vsl, vm, rhoL, rhoG, muL, muG, sigma, roughness, angle);
    }
}
