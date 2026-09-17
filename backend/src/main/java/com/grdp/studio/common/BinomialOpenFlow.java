package com.grdp.studio.common;

/** 单井及库级产能对比使用的二项式正根；兼容负A、正B，不改写原始系数。 */
public final class BinomialOpenFlow {
    private BinomialOpenFlow() {}
    public static void validateCoefficients(Double a, Double b) {
        if (a == null || b == null || !Double.isFinite(a) || !Double.isFinite(b)
                || b < 0 || b == 0 && a <= 0)
            throw new BusinessException(400, "A、B缺失或无效（B须非负；B为0时A须大于0）");
    }
    public static double solve(double a, double b, double difference) {
        validateCoefficients(a, b);
        if (!Double.isFinite(difference) || difference <= 0)
            throw new BusinessException(400, "压力差或拟压力差无效");
        double q;
        if (a < 0) {
            // 此时B必为正，Bq²+Aq=差值有唯一正根。
            // 使用流量尺度的正项相加，避免原有有理化公式在负A时发生相消。
            double halfLinear = (-a / b) / 2;
            q = halfLinear + Math.hypot(halfLinear, Math.sqrt(difference) / Math.sqrt(b));
        } else {
            // 非负系数保留原有计算路径，避免影响既有结果。
            q = b == 0 ? difference / a
                    : difference / (a / 2 + Math.hypot(a / 2, Math.sqrt(b) * Math.sqrt(difference)));
        }
        if (!Double.isFinite(q) || q <= 0) throw new BusinessException(400, "无阻流量计算结果无效");
        return q;
    }
}
