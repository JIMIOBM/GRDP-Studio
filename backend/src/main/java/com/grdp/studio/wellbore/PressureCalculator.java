package com.grdp.studio.wellbore;

import com.grdp.studio.common.BusinessException;
import java.util.*;
import java.util.function.BiFunction;

public final class PressureCalculator {
    public record Properties(double gasDensity, double gasViscosity, double waterDensity, double waterViscosity) {}
    public record Result(List<Double> depth, Map<String, List<Double>> pressures, String calculationPosition) {}
    public static void validate(PressureRequest p) {
        if (p == null || p.profileDepth == null || p.profileTemperature == null || p.profileDepth.size() < 2
                || p.profileDepth.size() > 201 || p.profileDepth.size() != p.profileTemperature.size()) fail("压力初版需要2至201个温度剖面点");
        if (p.models == null || p.models.isEmpty() || p.models.stream().anyMatch(m -> !List.of("HB", "MB").contains(m))) fail("折算方法仅支持HB、MB");
        if (!List.of("wellhead", "bottomhole").contains(p.calculationPosition)) fail("无效边界位置");
        if (!Double.isFinite(p.fWh) || p.fWh <= 0 || !Double.isFinite(p.idTubing) || p.idTubing <= 0
                || !Double.isFinite(p.roughness) || p.roughness < 0 || !Double.isFinite(p.gammaG) || p.gammaG <= 0
                || !Double.isFinite(p.angle) || p.angle < 0 || p.angle > 90
                || !Double.isFinite(p.qGas) || p.qGas < 0 || !Double.isFinite(p.qLiq) || p.qLiq < 0 || p.qGas + p.qLiq <= 0) fail("压力折算输入参数无效");
        for (int i = 0; i < p.profileDepth.size(); i++) {
            Double d = p.profileDepth.get(i), t = p.profileTemperature.get(i);
            if (d == null || !Double.isFinite(d) || t == null || !Double.isFinite(t) || t <= -273.15
                    || (i == 0 ? d != 0 : d <= p.profileDepth.get(i - 1))) fail("温度剖面的深度必须从0严格递增且温度有效");
        }
    }
    public static Result calculate(PressureRequest p, BiFunction<Double, Double, Properties> properties) {
        validate(p);
        int n = p.profileDepth.size();
        var results = new LinkedHashMap<String, List<Double>>();
        int direction = "bottomhole".equals(p.calculationPosition) ? -1 : 1;
        int start = direction > 0 ? 0 : n - 1;
        double diameter = p.idTubing / 1000, area = Math.PI * diameter * diameter / 4;
        for (String model : new LinkedHashSet<>(p.models)) {
            var pressures = new ArrayList<>(Collections.nCopies(n, 0d));
            pressures.set(start, p.fWh);
            for (int i = start; i + direction >= 0 && i + direction < n; i += direction) {
                int next = i + direction;
                double length = p.profileDepth.get(next) - p.profileDepth.get(i);
                double temp = (p.profileTemperature.get(i) + p.profileTemperature.get(next)) / 2;
                double initial = pressures.get(i), guess = initial;
                boolean converged = false;
                for (int k = 0; k < 20; k++) {
                    double average = (initial + guess) / 2;
                    if (average <= 0) fail("折算压力非正，请检查边界压力和井深");
                    var v = properties.apply(average, temp);
                    double vsg = p.qGas * 10000 * p.gammaG * 1.205 / (86400 * v.gasDensity() * area);
                    double vsl = p.qLiq / (86400 * area), vm = vsg + vsl;
                    double gradient = model.equals("HB")
                            ? PressureCorrelations.hagedornBrown(average, temp + 273.15, diameter, vsg, vsl, vm, v.waterDensity(), v.gasDensity(), v.waterViscosity(), v.gasViscosity(), .03, p.roughness / 1000, p.angle)
                            : PressureCorrelations.mukherjeeBrill(average, temp + 273.15, diameter, vsg, vsl, vm, v.waterDensity(), v.gasDensity(), v.waterViscosity(), v.gasViscosity(), .03, p.roughness / 1000, p.angle);
                    double candidate = initial + gradient * length;
                    if (!Double.isFinite(candidate) || candidate <= 0) fail("压力折算超出有效范围");
                    if (Math.abs(candidate - guess) < .0001) { guess = candidate; converged = true; break; }
                    guess = candidate;
                }
                if (!converged) fail(model + "分段压力迭代未收敛，请减小温度剖面步长");
                pressures.set(next, guess);
            }
            results.put(model, pressures);
        }
        return new Result(p.profileDepth, results, p.calculationPosition);
    }
    private static void fail(String message) { throw new BusinessException(400, message); }
}
