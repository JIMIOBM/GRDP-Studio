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
 *     <li>理论基准线始终是 P/Z = kG，严格经过原点；</li>
 *     <li>页面输入的上限/下限是压力P的运行上下限，不是图表边界；</li>
 *     <li>Pmin/Z(Pmin) 对应储气库最低运行状态；</li>
 *     <li>Pmax/Z(Pmax) 对应最大设计周期的最高运行状态；</li>
 *     <li>每个周期按Excel cycle字段独立生成闭合梭形；</li>
 *     <li>不同周期间绝不连线；</li>
 *     <li>实际运行压力始终限制在用户输入的Pmin~Pmax内。</li>
 * </ol>
 *
 * <p>因为Excel没有实测压力，所以本图属于“基于压力上下限和周期工作气量重建”的诊断曲线。</p>
 */
@Service
public class DiagnosticCurveService {

    private static final double EPSILON = 1e-12;
    private static final double HYSTERESIS_RATIO = 0.12;
    private static final int PRESSURE_SOLVE_ITERATIONS = 80;
    private static final double STANDARD_LINE_EXTENSION_RATIO = 1.08;

    public DiagnosticCurveModels.CalculateResponse calculate(
            DiagnosticCurveModels.CalculateRequest request,
            String token,
            String cookie,
            String processEnv
    ) {
        validateRequest(request);
        final List<DiagnosticCurveModels.ProductionDataItem> data = request.productionData();
        final PvtLookup pvt = preparePvt(request.pvt());
        final List<CycleData> cycles = buildCycles(data);
        if (
                cycles.isEmpty()
        ) {
            throw new BusinessException(
                    400,
                    "没有找到完整注采周期"
            );
        }
        /*
         * 2. 每个周期计算一个“工作气量”。
         *
         * 注气总量与采气总量可能有少量差异，
         * 用两者平均值作为该周期的绘图工作气量。
         */
        double maxWorkingGas = 0.0;
        final List<CycleWorkingGas> workingGasList = new ArrayList<>(cycles.size());
        for (
                CycleData cycle
                : cycles
        ) {
            final double injectionGas = totalGas(cycle.injectionIndices(), data);
            final double productionGas = totalGas(cycle.productionIndices(), data);
            if (
                    injectionGas <= EPSILON || productionGas <= EPSILON
            ) {
                throw new BusinessException(
                        400, "周期【" + cycle.name() + "】注采气量无效"
                );
            }
            final double workingGas = (injectionGas + productionGas) / 2.0;
            if (!Double.isFinite(workingGas) || workingGas <= EPSILON) {
                throw new BusinessException(
                        400, "周期【" + cycle.name() + "】工作气量无效"
                );
            }
            workingGasList.add(
                    new CycleWorkingGas(
                            cycle,
                            injectionGas,
                            productionGas,
                            workingGas
                    )
            );

            maxWorkingGas = Math.max(maxWorkingGas, workingGas);
        }

        if (
                maxWorkingGas <= EPSILON
        ) {
            throw new BusinessException(
                    400,"最大周期工作气量为0"
            );
        }

        /*
         * 3. 压力运行上下限转换为P/Z端点。
         */
        final double lowerPressure = request.lowerLimit();
        final double upperPressure = request.upperLimit();
        final double lowerZ = resolveZ(pvt, lowerPressure);
        final double upperZ = resolveZ(pvt, upperPressure);
        final double lowerPz = lowerPressure / lowerZ;
        final double upperPz = upperPressure / upperZ;
        if (!Double.isFinite(lowerPz) || !Double.isFinite(upperPz) || lowerPz <= 0 || upperPz <= lowerPz)
        {
            throw new BusinessException(
                    400,
                    "压力上下限与所选PVT不能形成有效P/Z区间"
            );
        }

        /*
         * 过原点的直线
         */
        final double standardLineSlope = (upperPz - lowerPz) / maxWorkingGas;
        if (!Double.isFinite(standardLineSlope) || standardLineSlope <= EPSILON)
        {
            throw new BusinessException(
                    400,
                    "理论基准线斜率计算异常"
            );
        }
        final double baseInventory = lowerPz / standardLineSlope;
        if (
                !Double.isFinite(
                        baseInventory
                )
                        || baseInventory <= 0
        ) {
            throw new BusinessException(
                    400,
                    "基础库存量计算异常"
            );
        }

        /*
         * 每个周期独立生成一个闭合梭形。
         */
        final List<DiagnosticCurveModels.CycleCurve> cycleCurves = new ArrayList<>();
        final List<DiagnosticCurveModels.RunningPoint> flattened = new ArrayList<>();
        double minInventory = baseInventory;
        double maxInventory = baseInventory;
        double minPz = Double.POSITIVE_INFINITY;
        double maxPz = Double.NEGATIVE_INFINITY;
        for (
                CycleWorkingGas cycleWorkingGas
                : workingGasList
        ) {
            final CycleData cycle = cycleWorkingGas.cycle();
            final double workingGas = cycleWorkingGas.workingGas();
            final double cycleMinInventory = baseInventory;
            final double cycleMaxInventory = baseInventory + workingGas;
            final double cyclePzSpan = standardLineSlope * workingGas;
            final double amplitude = HYSTERESIS_RATIO * cyclePzSpan;
            final List<DiagnosticCurveModels.RunningPoint> points = new ArrayList<>();
            /*
             * 注气支路：
             */
            appendInjectionBranch(
                    points,
                    cycleWorkingGas,
                    data,
                    pvt,
                    cycleMinInventory,
                    cycleMaxInventory,
                    standardLineSlope,
                    amplitude,
                    lowerPz,
                    upperPz,
                    lowerPressure,
                    upperPressure
            );
            /*
             * 采气支路：
             */
            appendProductionBranch(
                    points,
                    cycleWorkingGas,
                    data,
                    pvt,
                    cycleMinInventory,
                    cycleMaxInventory,
                    standardLineSlope,
                    amplitude,
                    lowerPz,
                    upperPz,
                    lowerPressure,
                    upperPressure
            );
            for (
                    DiagnosticCurveModels.RunningPoint point
                    : points
            ) {
                minInventory = Math.min(minInventory, point.inventory());
                maxInventory = Math.max(maxInventory, point.inventory());
                minPz = Math.min(minPz, point.pressureOverZ());
                maxPz = Math.max(maxPz, point.pressureOverZ());
            }

            cycleCurves.add(
                    new DiagnosticCurveModels.CycleCurve(
                            cycle.name(),
                            List.copyOf(
                                    points
                            )
                    )
            );

            flattened.addAll(
                    points
            );
        }

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

        maxPz = Math.max(maxPz, standardLineEndPz);
        if (
                !Double.isFinite(minPz)
        ) {
            minPz = lowerPz;
        }

        return new DiagnosticCurveModels.CalculateResponse(
                List.copyOf(cycleCurves),
                List.copyOf(flattened),
                standardLine,
                baseInventory,
                minInventory,
                maxInventory,
                minPz,
                maxPz,
                standardLineSlope,
                lowerPressure,
                upperPressure
        );
    }

    /**
     * 注气支路：
     */
    private void appendInjectionBranch(
            List<DiagnosticCurveModels.RunningPoint> points,
            CycleWorkingGas cycleWorkingGas,
            List<DiagnosticCurveModels.ProductionDataItem> data,
            PvtLookup pvt,
            double cycleMinInventory,
            double cycleMaxInventory,
            double slope,
            double amplitude,
            double lowerPz,
            double upperPz,
            double lowerPressure,
            double upperPressure
    ) {

        final CycleData cycle = cycleWorkingGas.cycle();
        final List<Integer> indices = cycle.injectionIndices();
        final double totalInjectionGas = cycleWorkingGas.injectionGas();

        points.add(
                buildPoint(
                        0,
                        "",
                        cycle.name(),
                        OperationDirection.INJECTION,
                        0.0,
                        cycleMinInventory,
                        cycleMinInventory,
                        cycleMaxInventory,
                        pvt,
                        slope,
                        amplitude,
                        lowerPz,
                        upperPz,
                        lowerPressure,
                        upperPressure,
                        true
                )
        );

        double cumulative = 0.0;
        final double span = cycleMaxInventory - cycleMinInventory;
        for (
                int index
                : indices
        ) {
            final DiagnosticCurveModels.ProductionDataItem item =
                    data.get(
                            index
                    );
            cumulative += Math.abs(item.gas());
            final double progress = clamp(cumulative / totalInjectionGas, 0.0, 1.0);
            final double inventory = cycleMinInventory + progress * span;
            points.add(
                    buildPoint(
                            item.sequence(),
                            item.time(),
                            cycle.name(),
                            OperationDirection.INJECTION,
                            item.gas(),
                            inventory,
                            cycleMinInventory,
                            cycleMaxInventory,
                            pvt,
                            slope,
                            amplitude,
                            lowerPz,
                            upperPz,
                            lowerPressure,
                            upperPressure,
                            false
                    )
            );
        }
    }

    /**
     * 采气支路：
     */
    private void appendProductionBranch(
            List<DiagnosticCurveModels.RunningPoint> points,
            CycleWorkingGas cycleWorkingGas,
            List<DiagnosticCurveModels.ProductionDataItem> data,
            PvtLookup pvt,
            double cycleMinInventory,
            double cycleMaxInventory,
            double slope,
            double amplitude,
            double lowerPz,
            double upperPz,
            double lowerPressure,
            double upperPressure
    ) {

        final CycleData cycle = cycleWorkingGas.cycle();
        final List<Integer> indices = cycle.productionIndices();
        final double totalProductionGas = cycleWorkingGas.productionGas();
        double cumulative = 0.0;
        final double span = cycleMaxInventory - cycleMinInventory;
        for (
                int index
                : indices
        ) {

            final DiagnosticCurveModels.ProductionDataItem item = data.get(index);
            cumulative += Math.abs(item.gas());
            final double progress = clamp(cumulative / totalProductionGas, 0.0, 1.0);
            final double inventory = cycleMaxInventory - progress * span;

            points.add(
                    buildPoint(
                            item.sequence(),
                            item.time(),
                            cycle.name(),
                            OperationDirection.PRODUCTION,
                            item.gas(),
                            inventory,
                            cycleMinInventory,
                            cycleMaxInventory,
                            pvt,
                            slope,
                            amplitude,
                            lowerPz,
                            upperPz,
                            lowerPressure,
                            upperPressure,
                            false
                    )
            );
        }
    }

    private DiagnosticCurveModels.RunningPoint buildPoint(
            int sequence,
            String time,
            String cycle,
            OperationDirection direction,
            double gas,
            double inventory,
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
        final double basePz = slope * inventory;
        final double cycleInventorySpan = cycleMaxInventory - cycleMinInventory;
        if (
                cycleInventorySpan <= EPSILON
        ) {
            throw new BusinessException(
                    400,
                    "周期库存范围无效"
            );
        }
        final double u = clamp((inventory - cycleMinInventory) / cycleInventorySpan, 0.0, 1.0);
        final double shape = 4.0 * u * (1.0 - u);
        final double offset = amplitude * shape;
        final double rawTargetPz = direction == OperationDirection.INJECTION ? basePz + offset : basePz - offset;
        final double targetPz = clamp(rawTargetPz, lowerPz, upperPz);
        final double pressure = solvePressureForPz(pvt, targetPz, lowerPressure, upperPressure);
        final double z = resolveZ(pvt, pressure);
        final double actualPz = pressure / z;
        if (
                !Double.isFinite(actualPz) || actualPz <= 0
        ) {
            throw new BusinessException(
                    400,
                    "P/Z计算异常"
            );
        }

        return new DiagnosticCurveModels.RunningPoint(
                sequence,
                time,
                cycle,
                direction.name(),
                gas,
                inventory,
                pressure,
                z,
                actualPz,
                synthetic
        );
    }

    private double solvePressureForPz(
            PvtLookup pvt,
            double targetPz,
            double lowerPressure,
            double upperPressure
    ) {
        if (
                pvt.fixedZ() != null
        ) {

            final double pressure = targetPz * pvt.fixedZ();
            return clamp(
                    pressure,
                    lowerPressure,
                    upperPressure
            );
        }

        final double lowerValue = lowerPressure / resolveZ(pvt, lowerPressure);

        final double upperValue = upperPressure / resolveZ(pvt, upperPressure);

        if (
                targetPz <= lowerValue
        ) {
            return lowerPressure;
        }

        if (
                targetPz >= upperValue
        ) {
            return upperPressure;
        }

        double low = lowerPressure;
        double high = upperPressure;
        for (int i = 0; i < PRESSURE_SOLVE_ITERATIONS; i++
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

    private List<CycleData> buildCycles(
            List<DiagnosticCurveModels.ProductionDataItem> data
    ) {
        boolean hasExplicitCycle = true;
        for (
                DiagnosticCurveModels.ProductionDataItem item
                : data
        ) {
            if (
                    normalizeCycleKey(item.cycle()).isBlank()
            ) {
                hasExplicitCycle = false;
                break;
            }
        }

        if (hasExplicitCycle
        ) {
            return buildCyclesFromCycleField(
                    data
            );
        }
        return buildCyclesFromAdjacentPhases(data);
    }

    private List<CycleData> buildCyclesFromCycleField(
            List<DiagnosticCurveModels.ProductionDataItem> data
    ) {

        final Map<String, MutableCycle> grouped = new LinkedHashMap<>();
        for (int index = 0; index < data.size(); index++
        ) {

            final DiagnosticCurveModels.ProductionDataItem item =
                    data.get(index);

            final String cycleName = normalizeCycleKey(item.cycle());
            final OperationDirection direction = resolveDirection(item);
            final MutableCycle mutable = grouped.computeIfAbsent(cycleName, ignored ->
                            new MutableCycle(cycleName)
                    );
            if (
                    direction == OperationDirection.INJECTION
            ) {
                mutable.injectionIndices.add(index);
            } else {
                mutable.productionIndices.add(index);
            }
        }
        final List<CycleData> result = new ArrayList<>();
        for (
                MutableCycle mutable : grouped.values()
        ) {
            validateCompleteCycle(
                    mutable.name,
                    mutable.injectionIndices,
                    mutable.productionIndices
            );

            result.add(
                    new CycleData(
                            mutable.name,
                            List.copyOf(
                                    mutable.injectionIndices
                            ),
                            List.copyOf(
                                    mutable.productionIndices
                            )
                    )
            );
        }

        return result;
    }

    private List<CycleData> buildCyclesFromAdjacentPhases(
            List<DiagnosticCurveModels.ProductionDataItem> data
    ) {

        final List<OperationPhase> phases = buildOperationPhases(data);

        if (
                phases.size() % 2 != 0
        ) {
            throw new BusinessException(
                    400, "周期数据不完整：最后一个注/采阶段没有配对。" + "建议在Excel周期列写成“第1周期注气/第1周期采气”。"
            );
        }

        final List<CycleData> result = new ArrayList<>();

        for (int i = 0; i < phases.size(); i += 2
        ) {

            final OperationPhase first =
                    phases.get(i);

            final OperationPhase second = phases.get(i + 1);
            if (
                    first.direction() == second.direction()
            ) {
                throw new BusinessException(
                        400, "自动周期配对失败：相邻两个阶段方向相同"
                );
            }

            final List<Integer> injectionIndices = new ArrayList<>();
            final List<Integer> productionIndices = new ArrayList<>();

            appendPhaseIndices(
                    first,
                    injectionIndices,
                    productionIndices
            );

            appendPhaseIndices(
                    second,
                    injectionIndices,
                    productionIndices
            );

            final String name = "周期" + (i / 2 + 1);

            validateCompleteCycle(
                    name,
                    injectionIndices,
                    productionIndices
            );

            result.add(
                    new CycleData(
                            name,
                            List.copyOf(injectionIndices),
                            List.copyOf(productionIndices)
                    )
            );
        }
        return result;
    }

    private List<OperationPhase> buildOperationPhases(
            List<DiagnosticCurveModels.ProductionDataItem> data
    ) {

        final List<OperationPhase> phases = new ArrayList<>();
        int start = 0;
        OperationDirection current = resolveDirection(data.get(0));
        for (
                int i = 1;
                i < data.size();
                i++
        ) {

            final OperationDirection direction = resolveDirection(data.get(i));
            if (
                    direction != current
            ) {
                phases.add(
                        new OperationPhase(
                                start,
                                i - 1,
                                current
                        )
                );
                start = i;
                current = direction;
            }
        }
        phases.add(
                new OperationPhase(
                        start,
                        data.size() - 1,
                        current
                )
        );
        return phases;
    }

    private void appendPhaseIndices(
            OperationPhase phase,
            List<Integer> injectionIndices,
            List<Integer> productionIndices
    ) {
        for (
                int index = phase.startIndex();
                index <= phase.endIndex();
                index++
        ) {
            if (
                    phase.direction() == OperationDirection.INJECTION
            ) {
                injectionIndices.add(
                        index
                );
            } else {
                productionIndices.add(
                        index
                );
            }
        }
    }

    private void validateCompleteCycle(
            String name,
            List<Integer> injectionIndices,
            List<Integer> productionIndices
    ) {
        if (
                injectionIndices.isEmpty()
                        || productionIndices.isEmpty()
        ) {
            throw new BusinessException(
                    400, "周期【" + name + "】不完整：必须同时包含注气和采气数据"
            );
        }
    }

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
                        );

        value = value.replaceAll(
                        "[\\s\\-_/\\\\|:：,，;；()（）\\[\\]【】]+",
                        ""
                );

        if (
                value.equals("周期") || value.equals("阶段")
        ) {
            return "";
        }
        return value;
    }

    private OperationDirection resolveDirection(
            DiagnosticCurveModels.ProductionDataItem item
    ) {

        final String cycle = item.cycle() == null ? "" : item.cycle().trim();

        if (
                cycle.contains("注")
        ) {
            return OperationDirection.INJECTION;
        }

        if (
                cycle.contains("采") || cycle.contains("产")
        ) {
            return OperationDirection.PRODUCTION;
        }

        if (
                item.gas() < 0
        ) {
            return OperationDirection.INJECTION;
        }

        if (
                item.gas() > 0
        ) {
            return OperationDirection.PRODUCTION;
        }
        throw new BusinessException(
                400, "第 " + item.sequence() + " 行无法判断注气/采气方向"
        );
    }

    private double totalGas(
            List<Integer> indices,
            List<DiagnosticCurveModels.ProductionDataItem> data
    ) {

        double total = 0.0;

        for (int index : indices
        ) {
            final Double gas = data.get(index).gas();
            if (
                    gas == null || !Double.isFinite(gas) || Math.abs(gas) <= EPSILON
            ) {
                throw new BusinessException(
                        400, "第 " + data.get(index).sequence() + " 行注/采气量无效"
                );
            }
            total += Math.abs(gas);
        }

        return total;
    }
    /**
     * PVT预处理。
     */
    private PvtLookup preparePvt(
            DiagnosticCurveModels.PvtData pvt
    ) {
        if (
                pvt == null
        ) {
            throw new BusinessException(
                    400, "请选择有效PVT表"
            );
        }

        if (
                pvt.fixedZ() != null && Double.isFinite(pvt.fixedZ()) && pvt.fixedZ() > 0
        ) {
            return new PvtLookup(pvt.fixedZ(), List.of());
        }

        final List<DiagnosticCurveModels.PvtZPoint> curve = pvt.zCurve();

        if (
                curve == null || curve.isEmpty()
        ) {
            throw new BusinessException(
                    400, "所选PVT表没有有效Z数据"
            );
        }

        final List<DiagnosticCurveModels.PvtZPoint> sorted =
                curve.stream()
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

        if (
                sorted.isEmpty()
        ) {
            throw new BusinessException(
                    400, "所选PVT表中的Z曲线没有有效点"
            );
        }

        return new PvtLookup(
                null,
                sorted
        );
    }

    /**
     * 取指定压力下的Z。
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

        if (
                curve.size() == 1
        ) {
            return curve.get(0).zFactor();
        }

        if (
                pressure <= curve.get(0).pressure()
        ) {
            return curve.get(0).zFactor();
        }

        final int last = curve.size() - 1;

        if (
                pressure >= curve.get(last).pressure()
        ) {
            return curve.get(last).zFactor();
        }

        for (int i = 0; i < curve.size() - 1; i++
        ) {
            final DiagnosticCurveModels.PvtZPoint left = curve.get(i);
            final DiagnosticCurveModels.PvtZPoint right = curve.get(i + 1);

            if (
                    pressure >= left.pressure() && pressure <= right.pressure()
            ) {
                return interpolate(pressure, left, right);
            }
        }

        throw new BusinessException(
                400, "无法根据PVT表取得Z"
        );
    }

    private double interpolate(
            double pressure,
            DiagnosticCurveModels.PvtZPoint left,
            DiagnosticCurveModels.PvtZPoint right
    ) {
        final double dp = right.pressure() - left.pressure();
        if (
                Math.abs(dp) <= EPSILON
        ) {
            return (
                    left.zFactor() + right.zFactor()) / 2.0;
        }

        final double ratio = (pressure - left.pressure()) / dp;

        return left.zFactor() + ratio * (right.zFactor() - left.zFactor());
    }

    private void validateRequest(
            DiagnosticCurveModels.CalculateRequest request
    ) {
        if (
                request.upperLimit() == null
                        || request.lowerLimit() == null
                        || !Double.isFinite(
                        request.upperLimit()
                )
                        || !Double.isFinite(
                        request.lowerLimit()
                )
                        || request.lowerLimit() <= 0
                        || request.upperLimit()
                        <= request.lowerLimit()
        ) {
            throw new BusinessException(
                    400, "压力上下限错误：上限必须大于下限，且下限必须大于0"
            );
        }

        if (
                request.productionData() == null || request.productionData().size() < 2
        ) {
            throw new BusinessException(
                    400, "至少需要2行生产数据"
            );
        }
    }

    private double clamp(
            double value,
            double min,
            double max
    ) {
        return Math.max(min, Math.min(max, value));
    }

    private enum OperationDirection {
        INJECTION,
        PRODUCTION
    }

    private record OperationPhase(
            int startIndex,
            int endIndex,
            OperationDirection direction
    ) {
    }

    private record CycleData(
            String name,
            List<Integer> injectionIndices,
            List<Integer> productionIndices
    ) {
    }

    private record CycleWorkingGas(
            CycleData cycle,
            double injectionGas,
            double productionGas,
            double workingGas
    ) {
    }

    private record PvtLookup(
            Double fixedZ,
            List<DiagnosticCurveModels.PvtZPoint> curve
    ) {
    }

    private static final class MutableCycle {
        private final String name;
        private final List<Integer> injectionIndices = new ArrayList<>();
        private final List<Integer> productionIndices = new ArrayList<>();

        private MutableCycle(
                String name
        ) {
            this.name = name;
        }
    }
}
