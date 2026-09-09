package com.grdp.studio.productivitycomparison;

import com.grdp.studio.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Only reads saved coefficients and their input snapshots; never fits or creates records. */
@Repository
public class ComparisonRepository {
    static final Map<String, String> METHODS = Map.of("back-pressure", "回压试井", "isochronal", "等时试井",
            "modified-isochronal", "修正等时", "one-point", "一点法", "stable", "理论稳定流", "unstable", "理论不稳定流");
    record Saved(String method, long id, String name, LocalDate date, Double a, Double b, PvtSnapshot pvt,
                 String operationType) {}
    public record PvtSnapshot(String gasType, Double specificGravity, Double hydrogenSulfide,
            Double carbonDioxide, Double nitrogen, String modificationMethod,
            String deviationFactorMethod, String viscosityMethod, Double temperature, Double originalPressure) {}
    private final JdbcTemplate jdbc;
    public ComparisonRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void requireWell(long projectId, long reservoirId, String wellName) {
        if (projectId <= 0 || reservoirId <= 0 || wellName == null || wellName.isBlank())
            throw new BusinessException(400, "请先选择当前项目、气藏和井");
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_well_heads
                WHERE project_id=? AND project_gas_reservoir_id=? AND well_name=?
                """, Integer.class, projectId, reservoirId, wellName.trim());
        if (count == null || count != 1) throw new BusinessException(400, "当前项目和气藏下未找到唯一的井记录");
    }

    public List<Saved> load(long projectId, long reservoirId, String wellName, String method, String pressureMethod) {
        return load(projectId, reservoirId, wellName, method, pressureMethod, "production");
    }
    public List<Saved> load(long projectId, long reservoirId, String wellName, String method, String pressureMethod, String operationType) {
        if (!METHODS.containsKey(method)) throw new BusinessException(400, "不支持的产能对比方法");
        if (!List.of("production", "injection").contains(operationType)) throw new BusinessException(400, "不支持的注采类型");
        boolean theory = method.equals("stable") || method.equals("unstable");
        String common = "o.darcy_seepage_coefficient AS coefficient_a,o.non_darcy_seepage_coefficient AS coefficient_b,"
                + "i.gas_type,i.specific_gravity,i.hydrogen_sulfide,i.carbon_dioxide,i.nitrogen,"
                + "i.modification_method,i.deviation_factor_method,i.viscosity_method,i.formation_temperature,";
        String sql;
        Object[] args;
        if (theory) {
            // Table identifiers come exclusively from the two allowlisted constants above.
            String prefix = "project_well_theoretical_" + method;
            sql = "SELECT t.id AS record_id,t." + method + "_name AS record_name,"
                    + "CAST(o.calculated_at AS DATE) AS record_date," + common
                    + "i.original_formation_pressure AS original_pressure FROM " + prefix + "_calculation t "
                    + "JOIN project_well_theoretical_productivity d ON d.id=t.theoretical_productivity_id "
                    + "JOIN project_well_heads w ON w.id=d.well_id "
                    + "JOIN " + prefix + "_operation op ON op." + method + "_calculation_id=t.id "
                    + "JOIN " + prefix + "_output o ON o.operation_id=op.id "
                    + "LEFT JOIN " + prefix + "_input i ON i.operation_id=op.id "
                    + "WHERE w.project_id=? AND w.project_gas_reservoir_id=? AND w.well_name=? "
                    + "AND op.operation_type=? AND o.pressure_method=? ORDER BY record_date,t.id";
            args = new Object[]{projectId, reservoirId, wellName.trim(), operationType, pressureMethod.replace('-', '_')};
        } else {
            sql = "SELECT t.id AS record_id,t.test_name AS record_name,CAST(o.calculated_at AS DATE) AS record_date," + common
                    + "i.maximum_formation_pressure AS original_pressure FROM project_well_productivity_test t "
                    + "JOIN project_well_productivity_binomial_output o ON o.test_id=t.id "
                    + "LEFT JOIN project_well_productivity_test_input i ON i.test_id=t.id "
                    + "WHERE t.project_id=? AND t.project_gas_reservoir_id=? AND t.well_name=? "
                    + "AND t.operation_type=? AND t.test_method=? AND o.pressure_method=? ORDER BY record_date,t.id";
            args = new Object[]{projectId, reservoirId, wellName.trim(), operationType, method, pressureMethod};
        }
        return jdbc.query(sql, (rs, row) -> new Saved(method, rs.getLong("record_id"), rs.getString("record_name"),
                rs.getDate("record_date") == null ? null : rs.getDate("record_date").toLocalDate(),
                decimal(rs, "coefficient_a"), decimal(rs, "coefficient_b"),
                new PvtSnapshot(rs.getString("gas_type"), decimal(rs, "specific_gravity"), decimal(rs, "hydrogen_sulfide"),
                        decimal(rs, "carbon_dioxide"), decimal(rs, "nitrogen"), rs.getString("modification_method"),
                        rs.getString("deviation_factor_method"), rs.getString("viscosity_method"),
                        decimal(rs, "formation_temperature"), decimal(rs, "original_pressure")), operationType), args);
    }
    private static Double decimal(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
