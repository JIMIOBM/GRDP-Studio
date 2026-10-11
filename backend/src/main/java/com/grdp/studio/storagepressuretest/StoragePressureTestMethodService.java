package com.grdp.studio.storagepressuretest;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Stores the editable well pressure-test table independently from pressure-gradient points. */
@Service
public class StoragePressureTestMethodService {
    public record Point(String id, String sourceKey, String wellName, LocalDate date,
                        Double measuredPressure, boolean manual, boolean deleted) {}
    public record Dataset(List<Point> rows) {}
    public record SaveRequest(List<Point> rows) {}

    private final JdbcTemplate jdbc;
    private final StorageCatalogService catalog;

    public StoragePressureTestMethodService(JdbcTemplate jdbc, StorageCatalogService catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    @Transactional
    public Dataset list(long projectId, long gasReservoirId, long storageId) {
        StorageCatalogService.lockScope(jdbc, projectId, gasReservoirId, storageId);
        migrateLegacyPressureRows(projectId, gasReservoirId, storageId);
        Map<Long, String> wells = catalog.wells(storageId, projectId, gasReservoirId).stream()
                .collect(Collectors.toMap(StorageCatalogService.Well::id, StorageCatalogService.Well::wellName));
        List<Point> rows = jdbc.query("""
                SELECT row_key,source_key,well_id,point_date,measured_pressure_mpa,is_manual,is_deleted
                FROM storage_pressure_test_method_point
                WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?
                ORDER BY point_date,well_id,row_key
                """, (rs, n) -> {
            String wellName = wells.get(rs.getLong("well_id"));
            if (wellName == null) return null;
            java.sql.Date date = rs.getDate("point_date");
            return new Point(rs.getString("row_key"), rs.getString("source_key"), wellName,
                    date == null ? null : date.toLocalDate(),
                    rs.getObject("measured_pressure_mpa", Double.class),
                    rs.getBoolean("is_manual"), rs.getBoolean("is_deleted"));
        }, projectId, gasReservoirId, storageId).stream().filter(Objects::nonNull).toList();
        return new Dataset(rows);
    }

    /** Copy old shared-table pressure readings once, keeping the source gradient rows intact. */
    private void migrateLegacyPressureRows(long projectId, long gasReservoirId, long storageId) {
        Integer migrated = jdbc.queryForObject("""
                SELECT COUNT(*) FROM storage_pressure_test_method_migration
                WHERE project_id=? AND gas_reservoir_id=? AND storage_id=? AND migration_key='legacy-gradient-pressure-v1'
                """, Integer.class, projectId, gasReservoirId, storageId);
        if (migrated != null && migrated > 0) return;

        jdbc.update("""
                INSERT INTO storage_pressure_test_method_point
                  (project_id,gas_reservoir_id,storage_id,well_id,row_key,source_key,point_date,
                   measured_pressure_mpa,is_manual,is_deleted)
                SELECT g.project_id,g.gas_reservoir_id,g.storage_id,g.well_id,g.row_key,g.source_key,g.point_date,
                       g.measured_pressure_mpa,g.is_manual,g.is_deleted
                FROM storage_pressure_gradient_point g
                WHERE g.project_id=? AND g.gas_reservoir_id=? AND g.storage_id=?
                  AND g.measured_pressure_mpa IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM storage_pressure_test_method_point p
                    WHERE p.storage_id=g.storage_id
                      AND (p.row_key=g.row_key OR (g.source_key IS NOT NULL AND p.source_key=g.source_key))
                  )
                """, projectId, gasReservoirId, storageId);
        jdbc.update("""
                INSERT INTO storage_pressure_test_method_migration
                  (project_id,gas_reservoir_id,storage_id,migration_key)
                VALUES (?,?,?,'legacy-gradient-pressure-v1')
                """, projectId, gasReservoirId, storageId);
    }

    @Transactional
    public Dataset save(long projectId, long gasReservoirId, long storageId, SaveRequest request) {
        StorageCatalogService.lockScope(jdbc, projectId, gasReservoirId, storageId);
        if (request == null || request.rows() == null)
            throw new BusinessException(400, "保存请求缺少测点列表");
        List<Point> rows = request.rows();
        if (rows.size() > 20000) throw new BusinessException(400, "单个储气库最多保存20000个测试法测点");
        Map<String, Long> wells = catalog.wells(storageId, projectId, gasReservoirId).stream()
                .collect(Collectors.toMap(StorageCatalogService.Well::wellName, StorageCatalogService.Well::id));
        Set<String> ids = new HashSet<>();
        for (Point row : rows) {
            if (row == null || row.id() == null || row.id().isBlank() || row.id().length() > 255
                    || !ids.add(row.id())) throw new BusinessException(400, "测点标识缺失或重复");
            if (row.sourceKey() != null && row.sourceKey().length() > 255)
                throw new BusinessException(400, "实测来源标识超出长度限制");
            if (row.wellName() == null || !wells.containsKey(row.wellName()))
                throw new BusinessException(400, "测点井号不属于当前储气库");
            if (row.measuredPressure() != null
                    && (!Double.isFinite(row.measuredPressure()) || row.measuredPressure() < 0))
                throw new BusinessException(400, "实测静压必须是非负有效数字");
        }

        jdbc.update("DELETE FROM storage_pressure_test_method_point WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?",
                projectId, gasReservoirId, storageId);
        if (!rows.isEmpty()) jdbc.batchUpdate("""
                INSERT INTO storage_pressure_test_method_point
                  (project_id,gas_reservoir_id,storage_id,well_id,row_key,source_key,point_date,
                   measured_pressure_mpa,is_manual,is_deleted)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, rows, 200, (ps, row) -> {
            ps.setLong(1, projectId);
            ps.setLong(2, gasReservoirId);
            ps.setLong(3, storageId);
            ps.setLong(4, wells.get(row.wellName()));
            ps.setString(5, row.id());
            if (row.sourceKey() == null) ps.setNull(6, Types.VARCHAR); else ps.setString(6, row.sourceKey());
            if (row.date() == null) ps.setNull(7, Types.DATE); else ps.setObject(7, row.date());
            if (row.measuredPressure() == null) ps.setNull(8, Types.DOUBLE); else ps.setDouble(8, row.measuredPressure());
            ps.setBoolean(9, row.manual());
            ps.setBoolean(10, row.deleted());
        });
        return new Dataset(rows);
    }
}
