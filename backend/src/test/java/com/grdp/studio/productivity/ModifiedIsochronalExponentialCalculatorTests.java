package com.grdp.studio.productivity;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.grdp.studio.productivity.ModifiedIsochronalExponentialModels.*;
import static org.junit.jupiter.api.Assertions.*;

class ModifiedIsochronalExponentialCalculatorTests {
    private final ModifiedIsochronalExponentialCalculator calculator =
            new ModifiedIsochronalExponentialCalculator();

    @Test
    void calculatesPressureForm() {
        assertSyntheticResult("pressure", List.of(1d, 4d, 9d, 16d), List.of());
    }

    @Test
    void calculatesPressureSquaredForm() {
        assertSyntheticResult("pressure-squared", List.of(10d, 20d, 40d, 60d), List.of());
    }

    @Test
    void calculatesPseudoPressureForm() {
        List<PseudoPressurePoint> curve = new ArrayList<>();
        for (int pressure = 0; pressure <= 20; pressure += 5) {
            curve.add(new PseudoPressurePoint((double) pressure, (double) pressure));
        }
        assertSyntheticResult("pseudo-pressure", List.of(1d, 4d, 9d, 16d), curve);
    }

    @Test
    void calculatesPseudoPressureFromOriginalPlatformPressureFunctions() {
        List<Double> drawdowns = List.of(1d, 4d, 9d, 16d);
        CalculateRequest base = request("pressure", drawdowns, List.of());
        List<PressureFunctionDifference> differences = new ArrayList<>();
        for (int index = 0; index < drawdowns.size(); index++) {
            differences.add(new PressureFunctionDifference(index + 1, drawdowns.get(index)));
        }
        List<PressureFunctionCurve> curves = List.of(
                new PressureFunctionCurve(10d, List.of(
                        new PressureFunctionCurvePoint(10d, 0d),
                        new PressureFunctionCurvePoint(0d, 100d))),
                new PressureFunctionCurve(20d, List.of(
                        new PressureFunctionCurvePoint(20d, 0d),
                        new PressureFunctionCurvePoint(0d, 400d))));
        CalculateRequest request = new CalculateRequest(base.projectId(), base.gasReservoirId(),
                base.wellName(), base.pvtId(), base.operationType(), "pseudo-pressure",
                base.maximumFormationPressure(), base.inputItems(), List.of(), differences, curves);

        CalculateResponse result = calculator.calculate(request);

        assertEquals(3d, result.productivityCoefficient(), 1e-9);
        assertEquals(.75d, result.productivityExponent(), 1e-9);
        assertEquals(2, result.iprCurves().size());
        assertEquals(2, result.iprCurves().getFirst().points().size());
    }

    @Test
    void rejectsTooFewPoints() {
        CalculateRequest request = request("pressure", List.of(1d, 4d), List.of());
        assertBusinessError(request, "至少需要3个");
    }

    @Test
    void rejectsNonPositiveRate() {
        CalculateRequest request = request("pressure", List.of(1d, 4d, 9d, 16d), List.of());
        List<InputPoint> invalid = new ArrayList<>(request.inputItems());
        InputPoint first = invalid.getFirst();
        invalid.set(0, new InputPoint(first.testPointNumber(), 0d,
                first.reservoirPressure(), first.testFlowPressure()));
        assertBusinessError(new CalculateRequest(request.projectId(), request.gasReservoirId(),
                request.wellName(), request.pvtId(), request.operationType(), request.pressureMethod(),
                request.maximumFormationPressure(), invalid, request.pseudoPressurePoints()), "必须大于0");
    }

    @Test
    void acceptsEqualPressureOrderUntilPressureFunctionValidation() {
        CalculateRequest request = request("pressure", List.of(1d, 4d, 9d, 16d), List.of());
        List<InputPoint> invalid = new ArrayList<>(request.inputItems());
        InputPoint first = invalid.getFirst();
        invalid.set(0, new InputPoint(first.testPointNumber(), first.testDailyGasProduction(),
                first.testFlowPressure(), first.testFlowPressure()));
        assertBusinessError(new CalculateRequest(request.projectId(), request.gasReservoirId(),
                request.wellName(), request.pvtId(), request.operationType(), request.pressureMethod(),
                request.maximumFormationPressure(), invalid, request.pseudoPressurePoints()), "压力函数差必须大于0");
    }

    @Test
    void rejectsRepeatedDrawdown() {
        CalculateRequest request = request("pressure", List.of(1d, 1d, 1d, 4d), List.of());
        assertBusinessError(request, "不能全部相同");
    }

    @Test
    void rejectsPseudoPressureCurveWithoutCoverage() {
        CalculateRequest request = request("pseudo-pressure", List.of(1d, 4d, 9d, 16d),
                List.of(new PseudoPressurePoint(5d, 5d), new PseudoPressurePoint(10d, 10d)));
        assertBusinessError(request, "不能覆盖");
    }

    @Test
    void calculatesInjectionWithFlowingPressureAboveReservoirPressure() {
        CalculateRequest production = request("pressure", List.of(1d, 4d, 9d, 16d), List.of());
        List<InputPoint> injectionPoints = production.inputItems().stream()
                .map(point -> new InputPoint(point.testPointNumber(), point.testDailyGasProduction(),
                        point.testFlowPressure(), point.reservoirPressure()))
                .toList();
        CalculateResponse result = calculator.calculate(new CalculateRequest(production.projectId(),
                production.gasReservoirId(), production.wellName(), production.pvtId(), "injection",
                production.pressureMethod(), production.maximumFormationPressure(), injectionPoints,
                production.pseudoPressurePoints()));
        assertEquals("exponential", result.calculationResultType());
        assertTrue(result.equation().contains("Pwf-Pr"));
    }

    private void assertSyntheticResult(String method, List<Double> drawdowns,
                                       List<PseudoPressurePoint> pseudoCurve) {
        CalculateResponse result = calculator.calculate(request(method, drawdowns, pseudoCurve));
        assertEquals("exponential", result.calculationResultType());
        assertEquals(3d, result.productivityCoefficient(), 1e-9);
        assertEquals(.75d, result.productivityExponent(), 1e-9);
        assertEquals(2d, result.transientProductivityCoefficient(), 1e-9);
        assertEquals(1d, result.rSquared(), 1e-10);
        assertTrue(result.openFlowCapacity() > 0);
        assertEquals(4, result.analysisPoints().size());
        assertEquals("stable", result.analysisPoints().getLast().label());
        assertEquals(41, result.regressionLine().size());
        assertEquals(41, result.transientLine().size());
        assertEquals(10, result.iprCurves().size());
        assertTrue(result.iprCurves().stream().allMatch(curve -> curve.points().size() == 41));
        assertEquals(20d, result.iprCurves().getLast().formationPressure(), 1e-10);
    }

    private CalculateRequest request(String method, List<Double> drawdowns,
                                     List<PseudoPressurePoint> pseudoCurve) {
        double exponent = .75;
        double transientCoefficient = 2;
        double stableCoefficient = 3;
        List<InputPoint> points = new ArrayList<>();
        for (int index = 0; index < drawdowns.size(); index++) {
            double drawdown = drawdowns.get(index);
            double rate = (index == drawdowns.size() - 1 ? stableCoefficient : transientCoefficient) *
                    Math.pow(drawdown, exponent);
            double reservoirPressure = "pressure-squared".equals(method) ? 10 : 20;
            double flowingPressure = "pressure-squared".equals(method)
                    ? Math.sqrt(reservoirPressure * reservoirPressure - drawdown)
                    : reservoirPressure - drawdown;
            points.add(new InputPoint(index + 1, rate, reservoirPressure, flowingPressure));
        }
        return new CalculateRequest(6, 4, "A1-3", 4, "production", method, 20d, points, pseudoCurve);
    }

    private void assertBusinessError(CalculateRequest request, String text) {
        BusinessException error = assertThrows(BusinessException.class, () -> calculator.calculate(request));
        assertTrue(error.getMessage().contains(text), error.getMessage());
    }
}
