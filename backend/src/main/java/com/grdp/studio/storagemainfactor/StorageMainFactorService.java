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

import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.AUTO;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.MANUAL;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.auto;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.gasSaturation;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.missing;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.poreVolume;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.rows;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.*;

/**
 * 库级主控因素分析：读库预填四因素，调原平台算理论地层压力，算差异。
 *
 * <p>数据来自原平台的物质平衡来源表。注意这三张表<b>本身没有 project / well 列</b>，
 * 靠外键串起来：{@code dynamic_original_gas_in_place} 持 project/well/时间戳，
 * {@code ..._by_mb_input} 持入参，{@code ..._by_mb_input_item} 持逐日期 p 与累产气量，
 * {@code ..._output} 持动态地质储量。
 *
 * <p>口径：{@link StorageMainFactorUnits} 负责把数据库单位换到展示/原平台单位，本类只负责编排。
 * 本页是**即算即看、不落库**，与库级物质平衡、库级诊断曲线一致。
 */
@Service
public class StorageMainFactorService {

    private final JdbcTemplate jdbc;
    private final StorageCatalogService catalog;
    private final MaterialBalanceEquationClient client;

    public StorageMainFactorService(JdbcTemplate jdbc, StorageCatalogService catalog, MaterialBalanceEquationClient client) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.client = client;
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
            Double langmuirVolume) {}

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

        Map<String, FactorValue> theoretical = new LinkedHashMap<>();
        Map<String, FactorValue> actual = new LinkedHashMap<>();

        // ① 地层压力：理论值等 calculate 调原平台；实际值取实测静压
        theoretical.put("formationPressure", missing("需调用原平台物质平衡方程"));
        Double measured = source == null ? null : loadLatestMeasuredPressure(source.inputId());
        String measuredNote = source == null ? null : "实测静压：" + source.wellName();
        if (measured == null) {
            measured = loadLatestStaticPressure(projectId, gasReservoirId);
            measuredNote = "静态压力数据表最新一条";
        }
        if (measured != null) {
            actual.put("formationPressure", auto(measured, measuredNote));
        } else {
            actual.put("formationPressure", missing("没有可用的实测静压"));
            warnings.add("没有可用的实测静压，请手动填写实际地层压力。");
        }

        // ③ 天然气：实际值取与入参同源的动态地质储量
        GasVolume volume = source == null ? null : loadGasVolume(source.inputId());
        if (volume != null) {
            actual.put("gas", auto(volume.value(), volume.note()));
        } else {
            actual.put("gas", missing("没有可用的动态地质储量"));
            if (source != null) {
                warnings.add("没有可用的动态地质储量，请手动填写实际天然气量。");
            }
        }

        // ② ③ ④ 的理论值库中没有对应字段。没有代表井时只给一条汇总说明，
        // 不要每个因素各弹一条，否则空库页面上会堆四五条黄色提示。
        if (source == null) {
            if (!wells.isEmpty()) {
                warnings.add("库内单井没有物质平衡方程输入，无法自动预填工具箱入参，也无法自动读取动态地质储量。");
            }
            warnings.add("②动用孔隙体积、③设计地质储量、④设计气体饱和度库中都没有字段，理论值需要手动填写。");
        } else {
            warnings.add("库容设计表没有地质储量字段，③天然气的理论值需要手动填写。");
            warnings.add("②动用孔隙体积库中没有字段，理论值与实际值都需要手动填写。");
        }
        theoretical.put("gas", missing("设计地质储量需手动填写"));
        theoretical.put("poreVolume", missing("设计孔隙体积需手动填写"));
        actual.put("poreVolume", missing("需先填写 Bg，或手动填写"));
        actual.put("gasSaturation", missing("需先填写 Bg，或手动填写"));

        // ④ 气体饱和度：理论值默认 1 − 束缚水饱和度
        Double swi = source == null ? null : source.waterSaturation();
        if (swi != null) {
            theoretical.put("gasSaturation", auto(1d - swi, "默认 1 − 束缚水饱和度"));
        } else {
            theoretical.put("gasSaturation", missing("设计气体饱和度需手动填写"));
        }

        ToolboxInput inputs = source == null ? null : toToolboxInput(source, warnings);
        if (source != null) {
            inputSources.put("originalPressure", AUTO);
            inputSources.put("formationTemperature", AUTO);
            inputSources.put("originalGasInPlace", AUTO);
            warnings.add("工具箱入参按库内第一口有完整输入的井（" + source.wellName() + "）预填，可手动修改。");
        }

        return new Context(rows(theoretical, actual), inputs, inputSources, dedupe(warnings));
    }

    /**
     * 计算四因素差异。
     *
     * <p>刻意<b>不</b>加 {@code @Transactional}：这里会发起最长 60 秒的原平台 HTTP 调用，
     * 包在事务里会一直占着数据库连接（连接池上限 10），把不相关的请求一起拖住。
     * 方法本身只读一次库（作用域校验），不需要事务。
     *
     * <p>平台调用与差异计算是解耦的：库级没有入参（本机现状）或原平台不可用时，
     * 仍然要用回传/手输的值把四行差异算出来，只有"入参非空但不完整"才报 400。
     */
    public CalculateResult calculate(CalculateRequest request, Map<String, String> headers) {
        catalog.requireScope(jdbc, request.projectId(), request.gasReservoirId(), request.storageId());
        List<String> warnings = new ArrayList<>();
        Map<String, FactorValue> theoretical = asValues(request.theoretical());
        Map<String, FactorValue> actual = asValues(request.actual());

        Double pressure = null;
        if (request.inputs() == null) {
            // 不去调原平台：没有入参就打不通，而且这不该让整个请求失败——
            // 用户还要手工填四个值看差异（spec §8 / §11-5）。
            warnings.add("库内没有物质平衡方程输入，无法自动计算理论地层压力，请手动填写。");
        } else {
            pressure = client.calculateFormationPressure(request.projectId(), request.inputs(), headers);
            if (pressure == null) {
                warnings.add("原平台未登录或不可用，请手动填写理论地层压力。");
            }
        }
        if (pressure != null) {
            // 原平台算出来的理论地层压力优先于用户手输值：用户点"读取并计算"就是要它。
            theoretical.put("formationPressure", auto(pressure, "原平台物质平衡方程"));
        }

        ToolboxInput inputs = request.inputs();
        Double bg = request.volumeFactor();
        if (bg == null) {
            warnings.add("未填写天然气体积系数 Bg，②动用孔隙体积与④气体饱和度无法自动计算。");
        } else if (inputs != null) {
            if (!hasValue(actual, "poreVolume")) {
                Double vp = poreVolume(inputs.originalGasInPlace(), bg, inputs.waterSaturation());
                if (vp != null) {
                    actual.put("poreVolume", auto(vp, "Vp = G·Bg/(1−Swi)"));
                }
            }
            if (!hasValue(actual, "gasSaturation")) {
                Double sg = gasSaturation(inputs.originalGasInPlace(), inputs.cumulativeGasProduction(),
                        bg, valueOf(actual, "poreVolume"));
                if (sg != null) {
                    actual.put("gasSaturation", auto(sg, "Sg = (G−Gp)·Bg/Vp"));
                }
            }
        }

        return new CalculateResult(pressure, pressure != null ? AUTO : MANUAL,
                rows(theoretical, actual), dedupe(warnings));
    }

    /**
     * 回传的取值**原样保留来源**：只有调用方没给来源（或给的是裸值）时才当作手动填写。
     * 这样"实测静压：X-1"这类溯源信息不会因为点了一次计算就退化成"手动填写"。
     */
    private static Map<String, FactorValue> asValues(Map<String, FactorValue> values) {
        Map<String, FactorValue> mapped = new LinkedHashMap<>();
        if (values == null) {
            return mapped;
        }
        values.forEach((key, value) -> {
            if (value == null || value.value() == null) {
                mapped.put(key, missing(value == null ? null : value.note()));
                return;
            }
            String source = value.source() == null || value.source().isBlank() ? MANUAL : value.source();
            mapped.put(key, new FactorValue(value.value(), source, value.note()));
        });
        return mapped;
    }

    private static boolean hasValue(Map<String, FactorValue> values, String key) {
        FactorValue value = values.get(key);
        return value != null && value.value() != null;
    }

    private static Double valueOf(Map<String, FactorValue> values, String key) {
        FactorValue value = values.get(key);
        return value == null ? null : value.value();
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
                       i.reservoir_porosity, i.shale_rock_desity, i.langmuir_pressure, i.langmuir_volume
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
                        doubleOrNull(rs, 18), doubleOrNull(rs, 19)),
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
                nz(StorageMainFactorUnits.fractionToPercent(source.hydrogenSulfide())),
                nz(StorageMainFactorUnits.fractionToPercent(source.carbonDioxide())),
                nz(StorageMainFactorUnits.fractionToPercent(source.nitrogen())),
                source.deviationFactorMethod() == null ? 0 : source.deviationFactorMethod());
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
