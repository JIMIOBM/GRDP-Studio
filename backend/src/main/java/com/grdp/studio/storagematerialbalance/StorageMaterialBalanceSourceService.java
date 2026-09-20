package com.grdp.studio.storagematerialbalance;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/** 在库页追溯智慧气藏的实测来源；不创建单井页面，不重算、不保存原平台数据。 */
@Service
public class StorageMaterialBalanceSourceService {
    public record Parameters(String gasType, Double specificGravity, Double hydrogenSulfide,
                             Double carbonDioxide, Double nitrogen, Integer modificationMethod,
                             Integer deviationFactorMethod, Double temperature, Double waterGasRatioLimit) {}
    public record InputRow(LocalDate date, Double pressure, Double gas, Double water, boolean deleted) {}
    public record Output(Double gasVolume, Double intercept, Double gradient, Double rSquared,
                         Integer reliability, String reliabilityDescription) {}
    public record ResultRow(LocalDate date, Double formationPressure, Double pressure, Double gas,
                            Double water, boolean deleted) {}
    public record LinePoint(Double gas, Double pressure) {}
    public record Detail(long wellId, String wellName, long resultId, Parameters parameters,
                         List<InputRow> inputRows, Output output, List<ResultRow> resultRows,
                         List<LinePoint> regressionLine, String message) {}
    public record Availability(long wellId, String wellName, boolean inStorage, int measuredCount, int calculatedCount) {}
    private record SavedOutput(long id, Output value) {}
    private record Point(ResultRow value, Double linePressure) {}
    private final JdbcTemplate jdbc;
    private final StorageCatalogService catalog;

    public StorageMaterialBalanceSourceService(JdbcTemplate jdbc, StorageCatalogService catalog) {
        this.jdbc = jdbc; this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public List<Availability> availability(long projectId, long gasReservoirId, long storageId) {
        var memberIds = catalog.wells(storageId, projectId, gasReservoirId).stream()
                .map(StorageCatalogService.Well::id).collect(java.util.stream.Collectors.toSet());
        // 仅列出同项目候选井的数据覆盖情况，不把未入库的井自动纳入汇总。
        var candidates = catalog.candidateWells(projectId, gasReservoirId);
        return candidates.stream().map(well -> {
            var counts = jdbc.query("""
                    SELECT dynamic_original_gas_inplace_method,COUNT(*) AS count FROM dynamic_original_gas_in_place
                    WHERE project_id=? AND project_gas_reservoir_id=? AND well_name=?
                      AND dynamic_original_gas_inplace_method IN (1,2) GROUP BY dynamic_original_gas_inplace_method
                    """, (rs,n) -> new int[]{rs.getInt(1),rs.getInt(2)}, projectId, gasReservoirId, well.wellName());
            return new Availability(well.id(), well.wellName(), memberIds.contains(well.id()),
                    counts.stream().filter(c -> c[0] == 1).mapToInt(c -> c[1]).sum(),
                    counts.stream().filter(c -> c[0] == 2).mapToInt(c -> c[1]).sum());
        }).toList();
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Detail detail(long projectId, long gasReservoirId, long storageId, long wellId, long resultId) {
        var well = catalog.wells(storageId, projectId, gasReservoirId).stream().filter(w -> w.id() == wellId)
                .findFirst().orElseThrow(() -> new BusinessException(404, "该井不是当前库的成员"));
        if (resultId <= 0) throw new BusinessException(400, "请选择实测静压来源结果");
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dynamic_original_gas_in_place d
                JOIN project_gas_reservoir g ON g.id=d.project_gas_reservoir_id AND g.project_id=d.project_id
                JOIN project_summaries p ON p.id=d.project_id AND p.delete_status=0
                WHERE d.id=? AND d.project_id=? AND d.project_gas_reservoir_id=? AND d.well_name=?
                  AND d.dynamic_original_gas_inplace_method=1
                """, Integer.class, resultId, projectId, gasReservoirId, well.wellName());
        if (count == null || count != 1) throw new BusinessException(404, "该实测静压结果不属于当前库成员井或项目已删除");

        var inputs = jdbc.query("""
                SELECT id,gas_type,specific_gravity,hydrogen_sulfide,carbon_dioxide,nitrogen,
                       modification_method,deviation_factor_method,temperature,water_gas_ratio_limit
                FROM dynamic_original_gas_in_place_by_mb_input WHERE dynamic_original_gas_in_place_id=? AND gas_reservoir_type=1
                """, (rs,n) -> new java.util.AbstractMap.SimpleImmutableEntry<>(rs.getLong("id"), new Parameters(
                rs.getString("gas_type"), number(rs,"specific_gravity",1), number(rs,"hydrogen_sulfide",.01),
                number(rs,"carbon_dioxide",.01), number(rs,"nitrogen",.01), rs.getObject("modification_method",Integer.class),
                rs.getObject("deviation_factor_method",Integer.class), celsius(rs.getObject("temperature",Double.class)),
                number(rs,"water_gas_ratio_limit",1e-4))), resultId);
        var inputRows = inputs.isEmpty() ? List.<InputRow>of() : jdbc.query("""
                SELECT date,formation_pressure,cumulative_production,cumulative_water_production,is_deleted
                FROM dynamic_original_gas_in_place_by_mb_input_item
                WHERE dynamic_original_gas_inplace_by_mb_input_id=? ORDER BY date,id LIMIT 100001
                """, (rs,n) -> new InputRow(date(rs),number(rs,"formation_pressure",1e6),
                number(rs,"cumulative_production",1e8),number(rs,"cumulative_water_production",1e4),rs.getBoolean("is_deleted")),
                inputs.getFirst().getKey());
        var outputs = jdbc.query("""
                SELECT id,original_gas_volume,intercept,gradient,rsquared,reliablity,reliability_desc
                FROM dynamic_original_gas_in_place_output WHERE dynamic_original_gas_in_place_id=?
                  AND project_id=? AND project_gas_reservoir_id=? AND well_name=? AND dynamic_original_gas_inplace_method=1
                """, (rs,n) -> new SavedOutput(rs.getLong("id"),new Output(number(rs,"original_gas_volume",1e8),
                number(rs,"intercept",1e6),number(rs,"gradient",.01),number(rs,"rsquared",1),
                rs.getObject("reliablity",Integer.class),rs.getString("reliability_desc"))),resultId,projectId,gasReservoirId,well.wellName());
        var points = outputs.isEmpty() ? List.<Point>of() : jdbc.query("""
                SELECT date,formation_pressure,pressure,cumulative_gas_production,cumulative_water_production,
                       linear_regression_pressure,is_deleted
                FROM dynamic_original_gas_in_place_output_item
                WHERE dynamic_original_gas_inplace_output_id=? ORDER BY date,id LIMIT 100001
                """, (rs,n) -> new Point(new ResultRow(date(rs),number(rs,"formation_pressure",1e6),number(rs,"pressure",1e6),
                number(rs,"cumulative_gas_production",1e8),number(rs,"cumulative_water_production",1e4),rs.getBoolean("is_deleted")),
                number(rs,"linear_regression_pressure",1e6)),outputs.getFirst().id());
        if (inputRows.size() > 100000 || points.size() > 100000) throw new BusinessException(400,"实测来源超过10万行，未返回截断数据");
        Output output = outputs.isEmpty() ? null : outputs.getFirst().value();
        boolean validRegression = output != null && output.reliability() != null && output.reliability() > 0
                && output.gasVolume() != null && output.gasVolume() > 0;
        var resultRows = points.stream().filter(p -> p.value().pressure() != null || p.value().formationPressure() != null)
                .map(Point::value).toList();
        var line = !validRegression ? List.<LinePoint>of() : points.stream()
                .filter(p -> !p.value().deleted() && p.value().gas() != null && p.linePressure() != null)
                .map(p -> new LinePoint(p.value().gas(),p.linePressure()))
                .sorted(java.util.Comparator.comparingDouble(LinePoint::gas)).toList();
        String message = inputs.isEmpty() ? "实测输入参数缺失或类型不匹配，不能用于库级汇总"
                : output == null ? "尚未保存实测静压输出结果"
                : !validRegression ? "原平台实测分析失败，保留来源数据供核对，不绘制回归线"
                : "智慧气藏已保存的成员井实测结果，仅作库级来源核对；不是库级回归结果";
        return new Detail(well.id(),well.wellName(),resultId,inputs.isEmpty() ? null : inputs.getFirst().getValue(),
                inputRows,output,resultRows,line,message);
    }

    private static Double number(ResultSet rs,String column,double divisor) throws SQLException {
        Double value = rs.getObject(column,Double.class);
        if (value == null || !Double.isFinite(value)) return null;
        double scaled = value / divisor;
        return Double.isFinite(scaled) ? scaled : null;
    }
    private static Double celsius(Double value) { return value == null || !Double.isFinite(value) ? null : value - 273.15; }
    private static LocalDate date(ResultSet rs) throws SQLException { return rs.getDate("date") == null ? null : rs.getDate("date").toLocalDate(); }
}
