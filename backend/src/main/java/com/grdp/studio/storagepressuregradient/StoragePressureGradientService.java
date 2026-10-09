package com.grdp.studio.storagepressuregradient;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Types;
import java.time.LocalDate;
import java.util.*;

/** 保存当前储气库压力梯度页的测点与用户补录参数，不修改智慧气藏源数据。 */
@Service
public class StoragePressureGradientService {
    public record Point(String id, String sourceKey, String wellName, LocalDate date,
                        Double measuredPressure, Double measuredCoordinate, Double gradient,
                        boolean manual, boolean deleted) {}
    public record Dataset(List<Point> rows, Double referenceCoordinate, String coordinateMode) {}
    public record SaveRequest(List<Point> rows, Double referenceCoordinate, String coordinateMode) {}
    private record CalculationSettings(Double referenceCoordinate, String coordinateMode) {}

    private final JdbcTemplate jdbc;
    private final StorageCatalogService catalog;

    public StoragePressureGradientService(JdbcTemplate jdbc, StorageCatalogService catalog) {
        this.jdbc = jdbc; this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public Dataset list(long projectId, long gasReservoirId, long storageId) {
        catalog.requireScope(projectId, gasReservoirId, storageId);
        var wells = catalog.wells(storageId, projectId, gasReservoirId).stream()
                .collect(java.util.stream.Collectors.toMap(StorageCatalogService.Well::id,
                        StorageCatalogService.Well::wellName));
        var rows = jdbc.query("""
                SELECT row_key,source_key,well_id,point_date,measured_pressure_mpa,measured_coordinate_m,gradient_mpa_per_m,is_manual,is_deleted
                FROM storage_pressure_gradient_point
                WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?
                ORDER BY point_date,well_id,row_key
                """, (rs, n) -> {
            long wellId = rs.getLong("well_id");
            String wellName = wells.get(wellId);
            if (wellName == null) return null;
            java.sql.Date date = rs.getDate("point_date");
            return new Point(rs.getString("row_key"), rs.getString("source_key"), wellName,
                    date == null ? null : date.toLocalDate(), rs.getObject("measured_pressure_mpa", Double.class),
                    rs.getObject("measured_coordinate_m", Double.class), rs.getObject("gradient_mpa_per_m", Double.class),
                    rs.getBoolean("is_manual"), rs.getBoolean("is_deleted"));
        }, projectId, gasReservoirId, storageId).stream().filter(Objects::nonNull).toList();
        CalculationSettings settings = readSettings(projectId, gasReservoirId, storageId);
        return new Dataset(rows, settings.referenceCoordinate(), settings.coordinateMode());
    }

    private CalculationSettings readSettings(long projectId, long gasReservoirId, long storageId) {
        var settings = jdbc.query("""
                SELECT reference_coordinate_m,coordinate_mode FROM storage_pressure_gradient_config
                WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?
                """, (rs, n) -> new Object[]{rs.getDouble(1), rs.getString(2)}, projectId, gasReservoirId, storageId);
        return settings.isEmpty() ? new CalculationSettings(2000d, "depth")
                : new CalculationSettings((Double) settings.getFirst()[0], String.valueOf(settings.getFirst()[1]));
    }

    @Transactional
    public Dataset save(long projectId, long gasReservoirId, long storageId, SaveRequest request) {
        StorageCatalogService.lockScope(jdbc, projectId, gasReservoirId, storageId);
        if (request == null || request.rows() == null) throw new BusinessException(400, "保存请求缺少测点列表");
        List<Point> rows = request.rows();
        if ((request.referenceCoordinate() == null) != (request.coordinateMode() == null))
            throw new BusinessException(400, "统一基准坐标和坐标类型必须同时提供");
        if (request.referenceCoordinate() != null && (!valid(request.referenceCoordinate(), null)
                || (!"depth".equals(request.coordinateMode()) && !"elevation".equals(request.coordinateMode()))))
            throw new BusinessException(400, "统一基准坐标或坐标类型无效");
        String coordinateMode = request.coordinateMode() != null
                ? request.coordinateMode() : readSettings(projectId, gasReservoirId, storageId).coordinateMode();
        if (rows.size() > 20000) throw new BusinessException(400, "单个储气库最多保存20000个压力测点");
        var wells = catalog.wells(storageId, projectId, gasReservoirId).stream()
                .collect(java.util.stream.Collectors.toMap(StorageCatalogService.Well::wellName,
                        StorageCatalogService.Well::id));
        Set<String> ids = new HashSet<>();
        for (Point row : rows) {
            if (row == null || row.id() == null || row.id().isBlank() || row.id().length() > 255
                    || !ids.add(row.id())) throw new BusinessException(400, "压力测点标识缺失或重复");
            if (row.sourceKey() != null && row.sourceKey().length() > 255)
                throw new BusinessException(400, "实测来源标识超出长度限制");
            if (row.wellName() == null || !wells.containsKey(row.wellName()))
                throw new BusinessException(400, "测点井号不属于当前储气库");
            if (!valid(row.measuredPressure(), 0) || !valid(row.measuredCoordinate(), null)
                    || !valid(row.gradient(), 0)
                    || ("depth".equals(coordinateMode) && row.measuredCoordinate() != null && row.measuredCoordinate() < 0))
                throw new BusinessException(400, "压力、坐标或压力梯度包含无效数值");
        }
        jdbc.update("DELETE FROM storage_pressure_gradient_point WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?",
                projectId, gasReservoirId, storageId);
        if (request.referenceCoordinate() != null) jdbc.update("""
                INSERT INTO storage_pressure_gradient_config(project_id,gas_reservoir_id,storage_id,reference_coordinate_m,coordinate_mode)
                VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE reference_coordinate_m=VALUES(reference_coordinate_m),coordinate_mode=VALUES(coordinate_mode)
                """, projectId, gasReservoirId, storageId, request.referenceCoordinate(), request.coordinateMode());
        if (!rows.isEmpty()) jdbc.batchUpdate("""
                INSERT INTO storage_pressure_gradient_point
                  (project_id,gas_reservoir_id,storage_id,well_id,row_key,source_key,point_date,
                   measured_pressure_mpa,measured_coordinate_m,gradient_mpa_per_m,is_manual,is_deleted)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, rows, 200, (ps, row) -> {
            ps.setLong(1, projectId); ps.setLong(2, gasReservoirId); ps.setLong(3, storageId);
            ps.setLong(4, wells.get(row.wellName())); ps.setString(5, row.id());
            if (row.sourceKey() == null) ps.setNull(6, Types.VARCHAR); else ps.setString(6, row.sourceKey());
            if (row.date() == null) ps.setNull(7, Types.DATE); else ps.setObject(7, row.date());
            setDouble(ps, 8, row.measuredPressure()); setDouble(ps, 9, row.measuredCoordinate());
            setDouble(ps, 10, row.gradient()); ps.setBoolean(11, row.manual()); ps.setBoolean(12, row.deleted());
        });
        CalculationSettings settings = readSettings(projectId, gasReservoirId, storageId);
        return new Dataset(rows, settings.referenceCoordinate(), settings.coordinateMode());
    }

    private static boolean valid(Double value, Integer minimum) {
        return value == null || Double.isFinite(value) && (minimum == null || value >= minimum);
    }
    private static void setDouble(java.sql.PreparedStatement ps, int index, Double value) throws java.sql.SQLException {
        if (value == null) ps.setNull(index, Types.DOUBLE); else ps.setDouble(index, value);
    }
}
