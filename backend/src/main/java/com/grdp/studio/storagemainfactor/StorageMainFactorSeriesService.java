package com.grdp.studio.storagemainfactor;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SeriesDataRequest;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SeriesPoint;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SeriesPointInput;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SeriesRequest;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SeriesResult;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.ToolboxInput;

/**
 * 库级主控因素分析的**时序计算**：把本库导入的时序数据逐点送进原平台工具箱，
 * 得到一整条理论地层压力序列，与实测序列逐点对比。
 *
 * <p>数据集由页面**导入**（存 {@code project_reservoir_main_factor_point}），
 * 运行时不读原平台或团队其他模块的任何表——解耦的是读取路径，数据本身仍是真实的。
 *
 * <p><b>只有累产气在变。</b>整条序列共用表单里的那一组参数，逐点只替换
 * {@code cumulativeGasProduction}；工具箱会话也只开一次（见
 * {@link MaterialBalanceEquationClient#openToolbox}）。
 *
 * <p><b>动态地质储量 G 必须由独立来源确定</b>（容积法 / 档案 {@code ogip}），
 * <b>不得用这条 p–Gp 曲线反算出来的值</b>：反算值会让理论线穿过实测点、差异恒等于 0，
 * 这张图就失去了诊断价值。因此这里只做"累产气不得超过 G"的物理校验，
 * 不提供任何"反算动态储量"的功能——那正是要避免的用法。
 *
 * <p><b>计算不加 {@code @Transactional}</b>：它会发起几十秒的原平台 HTTP 调用，
 * 包在事务里会一直占着数据库连接（连接池上限 10），把不相关的请求一起拖住。
 * 本方法只读库（作用域校验与时序数据），不需要事务。
 */
@Service
public class StorageMainFactorSeriesService {

    /** 导入点数上限：折线图用不到更多，而每个点都要向原平台提交一次。 */
    private static final int MAX_POINTS = 2000;
    /** 实测地层压力按 MPa 解释；超出即认为单位或列选错了。 */
    private static final double MAX_PLAUSIBLE_MEASURED_MPA = 300d;
    /**
     * 连续多少个点拿不到结果，就认定原平台已经不可用，不再逐点等超时。
     *
     * <p>单点最坏情形是 1 次提交 + 3 次读取各等 60 秒。20 个点这么耗下去，
     * 浏览器早就超时了，服务端还在一个一个地打平台——纯粹的资源浪费。
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final StorageCatalogService catalog;
    private final StorageMainFactorPointStorage pointStorage;
    private final MaterialBalanceEquationClient client;

    public StorageMainFactorSeriesService(StorageCatalogService catalog,
            StorageMainFactorPointStorage pointStorage, MaterialBalanceEquationClient client) {
        this.catalog = catalog;
        this.pointStorage = pointStorage;
        this.client = client;
    }

    /** 本库已导入的时序数据，按日期升序。 */
    public List<SeriesPointInput> points(long projectId, long gasReservoirId, long storageId) {
        catalog.requireScope(projectId, gasReservoirId, storageId);
        return pointStorage.load(projectId, gasReservoirId, storageId);
    }

    /**
     * 导入时序数据（整体替换本库的），返回写入的点数。
     *
     * <p>先全量校验再落库：半个文件写进去，图上就是一条断的、还查不出断在哪的线。
     */
    public int importPoints(SeriesDataRequest request) {
        catalog.requireScope(request.projectId(), request.gasReservoirId(), request.storageId());
        List<SeriesPointInput> points = validate(request.points());
        pointStorage.replace(request.projectId(), request.gasReservoirId(), request.storageId(), points);
        return points.size();
    }

    /**
     * 逐点计算理论地层压力，返回完整序列。
     *
     * <p>原平台不可用时**整条序列降级**：理论值与差异为空，实测值照常返回——
     * 那部分来自本库数据，跟原平台在不在线无关。
     *
     * @throws BusinessException 尚未导入时序数据、入参不齐、或累产气达到/超过动态地质储量
     */
    public SeriesResult calculate(SeriesRequest request, Map<String, String> headers) {
        catalog.requireScope(request.projectId(), request.gasReservoirId(), request.storageId());
        List<SeriesPointInput> series = pointStorage.load(
                request.projectId(), request.gasReservoirId(), request.storageId());
        if (series.isEmpty()) {
            throw new BusinessException(400, "本库还没有导入时序数据，请先导入含「日期 / 累产气量 / 地层压力」的数据文件");
        }
        requireGasVolumeCoversSeries(request.inputs(), series);

        List<String> warnings = new ArrayList<>();
        MaterialBalanceEquationClient.ToolboxSession session = client.openToolbox(request.projectId(), headers);
        List<SeriesPoint> calculated = new ArrayList<>(series.size());
        if (session == null) {
            warnings.add("原平台未登录或不可用，理论地层压力暂时算不出来。");
            for (SeriesPointInput point : series) {
                calculated.add(StorageMainFactorCalculator.seriesPoint(point, null));
            }
            return new SeriesResult(List.copyOf(calculated), List.copyOf(warnings));
        }
        // 串行：工具箱是有状态的，并发提交会互相覆盖结果。
        int consecutiveFailures = 0;
        for (int index = 0; index < series.size(); index++) {
            SeriesPointInput point = series.get(index);
            ToolboxInput perPoint = withCumulativeGasProduction(request.inputs(), point.cumulativeGasProduction());
            Double theory = client.calculateWith(session, perPoint, headers);
            consecutiveFailures = theory == null ? consecutiveFailures + 1 : 0;
            calculated.add(StorageMainFactorCalculator.seriesPoint(point, theory));
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                // 平台显然已经不可用，继续逐点等超时毫无意义。剩下的点直接按"算不出来"
                // 补齐：序列长度不变、实测线照常完整，只是理论线在后面断开，
                // 而不是让服务端在浏览器放弃之后还一个个地打平台。
                for (int rest = index + 1; rest < series.size(); rest++) {
                    calculated.add(StorageMainFactorCalculator.seriesPoint(series.get(rest), null));
                }
                warnings.add("原平台连续 " + MAX_CONSECUTIVE_FAILURES + " 个点没有返回结果，剩余点未再尝试。");
                break;
            }
        }
        if (calculated.stream().allMatch(point -> point.theoreticalPressure() == null)) {
            // 会话建起来了却一个点都没算出来：可能是平台每次提交都失败，也可能是
            // 平台回显的入参与我们提交的对不上（见
            // MaterialBalanceEquationClient.echoesSubmittedInput，那道守卫宁可让点为空
            // 也不采信可能属于别的点的结果）。不说明的话，用户只会看到一条空的理论线。
            warnings.add("原平台没有返回任何可用的理论地层压力，请检查平台会话，或稍后重试。");
        }
        return new SeriesResult(List.copyOf(calculated), List.copyOf(warnings));
    }

    /**
     * 物理校验：累产气量不能达到或超过动态地质储量。
     *
     * <p>库级累产气可以到 57.44×10⁸m³，若沿用单井动态储量（如 23.3983×10⁸m³）就会触发。
     * 此时物质平衡方程给出的压力没有物理意义，画出来比不画更糟，所以直接报错。
     */
    private static void requireGasVolumeCoversSeries(ToolboxInput inputs, List<SeriesPointInput> series) {
        if (inputs == null) {
            throw new BusinessException(400, "缺少物质平衡方程工具箱入参");
        }
        double maxGas = series.stream()
                .mapToDouble(SeriesPointInput::cumulativeGasProduction).max().orElse(0d);
        Double gasInPlace = inputs.originalGasInPlace();
        if (gasInPlace == null || !Double.isFinite(gasInPlace) || gasInPlace <= 0) {
            throw new BusinessException(400, "请填写动态地质储量：本库累产气量最大到 " + maxGas
                    + " ×10⁸m³，没有储量算不出理论地层压力");
        }
        if (maxGas >= gasInPlace) {
            throw new BusinessException(400, "最大累产气量 " + maxGas + " ×10⁸m³ 已达到或超过动态地质储量 "
                    + gasInPlace + " ×10⁸m³，物质平衡方程不成立。动态地质储量应由容积法或气藏档案独立确定，"
                    + "不要用这条 p–Gp 曲线反算——反算出来的值会让理论线正好穿过实测点，差异恒等于 0");
        }
    }

    /**
     * 复制一组入参，只换累产气量。
     *
     * <p>这是"只有累产气随时间变化"的字面落点：其余 12 项全部原样带过去，
     * 将来给 {@link ToolboxInput} 加了字段也会在这里集中体现，不会漏掉某个点。
     */
    private static ToolboxInput withCumulativeGasProduction(ToolboxInput base, double cumulativeGasProduction) {
        return new ToolboxInput(base.originalPressure(), base.formationTemperature(), base.originalGasInPlace(),
                cumulativeGasProduction, base.rockCompressionCoefficient(), base.waterCompressionCoefficient(),
                base.waterSaturation(), base.gasReservoirType(), base.reservoirPorosity(), base.rockDensity(),
                base.langmuirPressure(), base.langmuirVolume(), base.gasPvtParam());
    }

    /**
     * 导入前的全量校验。校验后**按日期升序**返回，落库与画图都以这个顺序为准。
     *
     * <p>顺序校验的是"按日期排好之后累产气是否单调不减"：折线图的 x 轴本来就按日期排，
     * 所以文件给成倒序也应该能用；真正要拦的是累产气回退——那说明文件选错了或列错位了。
     */
    private static List<SeriesPointInput> validate(List<SeriesPointInput> points) {
        if (points == null || points.isEmpty()) {
            throw new BusinessException(400, "导入文件里没有数据");
        }
        if (points.size() > MAX_POINTS) {
            throw new BusinessException(400, "一次最多导入 " + MAX_POINTS + " 个点，当前 " + points.size() + " 个");
        }
        if (points.size() < 2) {
            throw new BusinessException(400, "折线图至少需要两个点，当前只有 " + points.size() + " 个");
        }
        Set<LocalDate> dates = new HashSet<>();
        for (SeriesPointInput point : points) {
            if (point == null || point.date() == null) {
                throw new BusinessException(400, "导入数据里有缺少日期的行");
            }
            if (!dates.add(point.date())) {
                throw new BusinessException(400, "日期 " + point.date() + " 出现了两次，同一个日期只能有一个点");
            }
            if (!Double.isFinite(point.cumulativeGasProduction()) || point.cumulativeGasProduction() < 0) {
                throw new BusinessException(400, "日期 " + point.date() + " 的累产气量不是有效的非负数");
            }
            if (!Double.isFinite(point.measuredPressure()) || point.measuredPressure() <= 0
                    || point.measuredPressure() > MAX_PLAUSIBLE_MEASURED_MPA) {
                throw new BusinessException(400, "日期 " + point.date() + " 的地层压力 " + point.measuredPressure()
                        + " 不在 (0, " + (int) MAX_PLAUSIBLE_MEASURED_MPA + "] MPa 内，请核对单位与列");
            }
        }
        List<SeriesPointInput> ordered = new ArrayList<>(points);
        ordered.sort(Comparator.comparing(SeriesPointInput::date));
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).cumulativeGasProduction() < ordered.get(i - 1).cumulativeGasProduction()) {
                throw new BusinessException(400, "累产气量在 " + ordered.get(i - 1).date() + " 与 "
                        + ordered.get(i).date() + " 之间下降了（" + ordered.get(i - 1).cumulativeGasProduction()
                        + " → " + ordered.get(i).cumulativeGasProduction()
                        + "）；累产气不可能回退，请核对导入的是不是同一口井/同一个库的数据");
            }
        }
        return List.copyOf(ordered);
    }
}
