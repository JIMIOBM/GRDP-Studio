package com.grdp.studio.nodal;

import com.grdp.studio.common.BusinessException;
import java.util.*;

/** Same potential and stable quadratic root as the coefficient workspace; no fitting here. */
public final class BinomialCurve {
    private final double a, b;
    private final String method;
    private final NavigableMap<Double, Double> pseudo = new TreeMap<>();
    private final Map<Double, Double> slopes = new HashMap<>();
    public BinomialCurve(double a, double b, String method, Map<?, ?> snapshot) {
        if (!Double.isFinite(a + b) || a < 0 || b < 0 || a + b <= 0) fail("二项式系数必须非负且不能同时为零");
        this.a = a; this.b = b; this.method = method;
        if (!Set.of("压力法", "压力平方法", "拟压力").contains(method)) fail("不支持的系数压力口径");
        if (method.equals("拟压力")) {
            Object rows = snapshot == null ? null : snapshot.get("gasResultRows");
            if (!(rows instanceof List<?>)) fail("系数方案缺少拟压力快照");
            for (Object row : (List<?>) rows) {
                Object x = null, y = null;
                if (row instanceof List<?> v && v.size() >= 4) { x = v.get(0); y = v.get(3); }
                if (row instanceof Map<?, ?> v) {
                    x = field(v, "pressure", "formationPressure", "reservoirPressure", "压力", "压力(MPa)");
                    y = field(v, "pseudoPressure", "pseudo_pressure", "gasPseudoPressure", "mP", "mp", "气体拟压力", "气体拟压力(MPa²/(mPa·s))");
                }
                if (x != null && y != null) {
                    double p = number(x), m = number(y);
                    if (p >= 0) pseudo.put(p, m);
                }
            }
            pseudo.putIfAbsent(0d, 0d);
            if (pseudo.size() < 2) fail("拟压力快照数据不足");
            double previous = -1;
            for (double m : pseudo.values()) { if (m < 0 || m <= previous) fail("拟压力必须非负且严格递增"); previous = m; }
            prepareSlopes();
        }
    }
    private static Object field(Map<?, ?> map, String... keys) {
        for (String key : keys) if (map.get(key) != null) return map.get(key);
        return null;
    }
    private static double number(Object value) {
        try { double n = Double.parseDouble(value.toString()); if (Double.isFinite(n)) return n; }
        catch (NumberFormatException ignored) {}
        throw new BusinessException(400, "拟压力快照存在无效数值");
    }
    public double potential(double p) {
        if (!Double.isFinite(p) || p < 0) fail("压力必须为非负有效数值");
        if (method.equals("压力法")) return p;
        if (method.equals("压力平方法")) return p * p;
        var lo = pseudo.floorEntry(p); var hi = pseudo.ceilingEntry(p);
        if (lo == null || hi == null) fail("井底压力范围超出系数方案PVT拟压力快照");
        if (lo.getKey().equals(hi.getKey())) return lo.getValue();
        // Interpolate the physical potential, not the plotted q-p curve. In p² coordinates,
        // constant gas viscosity/Z gives an exact straight line, including the low-pressure limit.
        // Monotone Hermite slopes preserve every saved knot without overshoot or slope jumps.
        double x0 = lo.getKey()*lo.getKey(), h = hi.getKey()*hi.getKey()-x0;
        double t = (p*p-x0)/h, t2=t*t, t3=t2*t;
        return (2*t3-3*t2+1)*lo.getValue() + (t3-2*t2+t)*h*slopes.get(lo.getKey())
                + (-2*t3+3*t2)*hi.getValue() + (t3-t2)*h*slopes.get(hi.getKey());
    }
    private void prepareSlopes() {
        var keys = new ArrayList<>(pseudo.keySet());
        int n = keys.size();
        double[] h = new double[n-1], d = new double[n-1];
        for (int i=0;i<n-1;i++) {
            h[i]=keys.get(i+1)*keys.get(i+1)-keys.get(i)*keys.get(i);
            d[i]=(pseudo.get(keys.get(i+1))-pseudo.get(keys.get(i)))/h[i];
        }
        if (n==2) { slopes.put(keys.get(0),d[0]); slopes.put(keys.get(1),d[0]); return; }
        slopes.put(keys.get(0),endSlope(h[0],h[1],d[0],d[1]));
        slopes.put(keys.get(n-1),endSlope(h[n-2],h[n-3],d[n-2],d[n-3]));
        for (int i=1;i<n-1;i++) {
            double w1=2*h[i]+h[i-1], w2=h[i]+2*h[i-1];
            slopes.put(keys.get(i),(w1+w2)/(w1/d[i-1]+w2/d[i]));
        }
    }
    private static double endSlope(double h0,double h1,double d0,double d1) {
        return Math.max(0,Math.min(3*d0,((2*h0+h1)*d0-h0*d1)/(h0+h1)));
    }
    public double rate(double pr, double pwf, boolean injection) {
        double d = injection ? potential(pwf) - potential(pr) : potential(pr) - potential(pwf);
        if (d < -1e-9) fail("井底压力与注采方向不一致");
        if (d <= 0) return 0;
        double q = b == 0 ? d / a : 2 * d / (a + Math.sqrt(a * a + 4 * b * d));
        if (!Double.isFinite(q)) fail("二项式气量超出数值范围");
        return q;
    }
    private static void fail(String message) { throw new BusinessException(400, message); }
}
