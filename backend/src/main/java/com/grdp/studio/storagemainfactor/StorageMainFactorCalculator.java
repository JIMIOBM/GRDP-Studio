package com.grdp.studio.storagemainfactor;

import com.grdp.studio.common.BusinessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.FactorValue;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.GasPvtParam;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.ToolboxInput;

/**
 * 地层压力一项的计算与工具箱载荷组装。全部是纯函数，不碰数据库也不碰 HTTP。
 *
 * <p>口径：{@link ToolboxInput} 用应用口径（Pa / K / 10⁸m³ / 小数 / 1/Pa），
 * {@link #toolboxPayload} 是**唯一**的换算边界——原平台 {@code calc} 接口收的是
 * **界面单位**（MPa / ℃ / 10⁸m³ / % / MPa⁻¹，依据它自己返回的 {@code fields.unit_label}
 * 与 {@code inputRange}，例如 {@code maxOriginalPressure=200}）。
 * 在这条边界之外再做一次换算会把结果缩放错 10⁶ 或 10⁸ 倍。
 *
 * <p>缺值一律用 {@code null} 表达，不用 0 兜底：0 是一个合法数值，
 * 会让差异显示出一个看似正常的错值。
 */
public final class StorageMainFactorCalculator {

    private StorageMainFactorCalculator() {}

    /** 值来自数据库或原平台自动读取。 */
    public static final String AUTO = "AUTO";
    /** 值由用户在页面上手动填写。 */
    public static final String MANUAL = "MANUAL";
    /** 没有值，等待用户填写。 */
    public static final String MISSING = "MISSING";

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
        // 口径以平台自己返回的 fields.unit_label 与 inputRange 为准（见类注释）：
        // calc 收的是**界面单位** MPa / ℃ / 10⁸m³ / % / MPa⁻¹，
        // 不是算法内部的 Pa / K / m³ / 小数。发错会被 inputRange 直接挡回，
        // 例如 maxOriginalPressure=200，而 50000000 Pa 远超它。
        payload.put("originalPressure",
                num(require(StorageMainFactorUnits.paToMpa(input.originalPressure()), "原始地层压力")));
        payload.put("formationTemperature",
                num(require(StorageMainFactorUnits.kelvinToCelsius(input.formationTemperature()), "地层温度")));
        // 体积本来就是 10⁸m³，不再换算
        payload.put("originalGasInPlace", num(require(input.originalGasInPlace(), "动态地质储量")));
        payload.put("cumulativeGasProduction", num(require(input.cumulativeGasProduction(), "累产气量")));
        payload.put("rockCompressionCoefficient",
                num(require(StorageMainFactorUnits.perPaToPerMpa(input.rockCompressionCoefficient()), "岩石压缩系数")));
        payload.put("waterCompressionCoefficient",
                num(require(StorageMainFactorUnits.perPaToPerMpa(input.waterCompressionCoefficient()), "地层水压缩系数")));
        payload.put("waterSaturation",
                num(require(StorageMainFactorUnits.fractionToPercent(input.waterSaturation()), "束缚水饱和度")));
        if (input.gasReservoirType() == null) {
            throw new BusinessException(400, "缺少物质平衡方程工具箱必填入参：气藏类型");
        }
        payload.put("gasReservoirType", input.gasReservoirType());
        // 这四项只有页岩气藏会用。**没值时不要写 0**：
        // 平台的校验是"值 <= 下限即拒绝"，0 正是那个非法值；
        // 留给平台模板自己的默认值。
        putWhenMeaningful(payload, "reservoirPorosity", input.reservoirPorosity());
        putWhenMeaningful(payload, "rockDensity", input.rockDensity());
        putWhenMeaningful(payload, "langmuirPressure", input.langmuirPressure());
        putWhenMeaningful(payload, "langmuirVolume", input.langmuirVolume());
        Map<String, Object> gasPvtParam = new LinkedHashMap<>();
        gasPvtParam.put("gasType", pvt.gasType());
        gasPvtParam.put("specificGravity", num(pvt.specificGravity()));
        gasPvtParam.put("modificationMethod", pvt.modificationMethod());
        // 摩尔分数是**百分数**（inputRange maxH2SMoleFraction=100），库里存的是小数
        gasPvtParam.put("h2SMoleFraction",
                num(require(StorageMainFactorUnits.fractionToPercent(pvt.h2SMoleFraction()), "H₂S摩尔百分含量")));
        gasPvtParam.put("co2MoleFraction",
                num(require(StorageMainFactorUnits.fractionToPercent(pvt.co2MoleFraction()), "CO₂摩尔百分含量")));
        gasPvtParam.put("n2MoleFraction",
                num(require(StorageMainFactorUnits.fractionToPercent(pvt.n2MoleFraction()), "N₂摩尔百分含量")));
        gasPvtParam.put("deviationFactorMethod", pvt.deviationFactorMethod());
        gasPvtParam.put("viscosityMethod", pvt.viscosityMethod());
        // 嵌套 PVT 也是界面单位：温度 ℃、压力 MPa
        gasPvtParam.put("temperature",
                num(require(StorageMainFactorUnits.kelvinToCelsius(input.formationTemperature()), "地层温度")));
        gasPvtParam.put("originalPressure",
                num(require(StorageMainFactorUnits.paToMpa(input.originalPressure()), "原始地层压力")));
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

    /** 只在真有值时写入：0/缺值留给平台模板自己的默认值，避免把合法值覆盖成非法的 0。 */
    private static void putWhenMeaningful(Map<String, Object> payload, String key, Double value) {
        if (value != null && value != 0d && Double.isFinite(value)) {
            payload.put(key, num(value));
        }
    }
}
