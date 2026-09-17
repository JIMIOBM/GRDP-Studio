package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;

/** Literal teacher Standing/Dempsey formula, with explicit temperature units.
 * Coefficient and low-pressure-form differences are documented in sql/gas-properties-methods.md. */
public final class PipelineStandingViscosity {
    public static final String VERSION = "standing-teacher-v1";
    public static final String SOURCE = "Standing（资料原式，温度单位已换算）";
    private static final double[] A = {
            -2.46211820, 2.97054714, -2.86264054e-1, 8.50420522e-3,
            2.80860949, -3.49803305, 3.60373020e-1, -1.044324e-2,
            -7.93385684e-1, 1.39643306, -1.49144925e-1, 4.41015512e-3,
            8.39387178e-2, -1.86408848e-1, 2.03367881e-2, -6.09579263e-4
    };
    public record Result(double viscosityMpaS, double gasGravity, double pseudoCriticalTemperatureK,
                         double pseudoCriticalPressureMpa, double reducedTemperature, double reducedPressure,
                         double lowPressureViscosityMpaS, double nitrogenCorrectionMpaS,
                         double carbonDioxideCorrectionMpaS, double hydrogenSulfideCorrectionMpaS,
                         double correctedLowPressureViscosityMpaS, double pressurePolynomial) {}
    private PipelineStandingViscosity() {}

    public static Result calculate(double pressureMpa, double temperatureC, double gasGravity,
                                   double pseudoCriticalTemperatureK, double pseudoCriticalPressureMpa,
                                   double nitrogenFraction, double carbonDioxideFraction, double hydrogenSulfideFraction) {
        positive(pressureMpa, "绝对压力"); positive(gasGravity, "气体相对密度");
        positive(pseudoCriticalTemperatureK, "拟临界温度"); positive(pseudoCriticalPressureMpa, "拟临界压力");
        double temperatureK = temperatureC + 273.15;
        positive(temperatureK, "绝对温度");
        fraction(nitrogenFraction); fraction(carbonDioxideFraction); fraction(hydrogenSulfideFraction);
        require(nitrogenFraction + carbonDioxideFraction + hydrogenSulfideFraction <= 1 + 1e-6,
                "非烃摩尔分数合计不能大于 1");
        double tr = temperatureK / pseudoCriticalTemperatureK, pr = pressureMpa / pseudoCriticalPressureMpa;
        positive(tr, "对比温度"); positive(pr, "对比压力");
        double logGravity = Math.log10(gasGravity);
        // The supplied image has no -6.15e-3*log10(gravity) term. Do not silently substitute a different formula.
        double low = (1.709e-5 - 2.062e-6 * gasGravity) * (1.8 * temperatureC + 32) + 8.188e-3;
        double n2 = nitrogenFraction * (8.48e-3 * logGravity + 9.59e-3);
        double co2 = carbonDioxideFraction * (9.08e-3 * logGravity + 6.24e-3);
        double h2s = hydrogenSulfideFraction * (8.49e-3 * logGravity + 3.73e-3);
        double corrected = low + n2 + co2 + h2s;
        positive(corrected, "非烃修正后的低压黏度");
        double polynomial = 0;
        for (int i = 3; i >= 0; i--) {
            int j = 4 * i;
            double row = ((A[j + 3] * pr + A[j + 2]) * pr + A[j + 1]) * pr + A[j];
            polynomial = polynomial * tr + row;
        }
        require(Double.isFinite(polynomial), "压力修正超出数值计算能力，请核对组成和温压");
        // Log form avoids an overflowing exp(A) when the final value is still representable.
        double viscosity = Math.exp(Math.log(corrected) - Math.log(tr) + polynomial);
        positive(viscosity, "计算黏度（请核对组成、温压及资料经验式适用性）");
        return new Result(viscosity, gasGravity, pseudoCriticalTemperatureK, pseudoCriticalPressureMpa,
                tr, pr, low, n2, co2, h2s, corrected, polynomial);
    }
    private static void fraction(double value) {
        require(Double.isFinite(value) && value >= 0 && value <= 1, "非烃摩尔分数须在 0～1 之间");
    }
    private static void positive(double value, String label) {
        require(Double.isFinite(value) && value > 0, label + "须为有限正数");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new BusinessException(400, "Standing 黏度：" + message);
    }
}
