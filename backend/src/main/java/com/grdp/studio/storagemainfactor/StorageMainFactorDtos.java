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

    /**
     * 页面初次加载的预填结果：实测地层压力 + 工具箱入参 + 入参来源 + 缺失说明。
     *
     * <p>改造后本功能只比较**地层压力**一个因素，所以这里不再有四因素行；
     * 实测静压单独给出，并保留它的出处说明（例如"实测静压：X-1"）。
     */
    public record Context(
            FactorValue measuredPressure,
            ToolboxInput inputs,
            Map<String, String> inputSources,
            List<String> warnings) {}

    /**
     * 读取已保存的一份：入参 + 两侧压力 + **后端重新算出的**差异与百分比偏差。
     *
     * <p>差异不入库（它是两个压力推导出来的）。若读取时不重算，重新打开页面就会看到
     * "理论值 19.65 / 实际值 13.56 / 差异 —"，看起来像坏了。
     */
    public record SavedAnalysis(
            ToolboxInput inputs,
            Double theoreticalPressure,
            Double actualPressure,
            Double difference,
            Double deviationPercent) {}

    /**
     * 计算请求：只输入工具箱入参。
     *
     * <p>理论值由原平台算、实际值由后端读库，两者都不需要前端回传，
     * 所以这里既没有 Bg，也没有"理论/实际值回传"的映射。
     */
    public record CalculateRequest(
            long projectId,
            long gasReservoirId,
            long storageId,
            ToolboxInput inputs) {}

    /**
     * 已保存的一份分析：工具箱入参 + 两侧地层压力。
     *
     * <p>只存"本库一份"，所以没有记录名与记录编号——那是微观损耗那种
     * "同一口井多个比选方案"才需要的结构。保存即更新同一行。
     *
     * <p>页岩气藏专属入参（孔隙度 / 岩石密度 / Langmuir）**不入库**：
     * 它们不在界面上，由原平台模板兜底，存下来只会造成"库里有一份、
     * 实际算的是另一份"的错觉。
     */
    public record SavedMainFactor(ToolboxInput inputs, Double theoreticalPressure, Double actualPressure) {}

    /**
     * 保存请求：工具箱入参 + 当前算出的两侧压力。
     *
     * <p>压力由前端回传而不是后端重算：用户点"保存"时要存下的正是**他看到的那个结果**，
     * 重算一次可能因为平台抖动而存进另一个值。
     */
    public record SaveRequest(
            long projectId,
            long gasReservoirId,
            long storageId,
            ToolboxInput inputs,
            Double theoreticalPressure,
            Double actualPressure) {}

    /**
     * 计算结果：理论值（原平台工具箱）、实际值（库内实测静压）、差异与百分比偏差。
     *
     * <p>{@code formationPressure} 为 null 表示原平台不可用或未登录，此时
     * {@code formationPressureSource} 为 {@code MISSING}，前端把理论值显示为空。
     * 三个派生量（差异、百分比偏差）只要有一侧缺失就为 null，绝不拿 0 顶替。
     */
    public record CalculateResult(
            Double formationPressure,
            String formationPressureSource,
            FactorValue measuredPressure,
            Double difference,
            Double deviationPercent,
            List<String> warnings) {}
}
