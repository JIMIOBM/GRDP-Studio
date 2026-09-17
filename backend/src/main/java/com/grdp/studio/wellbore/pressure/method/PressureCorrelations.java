package com.grdp.studio.wellbore.pressure.method;

/** HB/MB correlations ported from the supplied algorithm, SI units. */
public final class PressureCorrelations {
    private PressureCorrelations() {}

    public static PressureCalculator.Properties originalProperties(
            double pressureMpa,
            double temperatureKelvin,
            double gasSpecificGravity,
            double liquidDensity,
            double liquidViscosity
    ) {
        double pressurePa = pressureMpa * 1_000_000;
        double pressurePsi = pressurePa * 0.000145038;
        double temperatureRankine = temperatureKelvin * 1.8;
        double pseudoCriticalPressure = 678 - 50 * (gasSpecificGravity - 0.5);
        double pseudoCriticalTemperature = 326 + 315.7 * (gasSpecificGravity - 0.5);
        double pseudoReducedPressure = pressurePsi / pseudoCriticalPressure;
        double pseudoReducedTemperature = temperatureRankine / pseudoCriticalTemperature;
        double zFactor = zFactorDak(pseudoReducedPressure, pseudoReducedTemperature);
        double molarMass = gasSpecificGravity * 0.02896;
        double gasDensity = pressurePa * molarMass / (zFactor * 8.314 * temperatureKelvin);
        double gasViscosity = gasViscosityLge(
                gasSpecificGravity,
                temperatureKelvin,
                pressureMpa,
                zFactor
        );
        double gasVolumeFactor = temperatureKelvin * zFactor * 0.101325
                / (pressureMpa * 293.15);

        return new PressureCalculator.Properties(
                gasVolumeFactor,
                gasDensity,
                gasViscosity,
                liquidDensity,
                liquidViscosity
        );
    }

    public static double zFactorDak(double pseudoReducedPressure, double pseudoReducedTemperature) {
        double a1 = 0.3265;
        double a2 = -1.0700;
        double a3 = -0.5339;
        double a4 = 0.01569;
        double a5 = -0.05165;
        double a6 = 0.5475;
        double a7 = -0.7361;
        double a8 = 0.1844;
        double a9 = 0.1056;
        double a10 = 0.6134;
        double a11 = 0.7210;

        double r1 = a1
                + a2 / pseudoReducedTemperature
                + a3 / Math.pow(pseudoReducedTemperature, 3)
                + a4 / Math.pow(pseudoReducedTemperature, 4)
                + a5 / Math.pow(pseudoReducedTemperature, 5);
        double r2 = 0.27 * pseudoReducedPressure / pseudoReducedTemperature;
        double r3 = a6
                + a7 / pseudoReducedTemperature
                + a8 / Math.pow(pseudoReducedTemperature, 2);
        double r4 = a9 * (
                a7 / pseudoReducedTemperature
                        + a8 / Math.pow(pseudoReducedTemperature, 2)
        );
        double r5 = a10 / Math.pow(pseudoReducedTemperature, 3);

        double reducedDensity = 0.27 * pseudoReducedPressure / pseudoReducedTemperature;
        for (int iteration = 0; iteration < 50; iteration++) {
            if (reducedDensity <= 0) {
                reducedDensity = 1e-5;
            }
            double exponential = Math.exp(-a11 * reducedDensity * reducedDensity);
            double function = r1 * reducedDensity
                    - r2 / reducedDensity
                    + r3 * reducedDensity * reducedDensity
                    - r4 * Math.pow(reducedDensity, 5)
                    + r5 * reducedDensity * reducedDensity
                    * (1 + a11 * reducedDensity * reducedDensity)
                    * exponential
                    + 1;
            double bracket = (1 + 2 * a11 * reducedDensity * reducedDensity)
                    - a11 * reducedDensity * reducedDensity
                    * (1 + a11 * reducedDensity * reducedDensity);
            double derivative = r1
                    + r2 / (reducedDensity * reducedDensity)
                    + 2 * r3 * reducedDensity
                    - 5 * r4 * Math.pow(reducedDensity, 4)
                    + 2 * r5 * reducedDensity * exponential * bracket;

            if (Math.abs(derivative) < 1e-10) {
                break;
            }
            double nextDensity = reducedDensity - function / derivative;
            if (Math.abs(nextDensity - reducedDensity) < 1e-8) {
                return 0.27 * pseudoReducedPressure
                        / (nextDensity * pseudoReducedTemperature);
            }
            reducedDensity = nextDensity;
        }
        return 0.9;
    }

    public static double gasViscosityLge(
            double gasSpecificGravity,
            double temperatureKelvin,
            double pressureMpa,
            double zFactor
    ) {
        double molecularWeight = 28.97 * gasSpecificGravity;
        double x = 3.5 + 986 / (1.8 * temperatureKelvin) + 0.01 * molecularWeight;
        double y = 2.4 - 0.2 * x;
        double k = (9.4 + 0.02 * molecularWeight)
                * Math.pow(1.8 * temperatureKelvin, 1.5)
                / (209 + 19 * molecularWeight + 1.8 * temperatureKelvin);

        if (zFactor == 0 || temperatureKelvin == 0) {
            return 0.018;
        }
        double r1 = 3.4844 * gasSpecificGravity * pressureMpa
                / zFactor / temperatureKelvin;
        return 1e-4 * k * Math.exp(x * Math.pow(r1, y));
    }

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
    public static double hagedornBrown(
            double p_mpa,
            double t_k,
            double dia_m,
            double vsg,
            double vsl,
            double vm,
            double rho_liq,
            double rho_gas,
            double vis_liq,
            double vis_gas,
            double sigma_gl,
            double roughness,
            double angle
    ) {
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
    public static double mukherjeeBrill(
            double p_mpa,
            double t_k,
            double dia_m,
            double vsg,
            double vsl,
            double vm,
            double rho_liq,
            double rho_gas,
            double vis_liq,
            double vis_gas,
            double sigma_gl,
            double roughness,
            double angle
    ) {
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
