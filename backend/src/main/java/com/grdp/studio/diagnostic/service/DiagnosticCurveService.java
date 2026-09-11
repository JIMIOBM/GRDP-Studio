package com.grdp.studio.diagnostic.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.diagnostic.dto.DiagnosticCurveModels;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 储气库诊断曲线计算。
 *     <li>横坐标：库存量 G；</li>
 *     <li>纵坐标：P/Z；</li>
 *     <li>理论稳定线：P/Z = kG，严格经过原点；</li>
 *     <li>库存量严格按图三公式：
 *         Gi = G0 + ΣQin - ΣQp，跨周期连续累计；</li>
 *     <li>cycle 只负责分组绘图，不负责重置库存；</li>
 *     <li>压力上下限 Pmin/Pmax 用于锚定运行区间；</li>
 *     <li>Excel 不包含实测压力，因此注气上偏、采气下偏形成的梭形
 *         属于基于理论稳定线的重建曲线。</li>
 */
@Service
public class DiagnosticCurveService {
    private static final double EPSILON = 1e-12;
    private static final double HYSTERESIS_RATIO = 0.12;
    private static final int PRESSURE_SOLVE_ITERATIONS = 100;
    private static final double STANDARD_LINE_EXTENSION_RATIO = 1.08;
    private static final double PRESSURE_TOLERANCE = 1e-9;
    public DiagnosticCurveModels.CalculateResponse calculate(
            DiagnosticCurveModels.CalculateRequest request,
            String token,
            String cookie,
            String processEnv
    ) {
        validateRequest(request);
        final List<DiagnosticCurveModels.ProductionDataItem> data =
                request.productionData()
                        .stream()
                        .sorted(
                                Comparator.comparingInt(
                                        DiagnosticCurveModels.ProductionDataItem::sequence
                                )
                        )
                        .toList();

        /*
         * 2. 读取所选 PVT。
         */
        final PvtLookup pvt = preparePvt(request.pvt());
        final double lowerPressure = request.lowerLimit();
        final double upperPressure = request.upperLimit();
        validatePvtPressureCoverage(
                pvt,
                lowerPressure,
                upperPressure
        );

        /*
         * 3. 压力上下限换算成 P/Z 上下限。
         */
        final double lowerZ = resolveZ(pvt, lowerPressure);
        final double upperZ = resolveZ(pvt, upperPressure);
        final double lowerPz = lowerPressure / lowerZ;
        final double upperPz = upperPressure / upperZ;
        if (
                !Double.isFinite(lowerPz)
                        || !Double.isFinite(upperPz)
                        || lowerPz <= 0
                        || upperPz <= lowerPz
        ) {
            throw new BusinessException(
                    400,
                    "压力上下限与所选PVT不能形成有效的P/Z区间"
            );
        }

        validatePressureOverZMonotonic(
                pvt,
                lowerPressure,
                upperPressure
        );

        /*
         * 4. 严格按图三公式先算相对累计库存：
         *
         * Ci = ΣQin - ΣQp
         *
         * 注气：+|Q|
         * 采气：-|Q|
         *
         * 周期切换时绝不清零。
         */
        final List<RelativeState> states = buildRelativeStates(data);

        if (states.isEmpty()) {
            throw new BusinessException(
                    400,
                    "没有可用于计算诊断曲线的注采数据"
            );
        }

        double minRelativeInventory = 0.0;
        double maxRelativeInventory = 0.0;

        for (RelativeState state : states) {
            minRelativeInventory =
                    Math.min(
                            minRelativeInventory,
                            Math.min(
                                    state.relativeBefore(),
                                    state.relativeAfter()
                            )
                    );
            maxRelativeInventory =
                    Math.max(
                            maxRelativeInventory,
                            Math.max(
                                    state.relativeBefore(),
                                    state.relativeAfter()
                            )
                    );
        }

        final double relativeInventorySpan = maxRelativeInventory - minRelativeInventory;
        if (
                !Double.isFinite(relativeInventorySpan)
                        || relativeInventorySpan <= EPSILON
        ) {
            throw new BusinessException(
                    400,
                    "累计注采气量没有形成有效库存变化范围"
            );
        }

        /*
         * 5. 用上下限锚定理论稳定线的位置。
         */
        final double standardLineSlope = (upperPz - lowerPz) / relativeInventorySpan;

        if (
                !Double.isFinite(standardLineSlope)
                        || standardLineSlope <= EPSILON
        ) {
            throw new BusinessException(
                    400,
                    "理论基准线斜率计算异常"
            );
        }

        final double minInventory = lowerPz / standardLineSlope;
        /*
         * baseInventory 的物理含义：图三中的 G0。
         */
        final double baseInventory = minInventory - minRelativeInventory;
        final double maxInventory = baseInventory + maxRelativeInventory;

        if (
                !Double.isFinite(baseInventory)
                        || !Double.isFinite(minInventory)
                        || !Double.isFinite(maxInventory)
                        || baseInventory <= 0
                        || minInventory <= 0
                        || maxInventory <= minInventory
        ) {
            throw new BusinessException(
                    400,
                    "G0或库存范围计算异常"
            );
        }

        /*
         * 6. cycle 只负责分组绘图。
         */
        final Map<String, List<RelativeState>> statesByCycle = groupStatesByCycle(states);
        final List<DiagnosticCurveModels.CycleCurve> cycleCurves = new ArrayList<>();
        final List<DiagnosticCurveModels.RunningPoint> flattened = new ArrayList<>();
        double minRunningPz = Double.POSITIVE_INFINITY;
        double maxRunningPz = Double.NEGATIVE_INFINITY;
        for (
                Map.Entry<String, List<RelativeState>> entry
                : statesByCycle.entrySet()
        ) {
            final String cycleName = entry.getKey();
            final List<RelativeState> cycleStates = entry.getValue();
            validateCompleteCycle(
                    cycleName,
                    cycleStates
            );
            double cycleMinInventory = Double.POSITIVE_INFINITY;
            double cycleMaxInventory = Double.NEGATIVE_INFINITY;
            for (RelativeState state : cycleStates) {
                final double beforeInventory = baseInventory + state.relativeBefore();
                final double afterInventory = baseInventory + state.relativeAfter();
                cycleMinInventory =
                        Math.min(
                                cycleMinInventory,
                                Math.min(
                                        beforeInventory,
                                        afterInventory
                                )
                        );

                cycleMaxInventory =
                        Math.max(
                                cycleMaxInventory,
                                Math.max(
                                        beforeInventory,
                                        afterInventory
                                )
                        );
            }

            final double cycleInventorySpan = cycleMaxInventory - cycleMinInventory;
            if (
                    !Double.isFinite(cycleInventorySpan)
                            || cycleInventorySpan <= EPSILON
            ) {
                throw new BusinessException(
                        400,
                        "周期【"
                                + cycleName
                                + "】库存变化范围无效"
                );
            }
            final double amplitude = HYSTERESIS_RATIO * standardLineSlope * cycleInventorySpan;
            final List<DiagnosticCurveModels.RunningPoint> points = new ArrayList<>();
            final RelativeState first = cycleStates.get(0);
            points.add(
                    buildPoint(
                            0,
                            "",
                            cycleName,
                            first.direction(),
                            0.0,
                            first.relativeBefore(),
                            baseInventory,
                            cycleMinInventory,
                            cycleMaxInventory,
                            pvt,
                            standardLineSlope,
                            amplitude,
                            lowerPz,
                            upperPz,
                            lowerPressure,
                            upperPressure,
                            true
                    )
            );
            for (RelativeState state : cycleStates) {
                final DiagnosticCurveModels.ProductionDataItem item =
                        state.item();

                points.add(
                        buildPoint(
                                item.sequence(),
                                item.time(),
                                cycleName,
                                state.direction(),
                                item.gas(),
                                state.relativeAfter(),
                                baseInventory,
                                cycleMinInventory,
                                cycleMaxInventory,
                                pvt,
                                standardLineSlope,
                                amplitude,
                                lowerPz,
                                upperPz,
                                lowerPressure,
                                upperPressure,
                                false
                        )
                );
            }

            for (
                    DiagnosticCurveModels.RunningPoint point
                    : points
            ) {
                minRunningPz =
                        Math.min(
                                minRunningPz,
                                point.pressureOverZ()
                        );

                maxRunningPz =
                        Math.max(
                                maxRunningPz,
                                point.pressureOverZ()
                        );
            }

            cycleCurves.add(
                    new DiagnosticCurveModels.CycleCurve(
                            cycleName,
                            List.copyOf(
                                    points
                            )
                    )
            );

            flattened.addAll(
                    points
            );
        }
        /*
         * 7. 理论稳定线严格经过原点。
         */
        final double standardLineEndInventory = maxInventory * STANDARD_LINE_EXTENSION_RATIO;
        final double standardLineEndPz = standardLineSlope * standardLineEndInventory;
        final List<DiagnosticCurveModels.ChartPoint> standardLine =
                List.of(
                        new DiagnosticCurveModels.ChartPoint(
                                0.0,
                                0.0
                        ),
                        new DiagnosticCurveModels.ChartPoint(
                                standardLineEndInventory,
                                standardLineEndPz
                        )
                );

        if (
                !Double.isFinite(minRunningPz)
        ) {
            minRunningPz = lowerPz;
        }

        if (
                !Double.isFinite(maxRunningPz)
        ) {
            maxRunningPz = upperPz;
        }

        return new DiagnosticCurveModels.CalculateResponse(
                List.copyOf(cycleCurves),
                List.copyOf(flattened),
                standardLine,
                baseInventory,
                minInventory,
                maxInventory,
                minRunningPz,
                maxRunningPz,
                standardLineSlope,
                lowerPressure,
                upperPressure,
                lowerZ,
                upperZ,
                lowerPz,
                upperPz,
                pvt.fixedZ() != null
                        ? "FIXED_Z"
                        : "Z_CURVE"
        );
    }

    private List<RelativeState> buildRelativeStates(
            List<DiagnosticCurveModels.ProductionDataItem> data
    ) {
        final List<RelativeState> states = new ArrayList<>(data.size());
        double cumulative = 0.0;
        for (DiagnosticCurveModels.ProductionDataItem item : data
        ) {
            if (
                    item.gas() == null
                            || !Double.isFinite(
                            item.gas()
                    )
                            || Math.abs(
                            item.gas()
                    ) <= EPSILON
            ) {
                throw new BusinessException(
                        400,
                        "第 "
                                + item.sequence()
                                + " 行注/采气量无效"
                );
            }

            final String cycleName = normalizeCycleKey(item.cycle());
            if (
                    cycleName.isBlank()
            ) {
                throw new BusinessException(
                        400,
                        "第 "
                                + item.sequence()
                                + " 行周期字段为空或无法识别。"
                                + "建议填写“第1周期注气 / 第1周期采气”这类明确周期。"
                );
            }

            final OperationDirection direction = resolveDirection(item);
            final double relativeBefore = cumulative;
            final double amount = Math.abs(item.gas());
            final double deltaInventory =
                    direction
                            == OperationDirection.INJECTION
                            ? amount
                            : -amount;
            cumulative += deltaInventory;
            if (
                    !Double.isFinite(
                            cumulative
                    )
            ) {
                throw new BusinessException(
                        400,
                        "第 "
                                + item.sequence()
                                + " 行累计库存变化计算异常"
                );
            }

            states.add(
                    new RelativeState(
                            item,
                            cycleName,
                            direction,
                            relativeBefore,
                            cumulative
                    )
            );
        }
        return states;
    }

    private Map<String, List<RelativeState>> groupStatesByCycle(
            List<RelativeState> states
    ) {
        final Map<String, List<RelativeState>> grouped = new LinkedHashMap<>();
        for (RelativeState state : states
        ) {
            grouped.computeIfAbsent(
                    state.cycleName(),
                    ignored ->
                            new ArrayList<>()
            ).add(
                    state
            );
        }
        return grouped;
    }

    private void validateCompleteCycle(
            String cycleName,
            List<RelativeState> states
    ) {
        boolean hasInjection = false;
        boolean hasProduction = false;

        for (RelativeState state : states
        ) {
            if (
                    state.direction() == OperationDirection.INJECTION
            ) {
                hasInjection = true;
            }
            if (
                    state.direction() == OperationDirection.PRODUCTION
            ) {
                hasProduction = true;
            }
        }
        if (
                !hasInjection || !hasProduction
        ) {
            throw new BusinessException(
                    400,
                    "周期【"
                            + cycleName
                            + "】不完整：必须同时包含注气和采气数据"
            );
        }
    }

    private DiagnosticCurveModels.RunningPoint buildPoint(
            int sequence,
            String time,
            String cycle,
            OperationDirection direction,
            double gas,
            double relativeInventory,
            double baseInventory,
            double cycleMinInventory,
            double cycleMaxInventory,
            PvtLookup pvt,
            double slope,
            double amplitude,
            double lowerPz,
            double upperPz,
            double lowerPressure,
            double upperPressure,
            boolean synthetic
    ) {
        final double inventory = baseInventory + relativeInventory;

        final double stablePz = slope * inventory;

        if (
                !Double.isFinite(
                        inventory
                )
                        || !Double.isFinite(
                        stablePz
                )
                        || inventory <= 0
        ) {
            throw new BusinessException(
                    400,
                    "库存量或理论P/Z计算异常"
            );
        }
        final double cycleSpan = cycleMaxInventory - cycleMinInventory;
        if (
                !Double.isFinite(
                        cycleSpan
                )
                        || cycleSpan <= EPSILON
        ) {
            throw new BusinessException(
                    400,
                    "周期【"
                            + cycle
                            + "】库存范围无效"
            );
        }
        final double u =
                clamp(
                        (
                                inventory
                                        - cycleMinInventory
                        )
                                / cycleSpan,
                        0.0,
                        1.0
                );

        final double shape = 4.0 * u * (1.0 - u);
        final double requestedOffset =
                synthetic
                        ? 0.0
                        : amplitude
                        * shape;

        final double boundedStablePz = clamp(stablePz, lowerPz, upperPz);
        final double targetPz;
        if (
                direction == OperationDirection.INJECTION
        ) {
            final double availableUp = Math.max(0.0, upperPz - boundedStablePz);
            targetPz = boundedStablePz + Math.min(requestedOffset, availableUp);
        } else {
            final double availableDown =
                    Math.max(
                            0.0,
                            boundedStablePz
                                    - lowerPz
                    );

            targetPz =
                    boundedStablePz
                            - Math.min(
                            requestedOffset,
                            availableDown
                    );
        }

        final double boundedTargetPz =
                clamp(
                        targetPz,
                        lowerPz,
                        upperPz
                );

        /*
         * 根据所选 PVT 反求压力 P：
         */
        final double pressure =
                solvePressureForPz(
                        pvt,
                        boundedTargetPz,
                        lowerPressure,
                        upperPressure
                );

        final double z =
                resolveZ(
                        pvt,
                        pressure
                );

        final double actualPz = pressure / z;

        if (
                !Double.isFinite(
                        pressure
                )
                        || !Double.isFinite(
                        z
                )
                        || !Double.isFinite(
                        actualPz
                )
                        || pressure <= 0
                        || z <= 0
                        || actualPz <= 0
        ) {
            throw new BusinessException(
                    400,
                    "P/Z运行点计算异常"
            );
        }

        return new DiagnosticCurveModels.RunningPoint(
                sequence,
                time,
                cycle,
                direction.name(),
                gas,
                relativeInventory,
                inventory,
                stablePz,
                pressure,
                z,
                actualPz,
                synthetic
        );
    }

    /**
     * PVT 预处理。
     */
    private PvtLookup preparePvt(
            DiagnosticCurveModels.PvtData pvt
    ) {
        if (
                pvt == null
        ) {
            throw new BusinessException(
                    400,
                    "请选择有效PVT表"
            );
        }

        final List<DiagnosticCurveModels.PvtZPoint> rawCurve = pvt.zCurve();
        if (
                rawCurve != null && !rawCurve.isEmpty()
        ) {
            final List<DiagnosticCurveModels.PvtZPoint> sorted =
                    rawCurve.stream()
                            .filter(
                                    point ->
                                            point != null
                                                    && point.pressure() != null
                                                    && point.zFactor() != null
                                                    && Double.isFinite(
                                                    point.pressure()
                                            )
                                                    && Double.isFinite(
                                                    point.zFactor()
                                            )
                                                    && point.pressure() > 0
                                                    && point.zFactor() > 0
                            )
                            .sorted(
                                    Comparator.comparingDouble(
                                            DiagnosticCurveModels.PvtZPoint::pressure
                                    )
                            )
                            .toList();

            final List<DiagnosticCurveModels.PvtZPoint> normalized = mergeDuplicatePressurePoints(sorted);

            if (
                    normalized.size() < 2
            ) {
                throw new BusinessException(
                        400,
                        "所选PVT表的压力-Z曲线至少需要2个有效压力点"
                );
            }

            return new PvtLookup(
                    null,
                    List.copyOf(
                            normalized
                    )
            );
        }

        if (
                pvt.fixedZ() != null
                        && Double.isFinite(
                        pvt.fixedZ()
                )
                        && pvt.fixedZ() > 0
        ) {
            return new PvtLookup(
                    pvt.fixedZ(),
                    List.of()
            );
        }

        throw new BusinessException(
                400,
                "所选PVT表没有有效的Z(P)曲线或固定Z"
        );
    }

    private List<DiagnosticCurveModels.PvtZPoint> mergeDuplicatePressurePoints(
            List<DiagnosticCurveModels.PvtZPoint> sorted
    ) {
        final List<DiagnosticCurveModels.PvtZPoint> result = new ArrayList<>();
        for (
                DiagnosticCurveModels.PvtZPoint point : sorted
        ) {
            if (
                    result.isEmpty()
            ) {
                result.add(point);
                continue;
            }

            final int lastIndex = result.size() - 1;
            final DiagnosticCurveModels.PvtZPoint last =
                    result.get(
                            lastIndex
                    );

            if (
                    Math.abs(
                            point.pressure()
                                    - last.pressure()
                    ) <= PRESSURE_TOLERANCE
            ) {
                result.set(
                        lastIndex,
                        new DiagnosticCurveModels.PvtZPoint(
                                (
                                        last.pressure()
                                                + point.pressure()
                                ) / 2.0,
                                (
                                        last.zFactor()
                                                + point.zFactor()
                                ) / 2.0
                        )
                );
            } else {
                result.add(
                        point
                );
            }
        }

        return result;
    }

    /**
     * Z(P) 曲线模式下，用户输入的压力上下限必须落在 PVT 表覆盖范围内。
     */
    private void validatePvtPressureCoverage(
            PvtLookup pvt,
            double lowerPressure,
            double upperPressure
    ) {
        if (
                pvt.fixedZ() != null
        ) {
            return;
        }

        final List<DiagnosticCurveModels.PvtZPoint> curve = pvt.curve();
        final double pvtMinPressure = curve.get(0).pressure();
        final double pvtMaxPressure =
                curve.get(
                                curve.size()
                                        - 1
                        )
                        .pressure();

        if (
                lowerPressure
                        < pvtMinPressure
                        - PRESSURE_TOLERANCE
                        || upperPressure
                        > pvtMaxPressure
                        + PRESSURE_TOLERANCE
        ) {
            throw new BusinessException(
                    400,
                    "输入压力范围["
                            + lowerPressure
                            + ", "
                            + upperPressure
                            + "] MPa超出所选PVT的Z(P)压力范围["
                            + pvtMinPressure
                            + ", "
                            + pvtMaxPressure
                            + "] MPa"
            );
        }
    }

    private void validatePressureOverZMonotonic(
            PvtLookup pvt,
            double lowerPressure,
            double upperPressure
    ) {
        if (
                pvt.fixedZ() != null
        ) {
            return;
        }

        final List<Double> pressures =
                new ArrayList<>();

        pressures.add(
                lowerPressure
        );

        for (
                DiagnosticCurveModels.PvtZPoint point
                : pvt.curve()
        ) {
            if (
                    point.pressure()
                            > lowerPressure
                            + PRESSURE_TOLERANCE
                            && point.pressure()
                            < upperPressure
                            - PRESSURE_TOLERANCE
            ) {
                pressures.add(
                        point.pressure()
                );
            }
        }

        pressures.add(
                upperPressure
        );

        double previous =
                Double.NEGATIVE_INFINITY;
        for (
                double pressure : pressures
        ) {
            final double pz = pressure / resolveZ(pvt, pressure);
            final double tolerance =
                    1e-10
                            * Math.max(
                            1.0,
                            Math.abs(
                                    previous
                            )
                    );

            if (
                    previous
                            != Double.NEGATIVE_INFINITY
                            && pz <= previous
                            + tolerance
            ) {
                throw new BusinessException(
                        400,
                        "所选PVT在输入压力区间内的P/Z(P)不是单调递增，"
                                + "无法唯一反求运行压力，请检查PVT数据"
                );
            }
            previous = pz;
        }
    }

    /**
     * 取指定压力下的 Z。
     */
    private double resolveZ(
            PvtLookup pvt,
            double pressure
    ) {
        if (
                pvt.fixedZ() != null
        ) {
            return pvt.fixedZ();
        }

        final List<DiagnosticCurveModels.PvtZPoint> curve = pvt.curve();
        final DiagnosticCurveModels.PvtZPoint first = curve.get(0);
        final DiagnosticCurveModels.PvtZPoint last = curve.get(curve.size() - 1);

        if (
                pressure
                        < first.pressure()
                        - PRESSURE_TOLERANCE
                        || pressure
                        > last.pressure()
                        + PRESSURE_TOLERANCE
        ) {
            throw new BusinessException(
                    400,
                    "压力 "
                            + pressure
                            + " MPa 超出PVT的Z(P)插值范围"
            );
        }

        if (
                Math.abs(pressure - first.pressure()) <= PRESSURE_TOLERANCE
        ) {
            return first.zFactor();
        }

        if (
                Math.abs(pressure - last.pressure()) <= PRESSURE_TOLERANCE
        ) {
            return last.zFactor();
        }

        for (
                int i = 0; i < curve.size() - 1; i++
        ) {
            final DiagnosticCurveModels.PvtZPoint left = curve.get(i);
            final DiagnosticCurveModels.PvtZPoint right = curve.get(i + 1);
            if (
                    pressure
                            >= left.pressure()
                            - PRESSURE_TOLERANCE
                            && pressure
                            <= right.pressure()
                            + PRESSURE_TOLERANCE
            ) {
                return interpolateZ(
                        pressure,
                        left,
                        right
                );
            }
        }

        throw new BusinessException(
                400,
                "无法根据PVT表取得压力 "
                        + pressure
                        + " MPa 对应的Z"
        );
    }

    private double interpolateZ(
            double pressure,
            DiagnosticCurveModels.PvtZPoint left,
            DiagnosticCurveModels.PvtZPoint right
    ) {
        final double dp = right.pressure() - left.pressure();
        if (
                Math.abs(dp) <= PRESSURE_TOLERANCE
        ) {
            return (
                    left.zFactor() + right.zFactor()) / 2.0;
        }

        final double ratio = (pressure - left.pressure()) / dp;

        return left.zFactor() + ratio * (right.zFactor() - left.zFactor()
        );
    }

    /**
     * 给定目标 P/Z，反求压力 P。
     */
    private double solvePressureForPz(
            PvtLookup pvt,
            double targetPz,
            double lowerPressure,
            double upperPressure
    ) {
        if (
                pvt.fixedZ() != null
        ) {
            return clamp(
                    targetPz
                            * pvt.fixedZ(),
                    lowerPressure,
                    upperPressure
            );
        }

        final double lowerValue =
                lowerPressure
                        / resolveZ(
                        pvt,
                        lowerPressure
                );

        final double upperValue =
                upperPressure
                        / resolveZ(
                        pvt,
                        upperPressure
                );

        if (
                targetPz <= lowerValue
                        + EPSILON
        ) {
            return lowerPressure;
        }

        if (
                targetPz >= upperValue - EPSILON
        ) {
            return upperPressure;
        }

        double low = lowerPressure;
        double high = upperPressure;

        for (
                int i = 0; i < PRESSURE_SOLVE_ITERATIONS; i++
        ) {
            final double mid = (low + high) / 2.0;

            final double midPz = mid / resolveZ(pvt, mid);

            if (
                    midPz < targetPz
            ) {
                low = mid;
            } else {
                high = mid;
            }
        }

        return (low + high) / 2.0;
    }

    /**
     * “第1周期注气”“第1周期采气”统一归成“第1周期”。
     */
    private String normalizeCycleKey(
            String raw
    ) {
        if (
                raw == null || raw.isBlank()
        ) {
            return "";
        }

        String value = raw.trim();

        value = value
                .replace(
                        "注气",
                        ""
                )
                .replace(
                        "采气",
                        ""
                )
                .replace(
                        "产气",
                        ""
                )
                .replace(
                        "注入",
                        ""
                )
                .replace(
                        "采出",
                        ""
                )
                .replace(
                        "Injection",
                        ""
                )
                .replace(
                        "injection",
                        ""
                )
                .replace(
                        "Production",
                        ""
                )
                .replace(
                        "production",
                        ""
                )
                .replace(
                        "Withdrawal",
                        ""
                )
                .replace(
                        "withdrawal",
                        ""
                )
                .replace(
                        "注",
                        ""
                )
                .replace(
                        "采",
                        ""
                )
                .replace(
                        "产",
                        ""
                );

        value =
                value.replaceAll(
                        "[\\s\\-_/\\\\|:：,，;；()（）\\[\\]【】]+",
                        ""
                );

        if (
                value.equals(
                        "周期"
                )
                        || value.equals(
                        "阶段"
                )
        ) {
            return "";
        }

        return value;
    }

    /**
     * 判断注气/采气方向。
     */
    private OperationDirection resolveDirection(
            DiagnosticCurveModels.ProductionDataItem item
    ) {
        final String cycle =
                item.cycle() == null
                        ? ""
                        : item.cycle()
                        .trim();

        final String lowerCycle =
                cycle.toLowerCase();

        if (
                cycle.contains(
                        "注"
                )
                        || lowerCycle.contains(
                        "injection"
                )
        ) {
            return OperationDirection.INJECTION;
        }

        if (
                cycle.contains(
                        "采"
                )
                        || cycle.contains(
                        "产"
                )
                        || lowerCycle.contains(
                        "production"
                )
                        || lowerCycle.contains(
                        "withdrawal"
                )
        ) {
            return OperationDirection.PRODUCTION;
        }

        /*
         * 与当前前端约定保持一致：
         * 注气 < 0，采气 > 0。
         */
        if (
                item.gas()
                        < 0
        ) {
            return OperationDirection.INJECTION;
        }

        if (
                item.gas()
                        > 0
        ) {
            return OperationDirection.PRODUCTION;
        }

        throw new BusinessException(
                400,
                "第 "
                        + item.sequence()
                        + " 行无法判断注气/采气方向"
        );
    }

    private void validateRequest(
            DiagnosticCurveModels.CalculateRequest request
    ) {
        if (
                request == null
        ) {
            throw new BusinessException(
                    400,
                    "计算参数不能为空"
            );
        }

        if (
                request.upperLimit() == null
                        || request.lowerLimit() == null
                        || !Double.isFinite(
                        request.upperLimit()
                )
                        || !Double.isFinite(
                        request.lowerLimit()
                )
                        || request.lowerLimit()
                        <= 0
                        || request.upperLimit()
                        <= request.lowerLimit()
        ) {
            throw new BusinessException(
                    400,
                    "压力上下限错误：上限必须大于下限，且下限必须大于0"
            );
        }

        if (
                request.productionData() == null
                        || request.productionData()
                        .size()
                        < 2
        ) {
            throw new BusinessException(
                    400,
                    "至少需要2行注采数据"
            );
        }
    }

    private double clamp(
            double value,
            double min,
            double max
    ) {
        return Math.max(
                min,
                Math.min(
                        max,
                        value
                )
        );
    }

    private enum OperationDirection {
        INJECTION,
        PRODUCTION
    }

    /**
     * 一行数据计算前后的相对累计库存。
     */
    private record RelativeState(
            DiagnosticCurveModels.ProductionDataItem item,
            String cycleName,
            OperationDirection direction,
            double relativeBefore,
            double relativeAfter
    ) {
    }

    /**
     * PVT 查找对象。
     */
    private record PvtLookup(
            Double fixedZ,
            List<DiagnosticCurveModels.PvtZPoint> curve
    ) {
    }
}
