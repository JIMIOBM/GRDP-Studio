package com.grdp.studio.storagemainfactor;

import com.grdp.studio.common.BusinessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.FactorRow;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.FactorValue;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.GasPvtParam;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.ToolboxInput;

/**
 * 四因素计算、差异与工具箱载荷组装。全部是纯函数，不碰数据库也不碰 HTTP。
 *
 * <p>数值口径：入参与出参都是**原平台口径**（Pa / K / 10⁸m³ / 小数 / 1/Pa）。
 * 数据库口径与原平台口径之间的换算由 {@link StorageMainFactorUnits} 在读取数据的那一步完成，
 * 这里再做一次就会把结果缩放错 10⁶ 或 10⁸ 倍。
 *
 * <p>缺值一律用 {@code null} 表达，不用 0 兜底：0 是一个合法数值，
 * 会让差异列显示出一个看似正常的错值。
 */
public final class StorageMainFactorCalculator {

    private StorageMainFactorCalculator() {}

    /** 值来自数据库或原平台自动读取。 */
    public static final String AUTO = "AUTO";
    /** 值由用户在页面上手动填写。 */
    public static final String MANUAL = "MANUAL";
    /** 没有值，等待用户填写。 */
    public static final String MISSING = "MISSING";

    /** 四个因素的固定顺序、显示名与单位。顺序即页面表格的行序。 */
    private static final List<String> KEYS = List.of(
            "formationPressure", "poreVolume", "gas", "gasSaturation");
    private static final Map<String, String> LABELS = Map.of(
            "formationPressure", "地层压力",
            "poreVolume", "动用孔隙体积",
            "gas", "天然气",
            "gasSaturation", "气体饱和度");
    private static final Map<String, String> UNITS = Map.of(
            "formationPressure", "MPa",
            "poreVolume", "10⁸m³",
            "gas", "10⁸m³",
            "gasSaturation", "小数");

    public static FactorValue auto(Double value, String note) {
        return value == null ? missing(note) : new FactorValue(value, AUTO, note);
    }

    public static FactorValue manual(Double value, String note) {
        return value == null ? missing(note) : new FactorValue(value, MANUAL, note);
    }

    public static FactorValue missing(String note) {
        return new FactorValue(null, MISSING, note);
    }

    /** 差异 = 实际 − 理论。任一侧缺值则无差异可言，返回 {@code null}。 */
    public static Double difference(Double actual, Double theoretical) {
        if (actual == null || theoretical == null) {
            return null;
        }
        return actual - theoretical;
    }

    /** 差异方向。{@code null} 表示无法比较（缺值）。 */
    public static String direction(Double difference) {
        if (difference == null || !Double.isFinite(difference)) {
            return null;
        }
        if (difference > 0) {
            return "POSITIVE";
        }
        if (difference < 0) {
            return "NEGATIVE";
        }
        return "ZERO";
    }

    /**
     * 百分比偏差 = (实际 − 理论) / 理论 × 100。
     * 理论值为 0 或缺失时返回 {@code null}（不做除零，也不显示无穷大）。
     */
    public static Double deviationPercent(Double actual, Double theoretical) {
        if (actual == null || theoretical == null || theoretical == 0d) {
            return null;
        }
        double percent = (actual - theoretical) / theoretical * 100d;
        return Double.isFinite(percent) ? percent : null;
    }

    /**
     * 全库孔隙体积 Vp = G × Bg / (1 − Swi)。
     * Swi ≥ 1 时 1 − Swi ≤ 0，会得到无穷大或负的孔隙体积，两者都没有物理意义，返回 {@code null}。
     */
    public static Double poreVolume(Double g, Double bg, Double swiFraction) {
        if (g == null || bg == null || swiFraction == null) {
            return null;
        }
        double gasSaturation = 1d - swiFraction;
        if (gasSaturation <= 0d) {
            return null;
        }
        double vp = g * bg / gasSaturation;
        return Double.isFinite(vp) ? vp : null;
    }

    /**
     * 气体饱和度 Sg = (G − Gp) × Bg / Vp，即剩余天然气地下体积占孔隙体积的比例。
     * Vp 为 0 或缺失时返回 {@code null}。
     */
    public static Double gasSaturation(Double g, Double gp, Double bg, Double vp) {
        if (g == null || gp == null || bg == null || vp == null || vp == 0d) {
            return null;
        }
        double sg = (g - gp) * bg / vp;
        return Double.isFinite(sg) ? sg : null;
    }

    /**
     * 组装四因素行。固定顺序，缺值的因素也会出现在结果里（值为 null、来源 MISSING），
     * 这样页面可以把它渲染成输入框而不是整行消失。
     */
    public static List<FactorRow> rows(Map<String, FactorValue> theoretical, Map<String, FactorValue> actual) {
        Map<String, FactorValue> theo = theoretical == null ? Map.of() : theoretical;
        Map<String, FactorValue> act = actual == null ? Map.of() : actual;
        List<FactorRow> rows = new ArrayList<>(KEYS.size());
        for (String key : KEYS) {
            FactorValue t = theo.getOrDefault(key, missing(null));
            FactorValue a = act.getOrDefault(key, missing(null));
            Double diff = difference(a.value(), t.value());
            rows.add(new FactorRow(key, LABELS.get(key), UNITS.get(key), t, a,
                    diff, direction(diff), deviationPercent(a.value(), t.value())));
        }
        return List.copyOf(rows);
    }

    /**
     * 组装原平台工具箱入参——这里是**应用口径 → 原平台口径**的唯一转换点。
     *
     * <p>口径依据是原平台自己存下的调用记录（{@code toolbox_result} 里
     * {@code MaterialBalanceEquationAppl_FormationPressure} 的 inputs）：
     * <pre>
     *   "originalPressure":56340000      → Pa（不缩放）
     *   "formationTemperature":293       → K（不缩放）
     *   "originalGasInPlace":347222200   → **m³**，不是 10⁸m³
     *   "cumulativeGasProduction":147222200 → **m³**
     *   "waterSaturation":0.1804         → 小数
     *   "h2SMoleFraction":0.1462         → **小数**，不是百分数
     *   "rockCompressionCoefficient":2.26e-9 → 1/Pa
     * </pre>
     * 自己的库容/孔隙体积等派生量一律用应用口径（10⁸m³、小数），只有这里跨到平台口径。
     *
     * <p>必填项缺失直接抛错：把缺失的地质储量当成 0 发给原平台，
     * 它会返回一个看起来正常但完全错误的地层压力。
     */
    public static Map<String, Object> toolboxPayload(ToolboxInput input) {
        if (input == null) {
            throw new BusinessException(400, "缺少物质平衡方程工具箱入参");
        }
        GasPvtParam pvt = input.gasPvtParam();
        if (pvt == null) {
            throw new BusinessException(400, "缺少物质平衡方程工具箱必填入参：气体 PVT 参数");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("originalPressure", num(require(input.originalPressure(), "原始地层压力")));
        payload.put("formationTemperature", num(require(input.formationTemperature(), "地层温度")));
        payload.put("originalGasInPlace",
                num(require(StorageMainFactorUnits.hundredMillionToCubicMeter(input.originalGasInPlace()), "动态地质储量")));
        payload.put("cumulativeGasProduction",
                num(require(StorageMainFactorUnits.hundredMillionToCubicMeter(input.cumulativeGasProduction()), "累产气量")));
        payload.put("rockCompressionCoefficient", num(require(input.rockCompressionCoefficient(), "岩石压缩系数")));
        payload.put("waterCompressionCoefficient", num(require(input.waterCompressionCoefficient(), "地层水压缩系数")));
        payload.put("waterSaturation", num(require(input.waterSaturation(), "束缚水饱和度")));
        if (input.gasReservoirType() == null) {
            throw new BusinessException(400, "缺少物质平衡方程工具箱必填入参：气藏类型");
        }
        payload.put("gasReservoirType", input.gasReservoirType());
        // 这四项只有页岩气藏会用。**没值时不要写 0**：
        // 写了就会在合并平台模板时把它自己的默认值（孔隙度 0.0514、岩石密度 2700、
        // langmuir 4000000/3000）覆盖成 0，而原平台的校验是 `值 <= 下限 即拒绝`，
        // 0 正是那个非法值。留给模板提供。
        putWhenMeaningful(payload, "reservoirPorosity", input.reservoirPorosity());
        putWhenMeaningful(payload, "rockDensity", input.rockDensity());
        putWhenMeaningful(payload, "langmuirPressure", input.langmuirPressure());
        putWhenMeaningful(payload, "langmuirVolume", input.langmuirVolume());
        Map<String, Object> gasPvtParam = new LinkedHashMap<>();
        gasPvtParam.put("gasType", pvt.gasType());
        gasPvtParam.put("specificGravity", num(pvt.specificGravity()));
        gasPvtParam.put("modificationMethod", pvt.modificationMethod());
        gasPvtParam.put("h2SMoleFraction", num(pvt.h2SMoleFraction()));
        gasPvtParam.put("co2MoleFraction", num(pvt.co2MoleFraction()));
        gasPvtParam.put("n2MoleFraction", num(pvt.n2MoleFraction()));
        gasPvtParam.put("deviationFactorMethod", pvt.deviationFactorMethod());
        gasPvtParam.put("viscosityMethod", pvt.viscosityMethod());
        // 嵌套 PVT 也要带上温度与原始地层压力：原平台会校验它们，缺省成 0 会被判为
        //   工具箱计算出错:invoke algorithm error:参数校验失败:
        //   原始地层压力 取值范围 (0, 500000000]
        // 原始地层压力与温度在两层是同一个物理量，直接取外层值，不是编造。
        gasPvtParam.put("temperature", num(input.formationTemperature()));
        gasPvtParam.put("originalPressure", num(input.originalPressure()));
        payload.put("gasPvtParam", gasPvtParam);
        return payload;
    }

    /**
     * 数字的书写形式要与原平台一致：**整数写整数、非整数写十进制，不要科学计数法**。
     *
     * <p>Java 的 {@code Double.toString(5.0E7)} 是 {@code "5.0E7"}，Jackson 会照写成
     * {@code 5.0E7}；而原平台自己存的入参写的是 {@code 56340000}。原平台的参数校验
     * 拿到 {@code 5.0E7} 后判为不合法，报的就是
     * {@code 参数校验失败: 原始地层压力 取值范围 (0, 500000000]}——
     * 数值明明在区间内，问题出在写法上。
     *
     * <p>整数用 {@link Long}（写出 {@code 50000000}），其余用 {@link BigDecimal}
     * 的十进制写法（写出 {@code 0.26158040988077613}），整数值的 0 也会写成 {@code 0}。
     */
    private static Object num(double value) {
        if (!Double.isFinite(value)) {
            throw new BusinessException(400, "物质平衡方程工具箱入参出现非法数值：" + value);
        }
        if (value == Math.rint(value) && Math.abs(value) <= 9.007199254740991E15) {
            return (long) value;
        }
        return new BigDecimal(Double.toString(value));
    }

    private static Double require(Double value, String label) {
        if (value == null || !Double.isFinite(value)) {
            throw new BusinessException(400, "缺少物质平衡方程工具箱必填入参：" + label);
        }
        return value;
    }

    private static double zeroIfNull(Double value) {
        return value == null ? 0d : value;
    }

    /** 只在真有值时写入：0/缺值留给平台模板自己的默认值，避免把合法值覆盖成非法的 0。 */
    private static void putWhenMeaningful(Map<String, Object> payload, String key, Double value) {
        if (value != null && value != 0d && Double.isFinite(value)) {
            payload.put(key, num(value));
        }
    }
}
