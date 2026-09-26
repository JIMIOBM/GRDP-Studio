package com.grdp.studio.wellbore.erosion.model;

import com.grdp.studio.wellbore.erosion.dto.ErosionRequest;
import com.grdp.studio.wellbore.erosion.dto.ErosionResult;
import com.grdp.studio.wellbore.erosion.dto.ErosionResult.Status;
import java.util.ArrayList;
import java.util.List;

/**
 * 谢川，邓兴旺，完颜祺琪，等. 储气库P110注采管柱气-液-固三相冲蚀机理及临界冲蚀预测研究.
 * 石油钻采工艺, 2026, 48(3):361-369. DOI:10.13639/j.odpt.202508034.
 * 仅针对P110气-液-固三相；不用于CO2腐蚀或直接推广至其他材质。
 * 标定范围：气流速17~50 m/s，Hs=0.0001~0.02%，HL=0.001~0.01%；超出属于外推。
 * 用户提供原文明确 HL/Hs 单位为 %，故 f、k 使用百分数数值；密度权重使用 /100 后的体积分数。
 * 例如 0.01% 在 f/k 中为0.01，在密度式中为0.0001；禁止将两者混用。
 * 系数逐项遵照本次需求/PPT，不自行修改量级或补入CO2项。
 * 当前缺少论文比较流速的完整定义，实际值为气相表观流速，比较仅供参考；不发布临界气量。
 */
public final class ErosionCalculator {
    public static final String VERSION = "P110_PERCENT_V1_VELOCITY_PENDING";
    public static final String VELOCITY_REASON = "论文比较流速的完整定义尚未核实（原文符号说明中ve为mm/a）；当前采用气相表观流速供参考，暂不发布临界产气量";

    public ErosionResult calculate(ErosionRequest r) {
        try {
            validateInput(r);
        } catch (IllegalArgumentException ex) {
            return failure(Status.NOT_APPLICABLE, ex.getMessage());
        }
        try {
            double sand = calculateSandFactor(r.sandContentPercent());
            double liquid = calculateLiquidHoldupFactor(r.liquidHoldupPercent());
            double coefficient = calculateCriticalCoefficient(liquid, sand);
            double density = calculateMixtureDensity(r.liquidHoldupPercent(), r.sandContentPercent(),
                    r.gasDensityKgM3(), r.liquidDensityKgM3(), r.sandDensityKgM3());
            double critical = calculateCriticalVelocity(coefficient, density);
            double actual = calculateActualVelocity(r.actualGasRate1e4M3d(), r.tubingInnerDiameterMm(), r.gasVolumeFactor());
            double ratio = finite(actual / critical, "流速利用率");
            List<String> domainViolations = validateModelDomain(r.liquidHoldupPercent(), r.sandContentPercent(), actual);
            boolean applicable = domainViolations.isEmpty();
            return new ErosionResult(sand, liquid, coefficient, density, actual, critical, ratio,
                    null, classify(actual, critical), applicable, false,
                    applicable ? VELOCITY_REASON : String.join("；", domainViolations), VELOCITY_REASON, VERSION);
        } catch (IllegalArgumentException ex) {
            return failure(Status.NOT_APPLICABLE, ex.getMessage());
        } catch (ArithmeticException ex) {
            return failure(Status.CALCULATION_ERROR, ex.getMessage());
        }
    }

    private ErosionResult failure(Status status, String reason) {
        return new ErosionResult(null, null, null, null, null, null, null, null,
                status, false, false, reason, VELOCITY_REASON, VERSION);
    }

    public static double percentToFraction(double percent) { return finite(percent / 100d, "体积分数"); }
    /** Explicit identity conversion, intentionally separate from density's percentToFraction. */
    public static double percentToFormulaArgument(double percent) { return finite(percent, "公式百分数"); }

    public double calculateSandFactor(double sandPercent) {
        positive(sandPercent, "含砂率必须大于0，不能对0取对数");
        return positive(finite(-13.5 * Math.log(percentToFormulaArgument(sandPercent)) + 7.2138, "含砂修正"), "含砂修正k必须大于0");
    }
    public double calculateLiquidHoldupFactor(double liquidPercent) {
        nonNegative(liquidPercent, "持液率不能小于0");
        double h = percentToFormulaArgument(liquidPercent);
        return finite(277778 * h * h - 8388.9 * h + 137.11, "持液修正");
    }
    public double calculateCriticalCoefficient(double liquidFactor, double sandFactor) {
        positive(sandFactor, "含砂修正k必须大于0");
        double sandCorrection = 0.22 * Math.log(sandFactor) - 1.28;
        return positive(finite(-0.05 * liquidFactor * sandCorrection, "临界冲蚀系数"), "临界冲蚀系数C<=0，模型输入不适用");
    }
    public double calculateMixtureDensity(double liquidPercent, double sandPercent, double gas, double liquid, double sand) {
        validateFractions(liquidPercent, sandPercent);
        positive(gas, "气体密度必须大于0"); positive(liquid, "液体密度必须大于0"); positive(sand, "砂粒密度必须大于0");
        double hl = percentToFraction(liquidPercent), hs = percentToFraction(sandPercent);
        return positive(finite((1 - hl - hs) * gas + hl * liquid + hs * sand, "混合密度"), "混合密度必须大于0");
    }
    public double calculateCriticalVelocity(double coefficient, double density) {
        positive(coefficient, "临界冲蚀系数C必须大于0"); positive(density, "混合密度必须大于0");
        return positive(finite(coefficient / Math.sqrt(density), "临界冲蚀流速"), "临界流速必须大于0");
    }
    public double calculateArea(double diameterMm) {
        positive(diameterMm, "管柱内径必须大于0");
        double diameterM = diameterMm / 1000d;
        return positive(finite(Math.PI * diameterM * diameterM / 4d, "管柱截面积"), "管柱截面积必须大于0");
    }
    public double calculateActualVelocity(double gasRate, double diameterMm, double bg) {
        nonNegative(gasRate, "日产气量不能小于0"); positive(bg, "Bg必须大于0");
        double localRate = finite(gasRate * 10000d * bg / 86400d, "当地气体流量");
        return finite(localRate / calculateArea(diameterMm), "气相表观流速");
    }
    /** Algebraic inverse of gas superficial velocity ONLY. Not published until paper velocity is verified. */
    public double calculateCriticalGasRate(double velocity, double diameterMm, double bg) {
        positive(velocity, "临界流速必须大于0"); positive(bg, "Bg必须大于0");
        double localRate = finite(calculateArea(diameterMm) * velocity, "临界当地气体流量");
        return positive(finite(localRate * 86400d / bg / 10000d, "临界标况气量"), "临界气量必须大于0");
    }
    public Status classify(double actual, double critical) {
        nonNegative(actual, "实际速度不能小于0"); positive(critical, "临界速度必须大于0");
        double tolerance = 1e-9 * Math.max(Math.abs(actual), Math.abs(critical));
        if (Math.abs(actual - critical) <= tolerance) return Status.AT_LIMIT;
        return actual < critical ? Status.BELOW_LIMIT : Status.ABOVE_LIMIT;
    }
    public List<String> validateModelDomain(double hl, double hs, double actual) {
        var violations = new ArrayList<String>();
        if (hl < 0.001 || hl > 0.01) violations.add("持液率超出0.001%～0.01%");
        if (hs < 0.0001 || hs > 0.02) violations.add("含砂率超出0.0001%～0.02%");
        if (actual < 17 || actual > 50) violations.add("气相表观流速超出17～50 m/s");
        if (!violations.isEmpty()) violations.add("超出标定范围");
        return List.copyOf(violations);
    }
    public void validateInput(ErosionRequest r) {
        if (r == null) throw new IllegalArgumentException("计算输入不能为空");
        positive(required(r.pressureMpa(), "压力"), "绝压必须大于0");
        if (required(r.temperatureC(), "温度") <= -273.15) throw new IllegalArgumentException("温度必须高于绝对零度");
        validateFractions(required(r.liquidHoldupPercent(), "持液率"), required(r.sandContentPercent(), "含砂率"));
        positive(required(r.gasDensityKgM3(), "气体密度"), "气体密度必须大于0");
        positive(required(r.liquidDensityKgM3(), "液体密度"), "液体密度必须大于0");
        positive(required(r.sandDensityKgM3(), "砂粒密度"), "砂粒密度必须大于0");
        positive(required(r.gasVolumeFactor(), "Bg"), "Bg必须大于0");
        positive(required(r.tubingInnerDiameterMm(), "管柱内径"), "管柱内径必须大于0");
        nonNegative(required(r.actualGasRate1e4M3d(), "日产气量"), "日产气量不能小于0");
    }
    private void validateFractions(double hl, double hs) {
        nonNegative(hl, "持液率不能小于0"); positive(hs, "含砂率必须大于0");
        if (hl + hs >= 100) throw new IllegalArgumentException("持液率与含砂率之和必须小于100%（体积分数之和<1）");
    }
    private static double required(Double value, String label) {
        if (value == null || !Double.isFinite(value)) throw new IllegalArgumentException(label + "必须为有限数值");
        return value;
    }
    private static double positive(double value, String message) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(message);
        return value;
    }
    private static void nonNegative(double value, String message) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(message);
    }
    private static double finite(double value, String label) {
        if (!Double.isFinite(value)) throw new ArithmeticException(label + "出现数值溢出，计算失败");
        return value;
    }
}
