package com.grdp.studio.storagemainfactor;

import java.util.List;
import java.util.Map;

/**
 * 主控因素分析对外传输对象。全部为 record，便于 Jackson 直接序列化。
 *
 * <p>{@link FactorValue} 把「值」和「来源」绑在一起：理论侧与实际侧的来源天然不同
 * （理论地层压力来自原平台工具箱，实际值来自数据库），所以两侧各自携带 source，
 * 而不是共用一个来源表。
 */
public final class StorageMainFactorDtos {

    private StorageMainFactorDtos() {}

    /** 单个因素一侧的取值。{@code source} 取 {@code AUTO} / {@code MANUAL} / {@code MISSING}。 */
    public record FactorValue(Double value, String source, String note) {}

    /** 一个因素的一行：理论值、实际值、差异、差异方向与百分比偏差。 */
    public record FactorRow(
            String key,
            String label,
            String unit,
            FactorValue theoretical,
            FactorValue actual,
            Double difference,
            String direction,
            Double deviationPercent) {}

    /**
     * 原平台 {@code gasPvtParam}，字段名与取值都与原平台一致。
     *
     * <p>三个摩尔分数是**小数**（0.0462 = 4.62%），不是百分数——依据是原平台自己存下的
     * 调用记录（{@code toolbox_result}）里这个字段就是 {@code "h2SMoleFraction":0.0462}。
     */
    public record GasPvtParam(
            int gasType,
            double specificGravity,
            int modificationMethod,
            double h2SMoleFraction,
            double co2MoleFraction,
            double n2MoleFraction,
            int deviationFactorMethod,
            int viscosityMethod) {}

    /**
     * 原平台「物质平衡方程 → 计算地层压力」的入参。
     * 数值一律是**原平台口径**：Pa / K / 10⁸m³ / 小数 / 1/Pa。
     * 后四项仅页岩气藏（gasReservoirType=2）使用，允许为 null（按 0 发送）。
     */
    public record ToolboxInput(
            Double originalPressure,
            Double formationTemperature,
            Double originalGasInPlace,
            Double cumulativeGasProduction,
            Double rockCompressionCoefficient,
            Double waterCompressionCoefficient,
            Double waterSaturation,
            Integer gasReservoirType,
            Double reservoirPorosity,
            Double rockDensity,
            Double langmuirPressure,
            Double langmuirVolume,
            GasPvtParam gasPvtParam) {}

    /** 页面初次加载的预填结果：四因素行 + 工具箱入参 + 入参来源 + 缺失说明。 */
    public record Context(
            List<FactorRow> factors,
            ToolboxInput inputs,
            Map<String, String> inputSources,
            List<String> warnings) {}

    /**
     * 计算请求：四因素的理论/实际值由前端回传（用户可能改过），入参同样回传。
     *
     * <p>两侧都传 {@link FactorValue} 而不是裸数值：页面会把 context 读到的自动值原样回传，
     * 如果只传数字，后端就无法区分"这是数据库读来的"还是"用户手输的"，
     * 点一次计算之后所有来源都会退化成"手动填写"（spec 要求每个值都标明来源）。
     *
     * <p>{@code volumeFactor} 是天然气体积系数 Bg。库级没有 Bg 字段（井级为
     * {@code project_well_pvt_gas_result.volume_factor}），spec 把它定为页面上的手输项；
     * ② 动用孔隙体积与 ④ 气体饱和度都要用它，所以必须随请求带上来。
     */
    public record CalculateRequest(
            long projectId,
            long gasReservoirId,
            long storageId,
            Double volumeFactor,
            Map<String, FactorValue> theoretical,
            Map<String, FactorValue> actual,
            ToolboxInput inputs) {}

    /**
     * 计算结果。{@code formationPressure} 为 null 表示原平台不可用或未登录，
     * 此时前端应把理论地层压力切换为手输，{@code formationPressureSource} 会说明原因。
     */
    public record CalculateResult(
            Double formationPressure,
            String formationPressureSource,
            List<FactorRow> factors,
            List<String> warnings) {}
}
