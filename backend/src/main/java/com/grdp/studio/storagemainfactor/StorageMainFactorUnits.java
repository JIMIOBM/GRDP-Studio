package com.grdp.studio.storagemainfactor;

import com.grdp.studio.common.BusinessException;

import java.util.Map;

/**
 * 主控因素分析的**唯一**单位与枚举换算入口。
 *
 * <p>本库同一个物理量在不同表里存了不同单位（均为实测值）：
 * <ul>
 *   <li>原始地层压力：{@code dynamic_original_gas_in_place_by_mb_input.original_pressure} 是 Pa（5.0e7），
 *       而 {@code project_well_water_invasion_input.original_formation_pressure} 是 MPa（50）。</li>
 *   <li>地层温度：前者是 K（353.15），后者是 ℃（80）。</li>
 *   <li>束缚水饱和度：前者是小数（0.2616），后者是 %（26.158）。</li>
 *   <li>压缩系数：前者是 1/Pa（1e-10），后者是 1/MPa（1e-4）。</li>
 *   <li>地质储量：{@code dynamic_original_gas_in_place_output.original_gas_volume} 是 m³（2.34e9），
 *       水侵表是 10⁸m³（13.58）。</li>
 * </ul>
 * 两表同名列**不能**直接互相赋值，必须经过这里。原平台工具箱的入参口径是
 * Pa / K / 10⁸m³ / 小数 / 1/Pa，本类负责在数据库口径与原平台口径之间转换。
 *
 * <p>全部方法可空安全：{@code null} 进 {@code null} 出（避免把"没有值"当成 0 参与计算）。
 */
public final class StorageMainFactorUnits {

    private StorageMainFactorUnits() {}

    /** Pa → MPa。 */
    private static final double PA_PER_MPA = 1_000_000d;

    /** m³ → 10⁸m³。 */
    private static final double CUBIC_METER_PER_HUNDRED_MILLION = 100_000_000d;

    /** 1/Pa → 1/MPa：量纲取倒数，系数同为 1e6。 */
    private static final double PER_PA_PER_PER_MPA = 1_000_000d;

    private static final double KELVIN_OFFSET = 273.15d;

    /** 原平台 {@code gasPvtParam.gasType} 取值（来源：原平台 assets/toolsEnum-*.js）。 */
    private static final Map<String, Integer> GAS_TYPES = Map.of(
            "干气", 0,
            "湿气", 1,
            "凝析气", 2);

    /** 原平台 {@code gasPvtParam.modificationMethod} 取值。 */
    private static final Map<String, Integer> MODIFICATION_METHODS = Map.of(
            "Wichert-Aziz 修正方法", 0,
            "Carr-Kobayashi-Burrous 修正方法", 1);

    /** 原平台 {@code gasPvtParam.deviationFactorMethod} 取值。 */
    private static final Map<String, Integer> DEVIATION_FACTOR_METHODS = Map.of(
            "Dranchuk-Abu-Kassem 方法", 0,
            "Dranchuk-Purvis-Robinson 方法", 1,
            "Hall-Yarborough 方法", 2);

    public static Double paToMpa(Double pa) {
        return scale(pa, 1d / PA_PER_MPA);
    }

    public static Double mpaToPa(Double mpa) {
        return scale(mpa, PA_PER_MPA);
    }

    public static Double kelvinToCelsius(Double kelvin) {
        return offset(kelvin, -KELVIN_OFFSET);
    }

    public static Double celsiusToKelvin(Double celsius) {
        return offset(celsius, KELVIN_OFFSET);
    }

    public static Double cubicMeterToHundredMillion(Double cubicMeter) {
        return scale(cubicMeter, 1d / CUBIC_METER_PER_HUNDRED_MILLION);
    }

    public static Double hundredMillionToCubicMeter(Double hundredMillion) {
        return scale(hundredMillion, CUBIC_METER_PER_HUNDRED_MILLION);
    }

    public static Double fractionToPercent(Double fraction) {
        return scale(fraction, 100d);
    }

    public static Double percentToFraction(Double percent) {
        return scale(percent, 0.01d);
    }

    public static Double perPaToPerMpa(Double perPa) {
        return scale(perPa, PER_PA_PER_PER_MPA);
    }

    public static Double perMpaToPerPa(Double perMpa) {
        return scale(perMpa, 1d / PER_PA_PER_PER_MPA);
    }

    public static int gasTypeCode(String chinese) {
        return code(GAS_TYPES, chinese, "天然气类型");
    }

    public static int modificationMethodCode(String chinese) {
        return code(MODIFICATION_METHODS, chinese, "非烃气体修正方法");
    }

    public static int deviationFactorMethodCode(String chinese) {
        return code(DEVIATION_FACTOR_METHODS, chinese, "天然气偏差系数计算方法");
    }

    /** 非有限值一律视为"没有值"返回 {@code null}，不让 Infinity/NaN 流进后续计算。 */
    private static Double scale(Double value, double factor) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return value * factor;
    }

    private static Double offset(Double value, double delta) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return value + delta;
    }

    /**
     * 未知取值直接报错而不是静默返回 0：静默兜底会把"未知气型"当成"干气"，
     * 让原平台算出一个看似正常但口径错误的结果，比报错危险得多。
     */
    private static int code(Map<String, Integer> table, String value, String label) {
        if (value == null) {
            throw new BusinessException(400, "未识别的" + label + "：值为空");
        }
        Integer code = table.get(value.trim());
        if (code == null) {
            throw new BusinessException(400, "未识别的" + label + "：" + value);
        }
        return code;
    }
}
