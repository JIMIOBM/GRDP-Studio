package com.grdp.studio.storagemainfactor;

import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.AUTO;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.MISSING;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.auto;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.deviationPercent;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.difference;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.missing;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.*;

/**
 * 库级主控因素分析：读库预填工具箱入参并取实测静压，调原平台算理论地层压力，两者相减得差异。
 *
 * <p>数据来自原平台的物质平衡来源表。注意这三张表<b>本身没有 project / well 列</b>，
 * 靠外键串起来：{@code dynamic_original_gas_in_place} 持 project/well/时间戳，
 * {@code ..._by_mb_input} 持入参，{@code ..._by_mb_input_item} 持逐日期 p 与累产气量，
 * {@code ..._output} 持动态地质储量。
 *
 * <p>口径：数据库单位（Pa / K / 小数 / 1/Pa）由 {@link StorageMainFactorCalculator#toolboxPayload}
 * 在组装原平台载荷时换成平台的提交口径，本类只负责编排，不做单位换算。
 *
 * <p>计算结果即算即看；只有用户点「保存」时才写入
 * {@code project_reservoir_main_factor}（一个库一份，UPSERT）。
 */
@Service
public class StorageMainFactorService {

    private final JdbcTemplate jdbc;
    private final StorageCatalogService catalog;
    private final MaterialBalanceEquationClient client;
    private final StorageMainFactorSavedStorage savedStorage;

    public StorageMainFactorService(JdbcTemplate jdbc, StorageCatalogService catalog,
            MaterialBalanceEquationClient client, StorageMainFactorSavedStorage savedStorage) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.client = client;
        this.savedStorage = savedStorage;
    }

    /**
     * 保存本库的一份分析（存在则更新）。
     *
     * <p>只存"本库一份"，所以没有记录名、没有记录列表、也不接左侧树——
     * 那是微观损耗那种"同一口井多个比选方案"才需要的结构。
     */
    public void save(SaveRequest request) {
        catalog.requireScope(jdbc, request.projectId(), request.gasReservoirId(), request.storageId());
        savedStorage.save(request.projectId(), request.gasReservoirId(), request.storageId(),
                new SavedMainFactor(request.inputs(), request.theoreticalPressure(), request.actualPressure()));
    }

    /**
     * 读取本库已保存的一份；从未保存过返回 {@link Optional#empty()}。
     *
     * <p>差异与百分比偏差在这里**重新算**：它们不入库，读取时不重算，
     * 页面上就会出现"两侧都有值、差异却是 —"的残缺结果。
     */
    public Optional<SavedAnalysis> loadSaved(long projectId, long gasReservoirId, long storageId) {
        catalog.requireScope(jdbc, projectId, gasReservoirId, storageId);
        return savedStorage.load(projectId, gasReservoirId, storageId).map(saved -> new SavedAnalysis(
                saved.inputs(), saved.theoreticalPressure(), saved.actualPressure(),
                difference(saved.actualPressure(), saved.theoreticalPressure()),
                deviationPercent(saved.actualPressure(), saved.theoreticalPressure())));
    }

    /** 一口井的物质平衡输入，单位仍是**数据库口径**。 */
    private record Source(
            long inputId,
            String wellName,
            Double originalPressure,
            Double temperature,
            Double rockCompressionCoefficient,
            Double waterCompressionCoefficient,
            Double waterSaturation,
            Integer gasReservoirType,
            String gasType,
            Double specificGravity,
            Double hydrogenSulfide,
            Double carbonDioxide,
            Double nitrogen,
            Integer modificationMethod,
            Integer deviationFactorMethod,
            Double reservoirPorosity,
            Double rockDensity,
            Double langmuirPressure,
            Double langmuirVolume,
            Integer viscosityMethod) {}

    /** 动态地质储量及其来源说明（要把选中的输出行标出来，便于人工核对）。 */
    private record GasVolume(Double value, String note) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Context context(long projectId, long gasReservoirId, long storageId) {
        catalog.requireScope(jdbc, projectId, gasReservoirId, storageId);
        List<String> warnings = new ArrayList<>();
        Map<String, String> inputSources = new LinkedHashMap<>();

        List<StorageCatalogService.Well> wells = catalog.wells(storageId, projectId, gasReservoirId);
        if (wells.isEmpty()) {
            warnings.add("该储气库下还没有关联单井，请先在储气库档案里添加单井。");
        }

        // 取第一口有完整物质平衡输入的井做预填模板：原平台工具箱一次只接受一组参数，
        // 库级要合成一组就必须先选代表井。选谁会在下方说明，避免用户以为这是全库聚合值。
        Source source = null;
        for (StorageCatalogService.Well well : wells) {
            Source candidate = loadSource(projectId, gasReservoirId, well.wellName());
            if (candidate != null) {
                source = candidate;
                break;
            }
        }

        FactorValue measured = measuredPressure(projectId, gasReservoirId, source, warnings);

        ToolboxInput inputs = source == null ? null : toToolboxInput(source, warnings);
        if (source != null) {
            inputSources.put("originalPressure", AUTO);
            inputSources.put("formationTemperature", AUTO);
            inputSources.put("originalGasInPlace", AUTO);
            warnings.add("工具箱入参按库内第一口有完整输入的井（" + source.wellName() + "）预填，可手动修改。");
        } else if (!wells.isEmpty()) {
            warnings.add("库内单井没有物质平衡方程输入，无法自动预填工具箱入参，理论地层压力需要手动填写。");
        }

        return new Context(measured, inputs, inputSources, dedupe(warnings));
    }

    /**
     * 实测地层压力：优先取代表井的实测静压，退而取静态压力数据表最新一条。
     *
     * <p>两者都没有时返回 {@code MISSING} 并给出中文提示，绝不用 0 顶替——
     * 0 会让差异显示出一个看似正常的错值。
     */
    private FactorValue measuredPressure(long projectId, long gasReservoirId, Source source, List<String> warnings) {
        if (source != null) {
            Double measured = loadLatestMeasuredPressure(source.inputId());
            if (measured != null) {
                return auto(measured, "实测静压：" + source.wellName());
            }
        }
        Double fallback = loadLatestStaticPressure(projectId, gasReservoirId);
        if (fallback != null) {
            return auto(fallback, "静态压力数据表最新一条");
        }
        warnings.add("没有可用的实测静压，请手动填写实际地层压力。");
        return missing("没有可用的实测静压");
    }

    /**
     * 计算地层压力的理论值与差异。
     *
     * <p>刻意<b>不</b>加 {@code @Transactional}：这里会发起最长 60 秒的原平台 HTTP 调用，
     * 包在事务里会一直占着数据库连接（连接池上限 10），把不相关的请求一起拖住。
     * 方法本身只读库（作用域校验与实测静压），不需要事务。
     *
     * <p>平台调用与实际值读取是解耦的：原平台不可用时理论值为空、来源为 {@code MISSING}，
     * **实际值仍照常读出并显示**，差异为 null。只有"入参非空但不完整"才报 400。
     */
    public CalculateResult calculate(CalculateRequest request, Map<String, String> headers) {
        catalog.requireScope(jdbc, request.projectId(), request.gasReservoirId(), request.storageId());
        List<String> warnings = new ArrayList<>();

        Double pressure = null;
        if (request.inputs() == null) {
            warnings.add("库内没有物质平衡方程输入，无法自动计算理论地层压力，请手动填写。");
        } else {
            pressure = client.calculateFormationPressure(request.projectId(), request.inputs(), headers);
            if (pressure == null) {
                warnings.add("原平台未登录或不可用，理论地层压力暂时算不出来。");
            }
        }

        // 实际值每次都从库里重新读，不接受前端回传的结果值：
        // 否则页面上的旧值会被当成实测静压再显示回给用户。
        List<StorageCatalogService.Well> wells = catalog.wells(
                request.storageId(), request.projectId(), request.gasReservoirId());
        FactorValue measured = measuredPressure(request.projectId(), request.gasReservoirId(),
                firstSourceWithInput(request.projectId(), request.gasReservoirId(), wells), warnings);

        Double actual = measured.value();
        return new CalculateResult(pressure, pressure != null ? AUTO : MISSING, measured,
                difference(actual, pressure), deviationPercent(actual, pressure), dedupe(warnings));
    }

    /** 代表井：库内第一口有完整物质平衡输入的单井。 */
    private Source firstSourceWithInput(long projectId, long gasReservoirId, List<StorageCatalogService.Well> wells) {
        for (StorageCatalogService.Well well : wells) {
            Source candidate = loadSource(projectId, gasReservoirId, well.wellName());
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /** 同一句话只留一条，避免空库页面上堆一串重复的黄色提示。 */
    private static List<String> dedupe(List<String> warnings) {
        return List.copyOf(new LinkedHashSet<>(warnings));
    }

    /**
     * 代表井的输入行取**最新**一条：同一口井可能有多版物质平衡输入，
     * 按 id 升序会一直用最早的那一版。
     */
    private Source loadSource(long projectId, long gasReservoirId, String wellName) {
        List<Source> found = jdbc.query("""
                SELECT i.id, d.well_name, i.original_pressure, i.temperature,
                       i.rock_compression_coefficient, i.water_compression_coefficient, i.water_saturation,
                       i.gas_reservoir_type, i.gas_type, i.specific_gravity, i.hydrogen_sulfide,
                       i.carbon_dioxide, i.nitrogen, i.modification_method, i.deviation_factor_method,
                       i.reservoir_porosity, i.shale_rock_desity, i.langmuir_pressure, i.langmuir_volume,
                       i.viscosity_method
                FROM dynamic_original_gas_in_place d
                JOIN dynamic_original_gas_in_place_by_mb_input i ON i.dynamic_original_gas_in_place_id = d.id
                WHERE d.project_id=? AND d.project_gas_reservoir_id=? AND d.well_name=?
                ORDER BY COALESCE(d.update_time, d.create_time) DESC, i.id DESC
                """, (rs, n) -> new Source(
                        rs.getLong(1), rs.getString(2),
                        doubleOrNull(rs, 3), doubleOrNull(rs, 4),
                        doubleOrNull(rs, 5), doubleOrNull(rs, 6), doubleOrNull(rs, 7),
                        intOrNull(rs, 8), rs.getString(9), doubleOrNull(rs, 10),
                        doubleOrNull(rs, 11), doubleOrNull(rs, 12), doubleOrNull(rs, 13),
                        intOrNull(rs, 14), intOrNull(rs, 15),
                        doubleOrNull(rs, 16), doubleOrNull(rs, 17),
                        doubleOrNull(rs, 18), doubleOrNull(rs, 19),
                        intOrNull(rs, 20)),
                projectId, gasReservoirId, wellName);
        return found.isEmpty() ? null : found.getFirst();
    }

    /**
     * 按 {@link Number} 取值，不按具体包装类型强转。
     *
     * <p>真实库里 {@code modification_method} / {@code deviation_factor_method} /
     * {@code gas_reservoir_type} 是 <b>BIGINT</b>，MySQL 驱动因此返回 {@code Long}；
     * 若写成 {@code (Integer) rs.getObject(n)} 会 ClassCastException 直接 500。
     * 而 H2 用 INT 建表时返回的是 Integer，恰好把这个差异掩盖过去——所以测试建表也用 BIGINT，
     * 与真实库保持一致。
     */
    private static Integer intOrNull(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        Object value = rs.getObject(index);
        return value == null ? null : ((Number) value).intValue();
    }

    private static Double doubleOrNull(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        Object value = rs.getObject(index);
        return value == null ? null : ((Number) value).doubleValue();
    }

    /** 实测静压以 Pa 存在物质平衡输入明细里，换成 MPa 再展示。 */
    private Double loadLatestMeasuredPressure(long inputId) {
        List<Double> found = jdbc.query("""
                SELECT formation_pressure FROM dynamic_original_gas_in_place_by_mb_input_item
                WHERE dynamic_original_gas_inplace_by_mb_input_id=? AND (is_deleted IS NULL OR is_deleted=FALSE)
                  AND formation_pressure IS NOT NULL
                ORDER BY date DESC
                """, (rs, n) -> doubleOrNull(rs, 1), inputId);
        return firstMpa(found);
    }

    /**
     * 没有物质平衡输入时的兜底。{@code project_static_pressure_data.reservior_pressure}
     * 在本机真实库里实测是 <b>Pa</b>（19650000 ~ 31608000，即 19.65 ~ 31.6 MPa），
     * 不是 MPa——直接当 MPa 用会让差异列整体偏 10⁶ 倍。
     */
    private Double loadLatestStaticPressure(long projectId, long gasReservoirId) {
        List<Double> found = jdbc.query("""
                SELECT reservior_pressure FROM project_static_pressure_data
                WHERE project_id=? AND project_gas_reservoir_id=? AND reservior_pressure IS NOT NULL
                ORDER BY date DESC
                """, (rs, n) -> doubleOrNull(rs, 1), projectId, gasReservoirId);
        return firstMpa(found);
    }

    private static Double firstMpa(List<Double> paValues) {
        for (Double pa : paValues) {
            Double mpa = StorageMainFactorUnits.paToMpa(pa);
            if (mpa != null) {
                return mpa;
            }
        }
        return null;
    }

    /**
     * 动态地质储量取**与物质平衡输入同一来源行、且 method 与母行一致**的那条输出，
     * 并把选中的输出行 id 写进来源说明。
     *
     * <p>只按 {@code o.id} 取第一条会随插入顺序漂移；本库 G 的跨度是 19.85 ~ 33.05 ×10⁸m³，
     * 取错一条就是几十个百分点的误差。没有按 {@code reliablity} 排序是因为该列在本库里
     * 只有 1 / 2 两个取值，方向（越大越好还是越小越好）未经验证，按它排反而是新的猜测。
     */
    private GasVolume loadGasVolume(long inputId) {
        List<GasVolume> found = jdbc.query("""
                SELECT o.original_gas_volume, o.id
                FROM dynamic_original_gas_in_place_output o
                JOIN dynamic_original_gas_in_place_by_mb_input i
                  ON i.dynamic_original_gas_in_place_id = o.dynamic_original_gas_in_place_id
                JOIN dynamic_original_gas_in_place d
                  ON d.id = o.dynamic_original_gas_in_place_id
                WHERE i.id=? AND o.original_gas_volume IS NOT NULL
                ORDER BY CASE WHEN o.dynamic_original_gas_inplace_method = d.dynamic_original_gas_inplace_method
                              THEN 0 ELSE 1 END,
                         o.id DESC
                """, (rs, n) -> {
                    Double volume = StorageMainFactorUnits.cubicMeterToHundredMillion(doubleOrNull(rs, 1));
                    if (volume == null) {
                        return null;
                    }
                    return new GasVolume(volume, "动态地质储量（来源输出行 id=" + rs.getLong(2) + "）");
                }, inputId);
        for (GasVolume candidate : found) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 把数据库口径的入参换成原平台口径。
     *
     * <p>缺值策略分两类，这是刻意的：气藏类型为 2（页岩气藏）才会用到的
     * {@code reservoirPorosity / rockDensity / langmuir*} 缺省按 0 发送；
     * 而**任何气藏都要用**的组分与比重如果为空，会记一条明确的中文警告——
     * 把它们静默当 0 会改变偏差系数进而改变算出来的地层压力。
     */
    private ToolboxInput toToolboxInput(Source source, List<String> warnings) {
        List<Double> cumulative = jdbc.query("""
                SELECT cumulative_production FROM dynamic_original_gas_in_place_by_mb_input_item
                WHERE dynamic_original_gas_inplace_by_mb_input_id=? AND (is_deleted IS NULL OR is_deleted=FALSE)
                ORDER BY date DESC
                """, (rs, n) -> doubleOrNull(rs, 1), source.inputId());
        Double gp = null;
        for (Double m3 : cumulative) {
            gp = StorageMainFactorUnits.cubicMeterToHundredMillion(m3);
            if (gp != null) {
                break;
            }
        }
        GasVolume volume = loadGasVolume(source.inputId());
        Double g = volume == null ? null : volume.value();

        warnIfMissing(warnings, source.specificGravity(), "天然气比重");
        warnIfMissing(warnings, source.hydrogenSulfide(), "H₂S 摩尔百分含量");
        warnIfMissing(warnings, source.carbonDioxide(), "CO₂ 摩尔百分含量");
        warnIfMissing(warnings, source.nitrogen(), "N₂ 摩尔百分含量");

        GasPvtParam pvt = new GasPvtParam(
                StorageMainFactorUnits.gasTypeCode(source.gasType()),
                nz(source.specificGravity()),
                source.modificationMethod() == null ? 0 : source.modificationMethod(),
                // 三个组分保持**小数**（应用口径）。toolboxPayload 是唯一的换算边界，
                // 它统一乘 100 变成原平台 calc 要求的百分数——
                // 依据是平台自己返回的 inputRange 里 maxH2SMoleFraction=100。
                nz(source.hydrogenSulfide()),
                nz(source.carbonDioxide()),
                nz(source.nitrogen()),
                source.deviationFactorMethod() == null ? 0 : source.deviationFactorMethod(),
                source.viscosityMethod() == null ? 0 : source.viscosityMethod());
        return new ToolboxInput(
                source.originalPressure(),
                source.temperature(),
                g,
                gp,
                source.rockCompressionCoefficient(),
                source.waterCompressionCoefficient(),
                source.waterSaturation(),
                source.gasReservoirType(),
                source.reservoirPorosity(),
                source.rockDensity(),
                source.langmuirPressure(),
                source.langmuirVolume(),
                pvt);
    }

    private static void warnIfMissing(List<String> warnings, Double value, String label) {
        if (value == null) {
            warnings.add("物质平衡输入缺少" + label + "，工具箱将按 0 计算，结果可能偏离，请核对后手动修改。");
        }
    }

    private static double nz(Double value) {
        return value == null ? 0d : value;
    }
}
