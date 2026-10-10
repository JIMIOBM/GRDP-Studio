package com.grdp.studio.wellbore.sand.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.wellbore.sand.dto.SandCriticalVelocityRequest;
import com.grdp.studio.wellbore.sand.dto.SandCriticalVelocityResult;
import com.grdp.studio.wellbore.sand.dto.SandProductionRequest;
import com.grdp.studio.wellbore.sand.dto.SandProductionResult;
import org.springframework.stereotype.Service;

/**
 * 页岩气井临界出砂产量预测。
 * 公式来自殷洪川等《页岩气井临界出砂产量预测方法》（特种油气藏，2023）：
 * Vs = m/ρs（支撑剂体积，m³）；Ss = Vs/xf（裂缝张开面总面积，m²）；
 * Qg = 8.64 × Ss × vg（临界出砂日产气量，10⁴m³/d，8.64 = 86400/10⁴）。
 * 临界出砂流速 vg 由实验数据（闭合压力 5~50 MPa）线性插值查得，可手动覆盖。
 */
@Service
public class SandProductionCalculator {
    /** 闭合压力(MPa) → 临界出砂流速(m/s) 实验点。 */
    private record Point(double closurePressureMpa, double criticalVelocityMS) {}

    /** 70/140目石英砂实验数据（论文表2）。 */
    private static final Point[] QUARTZ = {
            new Point(5, 0.11), new Point(15, 0.14), new Point(20, 0.12),
            new Point(30, 0.06), new Point(40, 0.11), new Point(50, 0.13)
    };
    /** 复合压裂砂（70/140目石英砂:40/70目陶粒=6:4）实验数据（论文表3）。 */
    private static final Point[] COMPOSITE = {
            new Point(5, 0.13), new Point(10, 0.18), new Point(20, 0.27),
            new Point(30, 0.04), new Point(40, 0.18), new Point(50, 0.22)
    };
    private static final double DEFAULT_DENSITY_QUARTZ = 1.70;
    private static final double DEFAULT_DENSITY_COMPOSITE = 1.34;
    private static final double FACTOR = 8.64;
    private static final double RISK_THRESHOLD = 86.1;
    private static final double SAFE_THRESHOLD = 81.0;
    private static final String QUARTZ_TYPE = "quartz-sand";
    private static final String COMPOSITE_TYPE = "composite-sand";
    private static final String QUARTZ_LABEL = "70/140目石英砂";
    private static final String COMPOSITE_LABEL = "复合压裂砂(70/140目石英砂:40/70目陶粒=6:4)";

    /** 校验并返回是否为复合压裂砂。 */
    private static boolean compositeType(String proppantType) {
        if (COMPOSITE_TYPE.equals(proppantType)) return true;
        if (QUARTZ_TYPE.equals(proppantType)) return false;
        throw new BusinessException(400, "支撑剂类型仅支持 70/140目石英砂 与 复合压裂砂(6:4)");
    }

    /** 支撑剂类型对应的默认堆积密度（g/cm³）。 */
    public static double defaultDensity(String proppantType) {
        return compositeType(proppantType) ? DEFAULT_DENSITY_COMPOSITE : DEFAULT_DENSITY_QUARTZ;
    }

    /** 按支撑剂类型与闭合压力查表插值，供页面在填写闭合压力后自动回填流速输入框。 */
    public SandCriticalVelocityResult criticalVelocity(SandCriticalVelocityRequest input) {
        boolean composite = compositeType(input.proppantType());
        Point[] table = composite ? COMPOSITE : QUARTZ;
        return new SandCriticalVelocityResult(
                round(interpolate(table, input.closurePressureMpa()), 3),
                boundaryNote(table, input.closurePressureMpa(), composite));
    }

    public SandProductionResult calculate(SandProductionRequest input) {
        boolean composite = compositeType(input.proppantType());
        double density = input.densityGCm3() != null
                ? input.densityGCm3() : defaultDensity(input.proppantType());
        if (!Double.isFinite(density) || density <= 0) throw new BusinessException(400, "支撑剂堆积密度必须大于 0");

        boolean overridden = input.criticalVelocityOverrideMS() != null;
        double vg;
        String note;
        if (overridden) {
            vg = input.criticalVelocityOverrideMS();
            note = null;
        } else {
            Point[] table = composite ? COMPOSITE : QUARTZ;
            vg = interpolate(table, input.closurePressureMpa());
            note = boundaryNote(table, input.closurePressureMpa(), composite);
        }

        double vs = input.massT() / density;                 // t ÷ g/cm³ 单位恰好抵消，m³
        double ss = vs / input.halfLengthM();                // m²
        double qg = round(FACTOR * ss * vg, 2);              // 10⁴m³/d

        Double ratio = null;
        String riskLevel = "未判断";
        String levelKey = "none";
        String riskDescription = "未输入实际日产气量，仅给出临界出砂产量";
        if (input.actualRate1e4M3d() != null) {
            // 比值用展示的临界产量（舍入后）计算，保证页面数据自洽
            ratio = round(input.actualRate1e4M3d() / qg * 100, 2);
            if (ratio > RISK_THRESHOLD) {
                riskLevel = "易出砂"; levelKey = "danger";
                riskDescription = "实际日产气量为临界出砂产量的 " + ratio + "%，超过 86.1% 实验阈值，易出砂";
            } else if (ratio >= SAFE_THRESHOLD) {
                riskLevel = "临界区间"; levelKey = "warn";
                riskDescription = "实际日产气量为临界出砂产量的 " + ratio + "%，处于 81.0%~86.1% 灰色区间（论文实验：86.1% 出砂、81.0% 未出砂），建议加密出砂监测";
            } else {
                riskLevel = "安全"; levelKey = "ok";
                riskDescription = "实际日产气量为临界出砂产量的 " + ratio + "%，低于 81.0% 实验点，未观察到出砂";
            }
        }

        return new SandProductionResult(input.wellName(), input.proppantType(),
                composite ? COMPOSITE_LABEL : QUARTZ_LABEL,
                round(vs, 4), round(ss, 4), round(vg, 3), overridden,
                qg, input.actualRate1e4M3d(), ratio,
                riskLevel, levelKey, riskDescription, note);
    }

    private static double interpolate(Point[] points, double p) {
        if (p <= points[0].closurePressureMpa()) return points[0].criticalVelocityMS();
        Point last = points[points.length - 1];
        if (p >= last.closurePressureMpa()) return last.criticalVelocityMS();
        for (int i = 0; i < points.length - 1; i++) {
            Point left = points[i];
            Point right = points[i + 1];
            if (p >= left.closurePressureMpa() && p <= right.closurePressureMpa()) {
                return left.criticalVelocityMS()
                        + (right.criticalVelocityMS() - left.criticalVelocityMS())
                        * (p - left.closurePressureMpa()) / (right.closurePressureMpa() - left.closurePressureMpa());
            }
        }
        return last.criticalVelocityMS();
    }

    private static String boundaryNote(Point[] points, double p, boolean composite) {
        double min = points[0].closurePressureMpa();
        double max = points[points.length - 1].closurePressureMpa();
        String note;
        if (p < min) note = "闭合压力低于实验范围下限 " + min + " MPa，按 " + min + " MPa 端点取值";
        else if (p > max) note = "闭合压力高于实验范围上限 " + max + " MPa，按 " + max + " MPa 端点取值";
        else note = null;
        if (composite && p >= max) {
            note = (note == null ? "" : note + "；") + "实验在 50 MPa 闭合压力下未观察到出砂，结果仅供参考";
        }
        return note;
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}
