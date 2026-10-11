package com.grdp.studio.storagemainfactor;

import java.time.LocalDate;
import java.util.List;

/**
 * 主控因素分析对外传输对象。全部为 record，便于 Jackson 直接序列化。
 *
 * <p>口径：{@link ToolboxInput} 用应用口径（Pa / K / 10⁸m³ / 小数 / 1/Pa）；
 * 时序数据与结果压力用**界面口径**（10⁸m³ / MPa）。序列里的每个点自带差异与百分比偏差，
 * 而不是共用一个全局值——折线图要画的正是"偏离随时间怎么变"。
 */
public final class StorageMainFactorDtos {

    private StorageMainFactorDtos() {}

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
     * 时序数据的一个点：某个测压日期的累产气量与实测地层压力。
     *
     * <p>单位是**界面口径**——累产气 10⁸m³、压力 MPa，与
     * {@link ToolboxInput#cumulativeGasProduction()}（10⁸m³）以及结果表的两个压力列（MPa）一致；
     * 导入文件也是这两个单位，因此从文件到库到画图**全程零换算**，少一类单位 bug。
     *
     * <p>刻意不含累产水 / 累产油：原平台工具箱的定容气藏方程不吃这两个量
     * （它只吃束缚水饱和度、岩石与水压缩系数），带着它们会让人误以为参与计算。
     */
    public record SeriesPointInput(LocalDate date, double cumulativeGasProduction, double measuredPressure) {}

    /**
     * 序列里的一个点：该日期的实测值、算出来的理论值，以及**逐点**的差异与百分比偏差。
     *
     * <p>差异与偏差刻意挂在每个点上，而不是只给一个总数：折线图要画的是
     * "偏离随时间怎么变"（本库的实际形态是单调放大），一个全局差异画不出这条线。
     * 任一侧缺失时两者都是 {@code null}。
     */
    public record SeriesPoint(
            LocalDate date,
            double cumulativeGasProduction,
            double measuredPressure,
            Double theoreticalPressure,
            Double difference,
            Double deviationPercent) {}

    /** 一条完整的地层压力序列：点按日期升序，外加需要让用户知道的降级说明。 */
    public record SeriesResult(List<SeriesPoint> points, List<String> warnings) {}

    /** 序列计算请求：入参是本次表单的参数，累产气逐点来自本库已导入的时序数据。 */
    public record SeriesRequest(long projectId, long gasReservoirId, long storageId, ToolboxInput inputs) {}

    /** 导入时序数据的请求：点一律是**界面口径**（10⁸m³ / MPa），与导入文件一致。 */
    public record SeriesDataRequest(long projectId, long gasReservoirId, long storageId, List<SeriesPointInput> points) {}

    /**
     * 读取已保存的一份：入参 + 整条序列，其中每个点的差异与百分比偏差都是
     * **后端读取时重算的**。
     *
     * <p>差异与偏差不入库（它们由两个压力推导出来）。若读取时不重算，重新打开页面就会看到
     * "理论值 40.53 / 实际值 41.48 / 差异 —"，看起来像坏了。
     */
    public record SavedAnalysis(ToolboxInput inputs, List<SeriesPoint> points) {}

    /**
     * 序列里一个点的**落库形态**：只有原始值，没有派生量。
     *
     * <p>差异与偏差不入库（它们由两侧压力推导），读取时用
     * {@link StorageMainFactorCalculator#seriesPoint} 重算，保证序列与单点共用同一个定义。
     */
    public record StoredPoint(
            LocalDate date,
            double cumulativeGasProduction,
            double measuredPressure,
            Double theoreticalPressure) {}

    /**
     * 已保存的一份分析：工具箱入参 + 整条地层压力序列。
     *
     * <p>只存"本库一份"，所以没有记录名与记录编号——那是微观损耗那种
     * "同一口井多个比选方案"才需要的结构。保存即更新同一行。
     *
     * <p>页岩气藏专属入参（孔隙度 / 岩石密度 / Langmuir）**不入库**：
     * 它们不在界面上，由原平台模板兜底，存下来只会造成"库里有一份、
     * 实际算的是另一份"的错觉。
     */
    public record SavedMainFactor(ToolboxInput inputs, List<StoredPoint> points) {}

    /**
     * 保存请求：工具箱入参 + 当前算出的整条序列。
     *
     * <p>序列由前端回传而不是后端重算：用户点"保存"时要存下的正是**他看到的那个结果**，
     * 重算一次可能因为平台抖动而存进另一组值。
     */
    public record SaveRequest(
            long projectId,
            long gasReservoirId,
            long storageId,
            ToolboxInput inputs,
            List<StoredPoint> points) {}
}
