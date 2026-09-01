package com.grdp.studio.productivity;

import com.grdp.studio.common.BusinessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.grdp.studio.productivity.ModifiedIsochronalExponentialModels.*;

@Service
public class ModifiedIsochronalExponentialCalculator {
    private static final double ATMOSPHERIC_PRESSURE_MPA = 0.101325;
    private static final Set<String> METHODS = Set.of("pseudo-pressure", "pressure-squared", "pressure");

    public CalculateResponse calculate(CalculateRequest request) {
        if (!"production".equals(request.operationType())) error("修正等时指数式当前仅支持采气");
        if (!METHODS.contains(request.pressureMethod())) error("压力处理方法不正确");
        if (!finite(request.maximumFormationPressure()) || request.maximumFormationPressure() <= 0) {
            error("计算IPR曲线的最大地层压力必须大于0");
        }
        if (request.inputItems() == null || request.inputItems().size() < 3) {
            error("修正等时指数式至少需要3个有效测试点，最后一行为稳定点");
        }
        Map<Integer, PressureFunctionDifference> suppliedDifferences = normalizeSuppliedDifferences(request);
        List<PseudoPressurePoint> pseudo = suppliedDifferences.isEmpty() ? normalizePseudoCurve(request) : List.of();
        List<AnalysisPoint> points = new ArrayList<>();
        for (int index = 0; index < request.inputItems().size(); index++) {
            InputPoint item = request.inputItems().get(index);
            if (!finite(item.testDailyGasProduction()) || item.testDailyGasProduction() <= 0) {
                error("测试气产量必须大于0");
            }
            if (!finite(item.reservoirPressure()) || !finite(item.testFlowPressure()) ||
                    item.reservoirPressure() <= item.testFlowPressure()) {
                error("地层/恢复压力必须大于测试流压");
            }
            PressureFunctionDifference supplied = suppliedDifferences.get(item.testPointNumber());
            double drawdown = supplied == null
                    ? potential(item.reservoirPressure(), request.pressureMethod(), pseudo) -
                    potential(item.testFlowPressure(), request.pressureMethod(), pseudo)
                    : supplied.pressureFunctionDifference();
            if (!finite(drawdown) || drawdown <= 0) error("压力函数差必须大于0");
            points.add(new AnalysisPoint(item.testDailyGasProduction(), drawdown,
                    index == request.inputItems().size() - 1));
        }

        Regression transientRegression = regress(points.subList(0, points.size() - 1));
        AnalysisPoint stable = points.getLast();
        double stableCoefficient = stable.flowRate() / Math.pow(stable.drawdown(), transientRegression.exponent());
        if (!finite(stableCoefficient) || stableCoefficient <= 0) error("稳定点不能得到有效的指数式产能系数");

        List<PressureFunctionCurve> suppliedCurves = normalizeSuppliedCurves(request);
        double atmospheric = Math.min(request.maximumFormationPressure(), ATMOSPHERIC_PRESSURE_MPA);
        double maximumDrawdown = suppliedCurves.isEmpty()
                ? potential(request.maximumFormationPressure(), request.pressureMethod(), pseudo) -
                potential(atmospheric, request.pressureMethod(), pseudo)
                : maximumCurveDrawdown(suppliedCurves, request.maximumFormationPressure(), atmospheric);
        double aof = stableCoefficient * Math.pow(maximumDrawdown, transientRegression.exponent());
        if (!finite(aof) || aof <= 0) error("指数式计算未得到有效的无阻流量");

        double minimumRate = points.stream().mapToDouble(AnalysisPoint::flowRate).min().orElseThrow();
        double maximumRate = Math.max(aof, points.stream().mapToDouble(AnalysisPoint::flowRate).max().orElseThrow());
        double lineStart = Math.max(minimumRate / 1.25, 1e-12);
        double lineEnd = maximumRate * 1.02;
        List<CurvePoint> transientLine = curve(transientRegression.coefficient(), transientRegression.exponent(), lineStart, lineEnd);
        List<CurvePoint> stableLine = curve(stableCoefficient, transientRegression.exponent(), lineStart, lineEnd);
        List<CurvePoint> analysis = new ArrayList<>();
        for (AnalysisPoint point : points) {
            analysis.add(new CurvePoint(point.flowRate(), point.drawdown(), point.stable() ? "stable" : "unstable"));
        }

        String reliability = transientRegression.rSquared() >= .9
                ? "分析结果可靠性较高"
                : transientRegression.rSquared() >= .7 ? "分析结果可靠性一般" : "分析结果可靠性偏低";
        if (transientRegression.exponent() < .5 || transientRegression.exponent() > 1) {
            reliability += "；产能指数n超出常用工程范围[0.5,1]";
        }
        String expression = switch (request.pressureMethod()) {
            case "pseudo-pressure" -> "m(Pr)-m(Pwf)";
            case "pressure-squared" -> "Pr²-Pwf²";
            default -> "Pr-Pwf";
        };
        String equation = String.format(Locale.ROOT, "qsc = %.6g × [%s]^%.6g",
                stableCoefficient, expression, transientRegression.exponent());

        List<IprCurve> iprCurves = suppliedCurves.isEmpty()
                ? calculateIprFromPotential(request, pseudo, stableCoefficient, transientRegression.exponent())
                : calculateIprFromSuppliedCurves(suppliedCurves, stableCoefficient, transientRegression.exponent());
        return new CalculateResponse("exponential", request.pressureMethod(), stableCoefficient,
                transientRegression.exponent(), transientRegression.coefficient(), aof,
                transientRegression.rSquared(), reliability, equation, List.copyOf(analysis),
                stableLine, transientLine, List.copyOf(iprCurves));
    }

    private Map<Integer, PressureFunctionDifference> normalizeSuppliedDifferences(CalculateRequest request) {
        if (request.pressureFunctionDifferences() == null || request.pressureFunctionDifferences().isEmpty()) {
            return Map.of();
        }
        Map<Integer, PressureFunctionDifference> values;
        try {
            values = request.pressureFunctionDifferences().stream().collect(Collectors.toMap(
                    PressureFunctionDifference::testPointNumber, Function.identity()));
        } catch (IllegalStateException duplicate) {
            error("原平台压力函数差包含重复测试点编号");
            return Map.of();
        }
        if (values.size() != request.inputItems().size() || request.inputItems().stream()
                .anyMatch(item -> !values.containsKey(item.testPointNumber()))) {
            error("原平台压力函数差与试井测试点不完整对应");
        }
        if (values.values().stream().anyMatch(value -> !finite(value.pressureFunctionDifference()) ||
                value.pressureFunctionDifference() <= 0)) {
            error("原平台压力函数差必须为有效正数");
        }
        return values;
    }

    private List<PressureFunctionCurve> normalizeSuppliedCurves(CalculateRequest request) {
        if (request.pressureFunctionCurves() == null || request.pressureFunctionCurves().isEmpty()) {
            if (!normalizeSuppliedDifferences(request).isEmpty()) error("原平台结果缺少IPR压力函数网格");
            return List.of();
        }
        List<PressureFunctionCurve> curves = request.pressureFunctionCurves().stream()
                .sorted(Comparator.comparingDouble(PressureFunctionCurve::formationPressure)).toList();
        if (curves.size() < 2 || curves.stream().anyMatch(curve -> !finite(curve.formationPressure()) ||
                curve.formationPressure() <= 0 || curve.points() == null || curve.points().size() < 2 ||
                curve.points().stream().anyMatch(point -> !finite(point.bottomHoleFlowingPressure()) ||
                        !finite(point.pressureFunctionDifference()) || point.pressureFunctionDifference() < 0))) {
            error("原平台IPR压力函数网格无效");
        }
        double largest = curves.getLast().formationPressure();
        if (Math.abs(largest - request.maximumFormationPressure()) > Math.max(1e-6, largest * 1e-6)) {
            error("原平台IPR压力函数网格未覆盖最大地层压力");
        }
        return curves;
    }

    private double maximumCurveDrawdown(List<PressureFunctionCurve> curves, double maximumPressure,
                                        double flowingPressure) {
        PressureFunctionCurve curve = curves.stream().min(Comparator.comparingDouble(value ->
                Math.abs(value.formationPressure() - maximumPressure))).orElseThrow();
        List<PressureFunctionCurvePoint> points = curve.points().stream()
                .sorted(Comparator.comparingDouble(PressureFunctionCurvePoint::bottomHoleFlowingPressure)).toList();
        if (flowingPressure <= points.getFirst().bottomHoleFlowingPressure()) {
            return points.getFirst().pressureFunctionDifference();
        }
        for (int index = 1; index < points.size(); index++) {
            PressureFunctionCurvePoint upper = points.get(index);
            if (flowingPressure <= upper.bottomHoleFlowingPressure()) {
                PressureFunctionCurvePoint lower = points.get(index - 1);
                double fraction = (flowingPressure - lower.bottomHoleFlowingPressure()) /
                        (upper.bottomHoleFlowingPressure() - lower.bottomHoleFlowingPressure());
                return lower.pressureFunctionDifference() + fraction *
                        (upper.pressureFunctionDifference() - lower.pressureFunctionDifference());
            }
        }
        error("原平台IPR压力函数网格未覆盖大气压力");
        return 0;
    }

    private List<IprCurve> calculateIprFromSuppliedCurves(List<PressureFunctionCurve> curves,
                                                           double coefficient, double exponent) {
        return curves.stream().map(curve -> new IprCurve(curve.formationPressure(), curve.points().stream()
                .map(point -> new IprPoint(point.pressureFunctionDifference() > 0
                                ? coefficient * Math.pow(point.pressureFunctionDifference(), exponent) : 0,
                        point.bottomHoleFlowingPressure(), null)).toList())).toList();
    }

    private List<IprCurve> calculateIprFromPotential(CalculateRequest request, List<PseudoPressurePoint> pseudo,
                                                      double coefficient, double exponent) {
        List<IprCurve> curves = new ArrayList<>();
        for (int curveNumber = 1; curveNumber <= 10; curveNumber++) {
            double formationPressure = request.maximumFormationPressure() * curveNumber / 10.0;
            List<IprPoint> points = new ArrayList<>();
            double minimumFlowingPressure = Math.min(formationPressure, ATMOSPHERIC_PRESSURE_MPA);
            for (int pointNumber = 0; pointNumber <= 40; pointNumber++) {
                double flowingPressure = formationPressure -
                        (formationPressure - minimumFlowingPressure) * pointNumber / 40.0;
                double drawdown = potential(formationPressure, request.pressureMethod(), pseudo) -
                        potential(flowingPressure, request.pressureMethod(), pseudo);
                double flowRate = drawdown > 0 ? coefficient * Math.pow(drawdown, exponent) : 0;
                points.add(new IprPoint(flowRate, flowingPressure, null));
            }
            curves.add(new IprCurve(formationPressure, List.copyOf(points)));
        }
        return List.copyOf(curves);
    }

    private Regression regress(List<AnalysisPoint> points) {
        double meanX = points.stream().mapToDouble(point -> Math.log(point.drawdown())).average().orElseThrow();
        double meanY = points.stream().mapToDouble(point -> Math.log(point.flowRate())).average().orElseThrow();
        double sxx = 0;
        double sxy = 0;
        for (AnalysisPoint point : points) {
            double x = Math.log(point.drawdown());
            double y = Math.log(point.flowRate());
            sxx += Math.pow(x - meanX, 2);
            sxy += (x - meanX) * (y - meanY);
        }
        if (sxx <= 1e-12) error("不稳定点的压力函数差不能全部相同");
        double exponent = sxy / sxx;
        double coefficient = Math.exp(meanY - exponent * meanX);
        if (!finite(exponent) || !finite(coefficient) || exponent <= 0 || coefficient <= 0) {
            error("不稳定点不能得到有效的指数式系数");
        }
        double total = 0;
        double residual = 0;
        for (AnalysisPoint point : points) {
            double observed = Math.log(point.flowRate());
            double predicted = Math.log(coefficient) + exponent * Math.log(point.drawdown());
            total += Math.pow(observed - meanY, 2);
            residual += Math.pow(observed - predicted, 2);
        }
        double rSquared = total <= 1e-12 ? 1 : Math.max(0, Math.min(1, 1 - residual / total));
        return new Regression(coefficient, exponent, rSquared);
    }

    private List<CurvePoint> curve(double coefficient, double exponent, double start, double end) {
        double ratio = end / start;
        List<CurvePoint> points = new ArrayList<>();
        for (int index = 0; index <= 40; index++) {
            double flowRate = start * Math.pow(ratio, index / 40.0);
            double drawdown = Math.pow(flowRate / coefficient, 1 / exponent);
            points.add(new CurvePoint(flowRate, drawdown, null));
        }
        return List.copyOf(points);
    }

    private List<PseudoPressurePoint> normalizePseudoCurve(CalculateRequest request) {
        if (!"pseudo-pressure".equals(request.pressureMethod())) return List.of();
        List<PseudoPressurePoint> points = (request.pseudoPressurePoints() == null
                ? List.<PseudoPressurePoint>of() : request.pseudoPressurePoints()).stream()
                .filter(point -> finite(point.pressure()) && finite(point.pseudoPressure()))
                .sorted(Comparator.comparingDouble(PseudoPressurePoint::pressure)).toList();
        if (points.size() < 2) error("拟压力计算需要至少两个PVT拟压力点");
        double requiredMaximum = Math.max(request.maximumFormationPressure(), request.inputItems().stream()
                .mapToDouble(InputPoint::reservoirPressure).max().orElse(0));
        if (points.getFirst().pressure() > ATMOSPHERIC_PRESSURE_MPA ||
                points.getLast().pressure() < requiredMaximum) {
            error("PVT拟压力曲线不能覆盖当前计算压力范围");
        }
        return points;
    }

    private double potential(double pressure, String method, List<PseudoPressurePoint> pseudo) {
        if ("pressure-squared".equals(method)) return pressure * pressure;
        if (!"pseudo-pressure".equals(method)) return pressure;
        if (pressure < pseudo.getFirst().pressure() || pressure > pseudo.getLast().pressure()) {
            error("PVT拟压力曲线不能覆盖当前计算压力范围");
        }
        int upperIndex = 0;
        while (upperIndex < pseudo.size() && pseudo.get(upperIndex).pressure() < pressure) upperIndex++;
        if (upperIndex == 0) return pseudo.getFirst().pseudoPressure();
        if (upperIndex == pseudo.size()) return pseudo.getLast().pseudoPressure();
        PseudoPressurePoint lower = pseudo.get(upperIndex - 1);
        PseudoPressurePoint upper = pseudo.get(upperIndex);
        if (Math.abs(upper.pressure() - pressure) < 1e-12) return upper.pseudoPressure();
        int sampleCount = Math.min(5, pseudo.size());
        int start = Math.max(0, Math.min(upperIndex - 2, pseudo.size() - sampleCount));
        double interpolated = 0;
        for (int i = start; i < start + sampleCount; i++) {
            double weight = 1;
            for (int j = start; j < start + sampleCount; j++) {
                if (i != j) weight *= (pressure - pseudo.get(j).pressure()) /
                        (pseudo.get(i).pressure() - pseudo.get(j).pressure());
            }
            interpolated += pseudo.get(i).pseudoPressure() * weight;
        }
        double min = Math.min(lower.pseudoPressure(), upper.pseudoPressure());
        double max = Math.max(lower.pseudoPressure(), upper.pseudoPressure());
        if (finite(interpolated) && interpolated >= min - 1e-6 && interpolated <= max + 1e-6) return interpolated;
        double fraction = (pressure - lower.pressure()) / (upper.pressure() - lower.pressure());
        return lower.pseudoPressure() + fraction * (upper.pseudoPressure() - lower.pseudoPressure());
    }

    private boolean finite(Double value) { return value != null && Double.isFinite(value); }
    private void error(String message) { throw new BusinessException(400, message); }
    private record AnalysisPoint(double flowRate, double drawdown, boolean stable) {}
    private record Regression(double coefficient, double exponent, double rSquared) {}
}
