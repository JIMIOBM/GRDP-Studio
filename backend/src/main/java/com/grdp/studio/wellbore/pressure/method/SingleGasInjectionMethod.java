package com.grdp.studio.wellbore.pressure.method;

/** Steady single gas, prescribed temperature, constant pipe area; acceleration neglected.
 * Positive coordinate is measured depth downwards. Darcy friction opposes downward flow.
 * Properties and velocities are evaluated at each segment's mean pressure/temperature.
 */
public final class SingleGasInjectionMethod {
    public static final String CODE = "GAS";
    public static final String VERSION = "gas-injection-linear-v1";
    public static final int ITERATION_LIMIT = 80;
    public static final double TOLERANCE_MPA = 1e-7;

    private SingleGasInjectionMethod() {}

    public static double gradient(double diameter, double roughness, double angle,
                                  double density, double viscosityMpas, double velocity) {
        double reynolds = density * velocity * diameter / (viscosityMpas * 1e-3);
        double friction = PressureCorrelations.frictionJain(diameter, roughness, reynolds);
        double gravityPaPerM = density * 9.81 * Math.cos(Math.toRadians(angle));
        double frictionPaPerM = friction * density * velocity * velocity / (2 * diameter);
        return (gravityPaPerM - frictionPaPerM) * 1e-6;
    }
}
