package com.grdp.studio.wellbore;

/** HB/MB correlations ported from the supplied algorithm, SI units. */
public final class PressureCorrelations {
public static double frictionJain(double diameter_m, double ab_rough, double nre) {
        if (nre <= 2300) return nre > 0 ? 64.0 / nre : 0;
        if (diameter_m <= 0 || nre <= 0) return 0.02;
        double term = ab_rough / diameter_m + 21.25 / Math.pow(nre, 0.9);
        if (term <= 0) term = 1e-6;
        return 1.0 / Math.pow(1.14 - 2 * Math.log10(term), 2);
    }
public static double interpolateLinear(double[] xArr, double[] yArr, double xAim) {
        int n = xArr.length;
        if (xAim <= xArr[0]) return yArr[0];
        if (xAim >= xArr[n - 1]) return yArr[n - 1];
        for (int i = 0; i < n - 1; i++) {
            if (xArr[i] <= xAim && xAim <= xArr[i + 1]) {
                double slope = (yArr[i + 1] - yArr[i]) / (xArr[i + 1] - xArr[i]);
                return yArr[i] + slope * (xAim - xArr[i]);
            }
        }
        return yArr[n - 1];
    }
public static double hagedornBrown(double p_mpa, double t_k, double dia_m, double vsg, double vsl, double vm, double rho_liq, double rho_gas, double vis_liq, double vis_gas, double sigma_gl, double roughness, double angle) {
        double g = 9.81;
        if (vm == 0) return rho_liq * g * Math.cos(angle * Math.PI / 180) * 1e-6;

        double holdupNoSlip = vsl / vm;
        double nl = (vis_liq * 1e-3) * Math.pow(g / rho_liq / Math.pow(sigma_gl, 3), 0.25);
        double cnl = interpolateLinear(
            new double[] {0.0018, 0.004, 0.01, 0.032, 0.045, 0.1, 0.12, 0.15, 0.23},
            new double[] {0.002, 0.0022, 0.0027, 0.004, 0.0049, 0.0061, 0.0067, 0.0071, 0.01}, nl);

        double ngv = vsg * Math.pow(rho_liq / g / sigma_gl, 0.25);
        double nlv = vsl * Math.pow(rho_liq / g / sigma_gl, 0.25);
        double nd = dia_m * Math.sqrt(rho_liq * g / sigma_gl);

        double ngvSafe = ngv == 0 ? 1e-6 : ngv;
        double xDimHl = nlv * Math.pow(p_mpa / 0.101, 0.1) * cnl / nd / Math.pow(ngvSafe, 0.575);
        double hlRatioPsi = interpolateLinear(
            new double[] {5e-6, 1e-5, 2e-5, 9e-5, 1e-4, 2e-4, 4e-4, 1e-3, 4e-3},
            new double[] {0.08, 0.14, 0.205, 0.35, 0.374, 0.46, 0.56, 0.8, 0.947}, xDimHl);
        double xDimPsi = ngv * Math.pow(nl, 0.38) / Math.pow(nd, 2.14);
        double psi = interpolateLinear(
            new double[] {0.02, 0.024, 0.03, 0.035, 0.04, 0.045, 0.05, 0.06, 0.08},
            new double[] {1.12, 1.2, 1.38, 1.51, 1.6, 1.651, 1.7, 1.746, 1.81}, xDimPsi);

        double holdup = hlRatioPsi * psi;
        holdup = Math.max(holdupNoSlip, Math.min(1, holdup));

        double rhoMix = holdup * rho_liq + (1 - holdup) * rho_gas;
        double visMix = Math.pow(vis_liq, holdup) * Math.pow(vis_gas, 1 - holdup);

        double nre = rhoMix * vm * dia_m / (visMix * 1e-3);
        double f = frictionJain(dia_m, roughness, nre);

        double dpGravity = rhoMix * g * Math.cos(angle * Math.PI / 180);
        double area = Math.PI * dia_m * dia_m / 4;
        double massFlow = area * (vsl * rho_liq + vsg * rho_gas);
        double dpFriction = f * massFlow * massFlow / (2 * dia_m * area * area * rhoMix);

        return (dpGravity + dpFriction) * 1e-6;
    }
public static double mukherjeeBrill(double p_mpa, double t_k, double dia_m, double vsg, double vsl, double vm, double rho_liq, double rho_gas, double vis_liq, double vis_gas, double sigma_gl, double roughness, double angle) {
        double g = 9.81;
        if (vm == 0) return rho_liq * g * Math.cos(angle * Math.PI / 180) * 1e-6;

        double holdupNoSlip = vsl / vm;
        double nl = (vis_liq * 1e-3) * Math.pow(g / rho_liq / Math.pow(sigma_gl, 3), 0.25);
        double ngv = vsg * Math.pow(rho_liq / g / sigma_gl, 0.25);
        double nlv = vsl * Math.pow(rho_liq / g / sigma_gl, 0.25);

        double cosTheta = Math.cos(angle * Math.PI / 180);
        double poly = -0.380113 + 0.129875 * cosTheta - 0.119788 * cosTheta * cosTheta + 2.343227 * nl * nl;
        if (nlv < 1e-9) nlv = 1e-9;
        double holdup = Math.exp(poly * Math.pow(ngv, 0.475686) / Math.pow(nlv, 0.288657));
        holdup = Math.max(0, Math.min(1, holdup));
        if (holdup < holdupNoSlip) holdup = holdupNoSlip;

        double rhoMix = holdup * rho_liq + (1 - holdup) * rho_gas;
        double visMixNs = vis_liq * holdupNoSlip + vis_gas * (1 - holdupNoSlip);

        double nre = dia_m * vm * rhoMix / (visMixNs * 1e-3);
        double f = frictionJain(dia_m, roughness, nre);

        double ngvSm = Math.pow(10, 1.401 - 2.694 * nl + 0.521 * Math.pow(nlv, 0.329));
        if (ngv > ngvSm) {
            double ratio = holdup > 0 ? holdupNoSlip / holdup : 1;
            double corr = interpolateLinear(new double[] {0.1, 0.3, 0.4, 0.4001, 1.0}, new double[] {1.0, 1.2, 1.25, 1.3, 1.0}, ratio);
            f *= corr;
        }

        double dpGravity = rhoMix * g * cosTheta;
        double dpFriction = rhoMix * f * vm * vm / (2 * dia_m);
        double ekTerm = rhoMix * vm * vsg / p_mpa * 1e-6;
        double denom = Math.max(0.1, 1 - ekTerm);

        return ((dpGravity + dpFriction) / denom) * 1e-6;
    }
}
