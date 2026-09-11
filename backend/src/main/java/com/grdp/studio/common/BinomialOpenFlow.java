package com.grdp.studio.common;

/** Positive root of the binomial equation, shared by comparison and injection curves. */
public final class BinomialOpenFlow {
    private BinomialOpenFlow() {}
    public static void validateCoefficients(Double a, Double b) {
        if (a == null || b == null || !Double.isFinite(a) || !Double.isFinite(b)
                || a < 0 || b < 0 || a == 0 && b == 0)
            throw new BusinessException(400, "A、B缺失或无效（须非负且不能同时为0）");
    }
    public static double solve(double a, double b, double difference) {
        validateCoefficients(a, b);
        if (!Double.isFinite(difference) || difference <= 0)
            throw new BusinessException(400, "压力差或拟压力差无效");
        double q = b == 0 ? difference / a
                : difference / (a / 2 + Math.hypot(a / 2, Math.sqrt(b) * Math.sqrt(difference)));
        if (!Double.isFinite(q) || q <= 0) throw new BusinessException(400, "无阻流量计算结果无效");
        return q;
    }
}
