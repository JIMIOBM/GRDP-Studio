package com.grdp.studio.wellbore.pressure.method;

/** 原始JS Mukherjee & Brill 关联式的明确入口。 */
public final class MukherjeeBrillMethod {
    private MukherjeeBrillMethod() {}
    public static double gradient(double p, double tk, double diameter, double vsg, double vsl, double vm,
                                  double rhoL, double rhoG, double muL, double muG, double sigma, double roughness, double angle) {
        return PressureCorrelations.mukherjeeBrill(p, tk, diameter, vsg, vsl, vm, rhoL, rhoG, muL, muG, sigma, roughness, angle);
    }
}
