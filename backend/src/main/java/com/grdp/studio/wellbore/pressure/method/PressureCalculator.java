package com.grdp.studio.wellbore.pressure.method;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.wellbore.pressure.dto.PressureCalculateRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Pressure integration ported from the supplied JavaScript algorithm. */
public final class PressureCalculator {
    private static final int MAX_SEGMENT_COUNT = 10_000;
    private static final int MAX_ITERATION_COUNT = 10;
    private static final double PRESSURE_TOLERANCE_MPA = 0.0001;
    private static final double GAS_LIQUID_SURFACE_TENSION = 0.03;

    private PressureCalculator() {}

    public record Properties(
            double gasVolumeFactor,
            double gasDensity,
            double gasViscosity,
            double liquidDensity,
            double liquidViscosity
    ) {}

    public record Point(
            double depth,
            double temperature,
            double pressure,
            Double segmentAveragePressure,
            Double segmentAverageTemperature,
            Double gasVolumeFactor,
            Double gasDensity,
            Double gasViscosity,
            Double liquidDensity,
            Double liquidViscosity,
            Double gradient,
            Integer iterationCount,
            Boolean segmentConverged
    ) {}

    public record MethodResult(
            String methodCode,
            List<Point> profile,
            boolean allSegmentsConverged,
            int maxIterationCount,
            int nonconvergedSegmentCount
    ) {}

    public record Result(
            List<Double> depth,
            Map<String, MethodResult> methods,
            String boundaryPosition
    ) {}

    /** Uses the supplied JS depth grid, linear temperature, DAK, LGE, HB and MB flow. */
    public static Result calculate(PressureCalculateRequest request) {
        validateDirectInput(request);

        List<Double> depths = buildDepths(request.depth, request.step);
        List<Double> temperatures = depths.stream()
                .map(depth -> request.tWh + request.tGrad / 100 * depth)
                .toList();

        return calculate(
                request,
                depths,
                temperatures,
                (pressure, temperature) -> PressureCorrelations.originalProperties(
                        pressure,
                        temperature + 273.15,
                        request.gammaG,
                        request.rhoL,
                        request.muL
                )
        );
    }

    public static void validate(
            PressureCalculateRequest request,
            List<Double> profileDepth,
            List<Double> profileTemperature
    ) {
        if (request == null
                || profileDepth == null
                || profileTemperature == null
                || profileDepth.size() < 2
                || profileDepth.size() > MAX_SEGMENT_COUNT + 1
                || profileDepth.size() != profileTemperature.size()) {
            fail("温度剖面需要2至10001个点");
        }
        if (request.models == null
                || request.models.isEmpty()
                || request.models.stream().anyMatch(model -> !List.of("HB", "MB").contains(model))) {
            fail("折算方法仅支持HB、MB");
        }
        if (!"production".equals(request.operationMode)) {
            fail("注入工况算法暂未开放");
        }
        if (!"wellhead".equals(request.boundaryPosition)
                && !"bottomhole".equals(request.boundaryPosition)) {
            fail("压力位置仅支持井口或井底");
        }
        if (!Double.isFinite(request.boundaryPressure)
                || request.boundaryPressure <= 0
                || !Double.isFinite(request.idTubing)
                || request.idTubing <= 0
                || !Double.isFinite(request.roughness)
                || request.roughness < 0
                || !Double.isFinite(request.angle)
                || request.angle < 0
                || request.angle > 90
                || !Double.isFinite(request.qGas)
                || request.qGas < 0
                || !Double.isFinite(request.qLiq)
                || request.qLiq < 0
                || request.qGas + request.qLiq <= 0) {
            fail("压力折算输入参数无效");
        }
        for (int index = 0; index < profileDepth.size(); index++) {
            Double depth = profileDepth.get(index);
            Double temperature = profileTemperature.get(index);
            if (depth == null
                    || !Double.isFinite(depth)
                    || temperature == null
                    || !Double.isFinite(temperature)
                    || temperature <= -273.15
                    || (index == 0 ? depth != 0 : depth <= profileDepth.get(index - 1))) {
                fail("温度剖面的深度必须从0严格递增且温度有效");
            }
        }
    }

    public static Result calculate(
            PressureCalculateRequest request,
            List<Double> depths,
            List<Double> temperatures,
            BiFunction<Double, Double, Properties> properties
    ) {
        validate(request, depths, temperatures);
        if (properties == null) {
            fail("缺少井筒流体物性计算方法");
        }

        Map<String, MethodResult> results = new LinkedHashMap<>();
        double diameter = request.idTubing / 1000;
        double area = Math.PI * diameter * diameter / 4;

        for (String model : new LinkedHashSet<>(request.models)) {
            List<Point> points = new ArrayList<>();
            points.add(new Point(
                    round(depths.getFirst(), 1),
                    round(temperatures.getFirst(), 2),
                    request.boundaryPressure,
                    null, null, null, null, null, null, null, null,
                    0,
                    true
            ));

            double pressureStart = request.boundaryPressure;
            int maxIterations = 0;
            int nonconverged = 0;

            for (int index = 1; index < depths.size(); index++) {
                double segmentLength = depths.get(index) - depths.get(index - 1);
                double averageTemperature = (temperatures.get(index) + temperatures.get(index - 1)) / 2;
                double pressureGuess = pressureStart;
                boolean converged = false;
                Properties lastProperties = null;
                double lastAveragePressure = pressureStart;
                double gradient = 0;
                int iteration = 0;

                for (int currentIteration = 0; currentIteration < MAX_ITERATION_COUNT; currentIteration++) {
                    iteration = currentIteration + 1;
                    double averagePressure = (pressureStart + pressureGuess) / 2;
                    if (averagePressure <= 0) {
                        averagePressure = 0.1;
                    }

                    Properties currentProperties = properties.apply(averagePressure, averageTemperature);
                    validateProperties(currentProperties);
                    lastProperties = currentProperties;
                    lastAveragePressure = averagePressure;

                    double superficialGasVelocity = (request.qGas * 10_000 / 86_400)
                            * currentProperties.gasVolumeFactor() / area;
                    double superficialLiquidVelocity = request.qLiq / (86_400 * area);
                    double mixtureVelocity = superficialGasVelocity + superficialLiquidVelocity;

                    gradient = gradient(
                            model,
                            averagePressure,
                            averageTemperature + 273.15,
                            diameter,
                            superficialGasVelocity,
                            superficialLiquidVelocity,
                            mixtureVelocity,
                            currentProperties,
                            request
                    );

                    double candidate = pressureStart + gradient * segmentLength;
                    if (!Double.isFinite(candidate) || candidate <= 0) {
                        fail("压力折算超出有效范围");
                    }
                    if (Math.abs(candidate - pressureGuess) < PRESSURE_TOLERANCE_MPA) {
                        pressureGuess = candidate;
                        converged = true;
                        break;
                    }
                    pressureGuess = (pressureGuess + candidate) / 2;
                }

                maxIterations = Math.max(maxIterations, iteration);
                if (!converged) {
                    nonconverged++;
                }
                points.add(new Point(
                        round(depths.get(index), 1),
                        round(temperatures.get(index), 2),
                        round(pressureGuess, 4),
                        lastAveragePressure,
                        averageTemperature,
                        lastProperties.gasVolumeFactor(),
                        lastProperties.gasDensity(),
                        lastProperties.gasViscosity(),
                        lastProperties.liquidDensity(),
                        lastProperties.liquidViscosity(),
                        gradient,
                        iteration,
                        converged
                ));
                pressureStart = pressureGuess;
            }

            results.put(model, new MethodResult(
                    model,
                    List.copyOf(points),
                    nonconverged == 0,
                    maxIterations,
                    nonconverged
            ));
        }

        return new Result(
                depths.stream().map(depth -> round(depth, 1)).toList(),
                results,
                request.boundaryPosition
        );
    }

    private static List<Double> buildDepths(double totalDepth, double step) {
        int fullSteps = (int) Math.floor(totalDepth / step);
        double remainder = totalDepth - fullSteps * step;
        boolean hasTail = remainder > 0.001;
        int segmentCount = fullSteps + (hasTail ? 1 : 0);
        if (segmentCount > MAX_SEGMENT_COUNT) {
            fail("计算段数不能超过10000，请增大计算步长");
        }

        List<Double> depths = new ArrayList<>(segmentCount + 1);
        depths.add(0.0);
        for (int index = 1; index <= fullSteps; index++) {
            depths.add(index * step);
        }
        if (hasTail) {
            depths.add(totalDepth);
        }
        return List.copyOf(depths);
    }

    private static double gradient(
            String model,
            double pressure,
            double temperatureKelvin,
            double diameter,
            double superficialGasVelocity,
            double superficialLiquidVelocity,
            double mixtureVelocity,
            Properties properties,
            PressureCalculateRequest request
    ) {
        if ("HB".equals(model)) {
            return HagedornBrownMethod.gradient(
                    pressure,
                    temperatureKelvin,
                    diameter,
                    superficialGasVelocity,
                    superficialLiquidVelocity,
                    mixtureVelocity,
                    properties.liquidDensity(),
                    properties.gasDensity(),
                    properties.liquidViscosity(),
                    properties.gasViscosity(),
                    GAS_LIQUID_SURFACE_TENSION,
                    request.roughness / 1000,
                    request.angle
            );
        }
        return MukherjeeBrillMethod.gradient(
                pressure,
                temperatureKelvin,
                diameter,
                superficialGasVelocity,
                superficialLiquidVelocity,
                mixtureVelocity,
                properties.liquidDensity(),
                properties.gasDensity(),
                properties.liquidViscosity(),
                properties.gasViscosity(),
                GAS_LIQUID_SURFACE_TENSION,
                request.roughness / 1000,
                request.angle
        );
    }

    private static void validateDirectInput(PressureCalculateRequest request) {
        if (request == null) {
            fail("缺少压力折算参数");
        }
        requireRange(request.depth, 0.001, 100_000, "测井深度");
        requireRange(request.step, 0.001, 100_000, "计算步长");
        requireRange(request.tWh, -273.14, 1000, "井口温度");
        requireRange(request.tGrad, 0, 100, "地温梯度");
        requireRange(request.gammaG, 0.001, 10, "气体相对密度");
        requireRange(request.rhoL, 0.001, 10_000, "液体密度");
        requireRange(request.muL, 0.000001, 100_000, "液体黏度");
    }

    private static void validateProperties(Properties properties) {
        if (properties == null
                || !Double.isFinite(properties.gasVolumeFactor())
                || properties.gasVolumeFactor() <= 0
                || !Double.isFinite(properties.gasDensity())
                || properties.gasDensity() <= 0
                || !Double.isFinite(properties.gasViscosity())
                || properties.gasViscosity() <= 0
                || !Double.isFinite(properties.liquidDensity())
                || properties.liquidDensity() <= 0
                || !Double.isFinite(properties.liquidViscosity())
                || properties.liquidViscosity() <= 0) {
            fail("井筒流体物性计算结果无效");
        }
    }

    private static void requireRange(double value, double min, double max, String name) {
        if (!Double.isFinite(value) || value < min || value > max) {
            fail(name + "必须在 " + min + " 至 " + max + " 之间");
        }
    }

    private static double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    private static void fail(String message) {
        throw new BusinessException(400, message);
    }
}
