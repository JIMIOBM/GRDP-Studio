package com.grdp.studio.reservoirloss.service;

import com.grdp.studio.reservoirloss.dto.GeologicalLossDtos.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** 储气库地质损耗的事务化存储；记录直接关联项目和储气库。 */
@Service
public class GeologicalLossStorageService {
    private final JdbcTemplate jdbc;

    public GeologicalLossStorageService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public RecordLists listRecords(long projectId, long gasReservoirId, long storageId) {
        StorageCatalogService.requireScope(jdbc, projectId, gasReservoirId, storageId);
        return new RecordLists(listMicroscopic(projectId, gasReservoirId, storageId), listEscape(projectId, gasReservoirId, storageId));
    }

    public List<RecordSummary> listMicroscopic(long projectId, long gasReservoirId, long storageId) {
        StorageCatalogService.requireScope(jdbc, projectId, gasReservoirId, storageId);
        return jdbc.query("""
                SELECT id,record_no,record_name,updated_at
                FROM project_reservoir_microscopic_loss WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?
                ORDER BY record_no
                """, (rs, row) -> record(rs, "microscopic"), projectId, gasReservoirId, storageId);
    }

    public List<RecordSummary> listEscape(long projectId, long gasReservoirId, long storageId) {
        StorageCatalogService.requireScope(jdbc, projectId, gasReservoirId, storageId);
        return jdbc.query("""
                SELECT id,record_no,record_name,updated_at
                FROM project_reservoir_escape_loss WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?
                ORDER BY record_no
                """, (rs, row) -> record(rs, "escape"), projectId, gasReservoirId, storageId);
    }

    public MicroscopicDetail microscopicDetail(long id, long projectId, long gasReservoirId, long storageId) {
        StorageCatalogService.requireScope(jdbc, projectId, gasReservoirId, storageId);
        try {
            return jdbc.queryForObject("""
                    SELECT id,record_no,record_name,updated_at,pore_volume,
                      previous_residual_saturation,current_residual_saturation,lower_limit_pressure,
                      formation_temperature,gas_type,specific_gravity,h2s_mole_fraction,
                      co2_mole_fraction,n2_mole_fraction,modification_method,deviation_factor_method,
                      viscosity_method,imported_file_name,volume_factor_toolbox_id,volume_factor,
                      microscopic_loss_volume
                    FROM project_reservoir_microscopic_loss
                    WHERE id=? AND project_id=? AND gas_reservoir_id=? AND storage_id=?
                    """, (rs, row) -> new MicroscopicDetail(record(rs, "microscopic"),
                    new MicroscopicInput(rs.getDouble(5), rs.getDouble(6), rs.getDouble(7),
                            rs.getDouble(8), rs.getDouble(9), rs.getInt(10), rs.getDouble(11),
                            rs.getDouble(12), rs.getDouble(13), rs.getDouble(14), rs.getInt(15),
                            rs.getInt(16), rs.getInt(17), rs.getString(18)),
                    new MicroscopicCalculation(rs.getLong(19), rs.getDouble(20), rs.getDouble(21))),
                    id, projectId, gasReservoirId, storageId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前储气库下不存在该微观损耗记录");
        }
    }

    public EscapeDetail escapeDetail(long id, long projectId, long gasReservoirId, long storageId) {
        StorageCatalogService.requireScope(jdbc, projectId, gasReservoirId, storageId);
        try {
            return jdbc.queryForObject("""
                    SELECT id,record_no,record_name,updated_at,
                      previous_cushion_gas_volume,movable_cushion_gas_volume,unused_inventory_volume,
                      injection_volume,predicted_change_rate,actual_change_rate,escape_loss_volume
                    FROM project_reservoir_escape_loss
                    WHERE id=? AND project_id=? AND gas_reservoir_id=? AND storage_id=?
                    """, (rs, row) -> new EscapeDetail(record(rs, "escape"),
                    new EscapeInput(rs.getDouble(5), rs.getDouble(6), rs.getDouble(7),
                            rs.getDouble(8), rs.getDouble(9)),
                    new EscapeCalculation(rs.getDouble(10), rs.getDouble(11))),
                    id, projectId, gasReservoirId, storageId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前储气库下不存在该逸散性损耗记录");
        }
    }

    @Transactional
    public synchronized RecordSummary saveMicroscopic(MicroscopicSaveRequest request) {
        StorageCatalogService.lockScope(jdbc, request.projectId(), request.gasReservoirId(), request.storageId());
        // 地质保存检查提交结果的有效性，不像井筒/地面保存入口那样重新调用完整计算流程。
        validateMicroscopicCalculation(request.calculation());
        if (request.recordId() != null) {
            // 覆盖前按完整归属读取旧记录；有recordId表示覆盖，不重新分配显示编号。
            microscopicDetail(request.recordId(), request.projectId(), request.gasReservoirId(), request.storageId());
            MicroscopicInput i = request.input(); MicroscopicCalculation c = request.calculation();
            jdbc.update("""
                    UPDATE project_reservoir_microscopic_loss SET pore_volume=?,previous_residual_saturation=?,
                      current_residual_saturation=?,lower_limit_pressure=?,formation_temperature=?,gas_type=?,
                      specific_gravity=?,h2s_mole_fraction=?,co2_mole_fraction=?,n2_mole_fraction=?,
                      modification_method=?,deviation_factor_method=?,viscosity_method=?,imported_file_name=?,
                      volume_factor_toolbox_id=?,volume_factor=?,microscopic_loss_volume=? WHERE id=?
                    """, i.poreVolume(), i.previousResidualSaturation(), i.currentResidualSaturation(),
                    i.lowerLimitPressure(), i.formationTemperature(), i.gasType(), i.specificGravity(),
                    i.h2SMoleFraction(), i.co2MoleFraction(), i.n2MoleFraction(), i.modificationMethod(),
                    i.deviationFactorMethod(), i.viscosityMethod(), i.importedFileName(),
                    c.volumeFactorToolboxId(), c.volumeFactor(), c.microscopicLossVolume(), request.recordId());
            return microscopicDetail(request.recordId(), request.projectId(), request.gasReservoirId(), request.storageId()).summary();
        }
        int no = allocateNumber("project_reservoir_microscopic_loss", request.projectId(), request.gasReservoirId(), request.storageId());
        MicroscopicInput i = request.input(); MicroscopicCalculation c = request.calculation();
        long id = insertAndReturnKey("""
                INSERT INTO project_reservoir_microscopic_loss
                (project_id,gas_reservoir_id,storage_id,record_no,record_name,pore_volume,
                 previous_residual_saturation,current_residual_saturation,lower_limit_pressure,
                 formation_temperature,gas_type,specific_gravity,h2s_mole_fraction,co2_mole_fraction,
                 n2_mole_fraction,modification_method,deviation_factor_method,viscosity_method,
                 imported_file_name,volume_factor_toolbox_id,volume_factor,microscopic_loss_volume)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, request.projectId(), request.gasReservoirId(), request.storageId(), no, "微观损耗" + no,
                i.poreVolume(), i.previousResidualSaturation(), i.currentResidualSaturation(),
                i.lowerLimitPressure(), i.formationTemperature(), i.gasType(), i.specificGravity(),
                i.h2SMoleFraction(), i.co2MoleFraction(), i.n2MoleFraction(), i.modificationMethod(),
                i.deviationFactorMethod(), i.viscosityMethod(), i.importedFileName(),
                c.volumeFactorToolboxId(), c.volumeFactor(), c.microscopicLossVolume());
        return microscopicDetail(id, request.projectId(), request.gasReservoirId(), request.storageId()).summary();
    }

    @Transactional
    public synchronized RecordSummary saveEscape(EscapeSaveRequest request) {
        StorageCatalogService.lockScope(jdbc, request.projectId(), request.gasReservoirId(), request.storageId());
        // 保存已计算的变化率与损耗量快照；此处仅检查结果是否为有限数值。
        validateEscapeCalculation(request.calculation());
        if (request.recordId() != null) {
            escapeDetail(request.recordId(), request.projectId(), request.gasReservoirId(), request.storageId());
            EscapeInput i = request.input(); EscapeCalculation c = request.calculation();
            jdbc.update("""
                    UPDATE project_reservoir_escape_loss SET previous_cushion_gas_volume=?,
                      movable_cushion_gas_volume=?,unused_inventory_volume=?,injection_volume=?,
                      predicted_change_rate=?,actual_change_rate=?,escape_loss_volume=? WHERE id=?
                    """, i.previousCushionGasVolume(), i.movableCushionGasVolume(),
                    i.unusedInventoryVolume(), i.injectionVolume(), i.predictedChangeRate(),
                    c.actualChangeRate(), c.escapeLossVolume(), request.recordId());
            return escapeDetail(request.recordId(), request.projectId(), request.gasReservoirId(), request.storageId()).summary();
        }
        int no = allocateNumber("project_reservoir_escape_loss", request.projectId(), request.gasReservoirId(), request.storageId());
        EscapeInput i = request.input(); EscapeCalculation c = request.calculation();
        long id = insertAndReturnKey("""
                INSERT INTO project_reservoir_escape_loss
                (project_id,gas_reservoir_id,storage_id,record_no,record_name,previous_cushion_gas_volume,
                 movable_cushion_gas_volume,unused_inventory_volume,injection_volume,predicted_change_rate,
                 actual_change_rate,escape_loss_volume) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, request.projectId(), request.gasReservoirId(), request.storageId(), no, "逸散性损耗" + no,
                i.previousCushionGasVolume(), i.movableCushionGasVolume(), i.unusedInventoryVolume(),
                i.injectionVolume(), i.predictedChangeRate(), c.actualChangeRate(), c.escapeLossVolume());
        return escapeDetail(id, request.projectId(), request.gasReservoirId(), request.storageId()).summary();
    }

    public RecordSummary renameRecord(String type, long id, long projectId, long gasReservoirId, long storageId, RenameRequest request) {
        StorageCatalogService.requireScope(jdbc, projectId, gasReservoirId, storageId);
        String table = table(type);
        int changed = jdbc.update("UPDATE " + table + " SET record_name=? WHERE id=? AND project_id=? AND gas_reservoir_id=? AND storage_id=?", request.name().trim(), id, projectId, gasReservoirId, storageId);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "损耗记录不存在");
        return jdbc.queryForObject("SELECT id,record_no,record_name,updated_at FROM " + table + " WHERE id=?",
                (rs, row) -> record(rs, type), id);
    }

    public void deleteRecord(String type, long id, long projectId, long gasReservoirId, long storageId) {
        StorageCatalogService.requireScope(jdbc, projectId, gasReservoirId, storageId);
        int changed = jdbc.update("DELETE FROM " + table(type) + " WHERE id=? AND project_id=? AND gas_reservoir_id=? AND storage_id=?",
                id, projectId, gasReservoirId, storageId);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "损耗记录不存在");
    }

    private int allocateNumber(String table, long projectId, long gasReservoirId, long storageId) {
        // 仅在持有库行锁的保存事务内调用；按当前库、当前损耗类型取MAX+1，删除最大编号后可能复用。
        Integer no = jdbc.queryForObject("SELECT COALESCE(MAX(record_no),0)+1 FROM " + table
                + " WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?", Integer.class, projectId, gasReservoirId, storageId);
        return no == null ? 1 : no;
    }

    private static void validateMicroscopicCalculation(MicroscopicCalculation c) {
        if (c.volumeFactorToolboxId() <= 0 || !Double.isFinite(c.volumeFactor()) || c.volumeFactor() <= 0
                || !Double.isFinite(c.microscopicLossVolume()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先完成微观损耗计算");
    }
    private static void validateEscapeCalculation(EscapeCalculation c) {
        if (!Double.isFinite(c.actualChangeRate()) || !Double.isFinite(c.escapeLossVolume()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先完成逸散性损耗计算");
    }
    private static String table(String type) {
        // 表名不能通过SQL占位符绑定，只能从固定白名单映射，不能直接拼接请求中的type。
        return switch (type) {
            case "microscopic" -> "project_reservoir_microscopic_loss";
            case "escape" -> "project_reservoir_escape_loss";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "损耗类型不正确");
        };
    }
    private static RecordSummary record(ResultSet rs, String type) throws SQLException {
        return new RecordSummary(rs.getLong(1), rs.getInt(2), rs.getString(3),
                type, rs.getTimestamp(4).toLocalDateTime());
    }
    private long insertAndReturnKey(String sql, Object... args) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("数据库未返回新增记录ID");
        return key.longValue();
    }
}
