package com.grdp.studio.storagemainfactor;

import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.AUTO;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.MANUAL;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.auto;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.gasSaturation;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.manual;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.missing;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.poreVolume;
import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.rows;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.*;

/**
 * 库级主控因素分析：读库预填四因素，调原平台算理论地层压力，算差异。
 *
 * <p>数据来自原平台的物质平衡来源表。注意这三张表<b>本身没有 project / well 列</b>，
 * 靠外键串起来：{@code dynamic_original_gas_in_place} 持 project/well，
 * {@code ..._by_mb_input} 持入参，{@code ..._by_mb_input_item} 持逐日期 p 与累产气量，
 * {@code ..._output} 持动态地质储量。
 *
 * <p>口径：{@link StorageMainFactorUnits} 负责把数据库单位换到原平台单位，本类只负责编排。
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
        if (source == null && !wells.isEmpty()) {
            warnings.add("库内单井没有物质平衡方程输入，无法自动预填工具箱入参。");
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
        if (source != null) {
            Double volume = loadGasVolume(source.wellName(), source.inputId());
            if (volume != null) {
                actual.put("gas", auto(volume, "动态地质储量（与物质平衡输入同源）"));
            } else {
                actual.put("gas", missing("没有可用的动态地质储量"));
                warnings.add("没有可用的动态地质储量，请手动填写实际天然气量。");
            }
        } else {
            actual.put("gas", missing("没有可用的动态地质储量"));
        }
        // 理论天然气是**设计地质储量**，库里没有这个字段：
        // project_storage_capacity_design 存的是库容量/工作气量/垫气量，那是库容口径，不是地质储量，不能拿来做理论值。
        theoretical.put("gas", missing("设计地质储量需手动填写"));
        warnings.add("库容设计表没有地质储量字段，③天然气的理论值需要手动填写。");

        // ② 动用的孔隙体积：两侧都没有字段
        theoretical.put("poreVolume", missing("设计孔隙体积需手动填写"));
        actual.put("poreVolume", missing("需先填写 Bg，或手动填写"));
        warnings.add("②动用孔隙体积库中没有字段，理论值与实际值都需要手动填写。");

        // ④ 气体饱和度：理论值默认 1 − 束缚水饱和度
        Double swi = source == null ? null : source.waterSaturation();
        if (swi != null) {
            theoretical.put("gasSaturation", auto(1d - swi, "默认 1 − 束缚水饱和度"));
        } else {
            theoretical.put("gasSaturation", missing("设计气体饱和度需手动填写"));
        }
        actual.put("gasSaturation", missing("需先填写 Bg，或手动填写"));

        ToolboxInput inputs = source == null ? null : toToolboxInput(source, projectId, gasReservoirId);
        if (source != null) {
            inputSources.put("originalPressure", AUTO);
            inputSources.put("formationTemperature", AUTO);
            inputSources.put("originalGasInPlace", AUTO);
            warnings.add("工具箱入参按库内第一口有完整输入的井（" + source.wellName() + "）预填，可手动修改。");
        }

        return new Context(rows(theoretical, actual), inputs, inputSources, warnings);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CalculateResult calculate(CalculateRequest request, Map<String, String> headers) {
        catalog.requireScope(jdbc, request.projectId(), request.gasReservoirId(), request.storageId());
        List<String> warnings = new ArrayList<>();
        Map<String, FactorValue> theoretical = asManual(request.theoretical());
        Map<String, FactorValue> actual = asManual(request.actual());
        theoretical.putIfAbsent("formationPressure", missing(null));
        actual.putIfAbsent("formationPressure", missing(null));

        Double pressure = client.calculateFormationPressure(request.projectId(), request.inputs(), headers);
        if (pressure != null) {
            // 原平台算出来的理论地层压力优先于用户手输值：用户点"读取并计算"就是要它。
            theoretical.put("formationPressure", auto(pressure, "原平台物质平衡方程"));
        } else {
            warnings.add("原平台未登录或不可用，请手动填写理论地层压力。");
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

        return new CalculateResult(pressure, pressure != null ? AUTO : MANUAL, rows(theoretical, actual), warnings);
    }

    /** 调用方回传的值一律按"用户填写"对待；缺项保持 MISSING 而不是当成 0。 */
    private static Map<String, FactorValue> asManual(Map<String, Double> values) {
        Map<String, FactorValue> mapped = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> mapped.put(key, manual(value, "手动填写")));
        }
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
                ORDER BY i.id
                """, (rs, n) -> new Source(
                        rs.getLong(1), rs.getString(2),
                        (Double) rs.getObject(3), (Double) rs.getObject(4),
                        (Double) rs.getObject(5), (Double) rs.getObject(6), (Double) rs.getObject(7),
                        (Integer) rs.getObject(8), rs.getString(9), (Double) rs.getObject(10),
                        (Double) rs.getObject(11), (Double) rs.getObject(12), (Double) rs.getObject(13),
                        (Integer) rs.getObject(14), (Integer) rs.getObject(15),
                        (Double) rs.getObject(16), (Double) rs.getObject(17),
                        (Double) rs.getObject(18), (Double) rs.getObject(19)),
                projectId, gasReservoirId, wellName);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** 实测静压以 Pa 存在物质平衡输入明细里，换成 MPa 再展示。 */
    private Double loadLatestMeasuredPressure(long inputId) {
        List<Double> found = jdbc.query("""
                SELECT formation_pressure FROM dynamic_original_gas_in_place_by_mb_input_item
                WHERE dynamic_original_gas_inplace_by_mb_input_id=? AND (is_deleted IS NULL OR is_deleted=FALSE)
                  AND formation_pressure IS NOT NULL
                ORDER BY date DESC
                """, (rs, n) -> (Double) rs.getObject(1), inputId);
        for (Double pa : found) {
            Double mpa = StorageMainFactorUnits.paToMpa(pa);
            if (mpa != null) {
                return mpa;
            }
        }
        return null;
    }

    /** 没有物质平衡输入时的兜底：静态压力数据表本身就是 MPa。 */
    private Double loadLatestStaticPressure(long projectId, long gasReservoirId) {
        List<Double> found = jdbc.query("""
                SELECT reservior_pressure FROM project_static_pressure_data
                WHERE project_id=? AND project_gas_reservoir_id=? AND reservior_pressure IS NOT NULL
                ORDER BY date DESC
                """, (rs, n) -> (Double) rs.getObject(1), projectId, gasReservoirId);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** 动态地质储量取**与物质平衡输入同一来源行**的输出，避免在多种 method 之间取错。 */
    private Double loadGasVolume(String wellName, long inputId) {
        List<Double> found = jdbc.query("""
                SELECT o.original_gas_volume
                FROM dynamic_original_gas_in_place_output o
                JOIN dynamic_original_gas_in_place_by_mb_input i ON i.dynamic_original_gas_in_place_id = o.dynamic_original_gas_in_place_id
                WHERE i.id=? AND o.original_gas_volume IS NOT NULL
                ORDER BY o.id
                """, (rs, n) -> (Double) rs.getObject(1), inputId);
        for (Double m3 : found) {
            Double volume = StorageMainFactorUnits.cubicMeterToHundredMillion(m3);
            if (volume != null) {
                return volume;
            }
        }
        return null;
    }

    /** 把数据库口径的入参换成原平台口径。 */
    private ToolboxInput toToolboxInput(Source source, long projectId, long gasReservoirId) {
        List<Double> cumulative = jdbc.query("""
                SELECT cumulative_production FROM dynamic_original_gas_in_place_by_mb_input_item
                WHERE dynamic_original_gas_inplace_by_mb_input_id=? AND (is_deleted IS NULL OR is_deleted=FALSE)
                ORDER BY date DESC
                """, (rs, n) -> (Double) rs.getObject(1), source.inputId());
        Double gp = null;
        for (Double m3 : cumulative) {
            gp = StorageMainFactorUnits.cubicMeterToHundredMillion(m3);
            if (gp != null) {
                break;
            }
        }
        Double g = loadGasVolume(source.wellName(), source.inputId());
        GasPvtParam pvt = new GasPvtParam(
                StorageMainFactorUnits.gasTypeCode(source.gasType()),
                source.specificGravity() == null ? 0d : source.specificGravity(),
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

    private static double nz(Double value) {
        return value == null ? 0d : value;
    }
}
