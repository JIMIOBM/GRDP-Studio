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
import java.sql.Statement;
import java.util.List;

/** 储气库地质损耗的事务化存储；记录直接关联项目和储气库。 */
@Service
public class GeologicalLossStorageService {
    private final JdbcTemplate jdbc;

    public GeologicalLossStorageService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public RecordLists listRecords(long projectId, long gasReservoirId) {
        return new RecordLists(listMicroscopic(projectId, gasReservoirId), listEscape(projectId, gasReservoirId));
    }

    public List<RecordSummary> listMicroscopic(long projectId, long gasReservoirId) {
        return jdbc.query("""
                SELECT id,record_no,record_name,updated_at
                FROM project_reservoir_microscopic_loss WHERE project_id=? AND gas_reservoir_id=?
                ORDER BY record_no
                """, (rs, row) -> record(rs, "microscopic"), projectId, gasReservoirId);
    }

    public List<RecordSummary> listEscape(long projectId, long gasReservoirId) {
        return jdbc.query("""
                SELECT id,record_no,record_name,updated_at
                FROM project_reservoir_escape_loss WHERE project_id=? AND gas_reservoir_id=?
                ORDER BY record_no
                """, (rs, row) -> record(rs, "escape"), projectId, gasReservoirId);
    }

    public MicroscopicDetail microscopicDetail(long id, long projectId, long gasReservoirId) {
        try {
            return jdbc.queryForObject("""
                    SELECT id,record_no,record_name,updated_at,pore_volume,
                      previous_residual_saturation,current_residual_saturation,lower_limit_pressure,
                      formation_temperature,gas_type,specific_gravity,h2s_mole_fraction,
                      co2_mole_fraction,n2_mole_fraction,modification_method,deviation_factor_method,
                      viscosity_method,imported_file_name,volume_factor_toolbox_id,volume_factor,
                      microscopic_loss_volume
                    FROM project_reservoir_microscopic_loss
                    WHERE id=? AND project_id=? AND gas_reservoir_id=?
                    """, (rs, row) -> new MicroscopicDetail(record(rs, "microscopic"),
                    new MicroscopicInput(rs.getDouble(5), rs.getDouble(6), rs.getDouble(7),
                            rs.getDouble(8), rs.getDouble(9), rs.getInt(10), rs.getDouble(11),
                            rs.getDouble(12), rs.getDouble(13), rs.getDouble(14), rs.getInt(15),
                            rs.getInt(16), rs.getInt(17), rs.getString(18)),
                    new MicroscopicCalculation(rs.getLong(19), rs.getDouble(20), rs.getDouble(21))),
                    id, projectId, gasReservoirId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前储气库下不存在该微观损耗记录");
        }
    }

    public EscapeDetail escapeDetail(long id, long projectId, long gasReservoirId) {
        try {
            return jdbc.queryForObject("""
                    SELECT id,record_no,record_name,updated_at,
                      previous_cushion_gas_volume,movable_cushion_gas_volume,unused_inventory_volume,
                      injection_volume,predicted_change_rate,actual_change_rate,escape_loss_volume
                    FROM project_reservoir_escape_loss
                    WHERE id=? AND project_id=? AND gas_reservoir_id=?
                    """, (rs, row) -> new EscapeDetail(record(rs, "escape"),
                    new EscapeInput(rs.getDouble(5), rs.getDouble(6), rs.getDouble(7),
                            rs.getDouble(8), rs.getDouble(9)),
                    new EscapeCalculation(rs.getDouble(10), rs.getDouble(11))),
                    id, projectId, gasReservoirId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前储气库下不存在该逸散性损耗记录");
        }
    }

    @Transactional
    public synchronized RecordSummary saveMicroscopic(MicroscopicSaveRequest request) {
        validateMicroscopicCalculation(request.calculation());
        if (request.recordId() != null) {
            microscopicDetail(request.recordId(), request.projectId(), request.gasReservoirId());
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
            return microscopicDetail(request.recordId(), request.projectId(), request.gasReservoirId()).summary();
        }
        int no = allocateNumber("project_reservoir_microscopic_loss", request.projectId(), request.gasReservoirId());
        MicroscopicInput i = request.input(); MicroscopicCalculation c = request.calculation();
        long id = insertAndReturnKey("""
                INSERT INTO project_reservoir_microscopic_loss
                (project_id,gas_reservoir_id,record_no,record_name,pore_volume,
                 previous_residual_saturation,current_residual_saturation,lower_limit_pressure,
                 formation_temperature,gas_type,specific_gravity,h2s_mole_fraction,co2_mole_fraction,
                 n2_mole_fraction,modification_method,deviation_factor_method,viscosity_method,
                 imported_file_name,volume_factor_toolbox_id,volume_factor,microscopic_loss_volume)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, request.projectId(), request.gasReservoirId(), no, "微观损耗" + no,
                i.poreVolume(), i.previousResidualSaturation(), i.currentResidualSaturation(),
                i.lowerLimitPressure(), i.formationTemperature(), i.gasType(), i.specificGravity(),
                i.h2SMoleFraction(), i.co2MoleFraction(), i.n2MoleFraction(), i.modificationMethod(),
                i.deviationFactorMethod(), i.viscosityMethod(), i.importedFileName(),
                c.volumeFactorToolboxId(), c.volumeFactor(), c.microscopicLossVolume());
        return microscopicDetail(id, request.projectId(), request.gasReservoirId()).summary();
    }

    @Transactional
    public synchronized RecordSummary saveEscape(EscapeSaveRequest request) {
        validateEscapeCalculation(request.calculation());
        if (request.recordId() != null) {
            escapeDetail(request.recordId(), request.projectId(), request.gasReservoirId());
            EscapeInput i = request.input(); EscapeCalculation c = request.calculation();
            jdbc.update("""
                    UPDATE project_reservoir_escape_loss SET previous_cushion_gas_volume=?,
                      movable_cushion_gas_volume=?,unused_inventory_volume=?,injection_volume=?,
                      predicted_change_rate=?,actual_change_rate=?,escape_loss_volume=? WHERE id=?
                    """, i.previousCushionGasVolume(), i.movableCushionGasVolume(),
                    i.unusedInventoryVolume(), i.injectionVolume(), i.predictedChangeRate(),
                    c.actualChangeRate(), c.escapeLossVolume(), request.recordId());
            return escapeDetail(request.recordId(), request.projectId(), request.gasReservoirId()).summary();
        }
        int no = allocateNumber("project_reservoir_escape_loss", request.projectId(), request.gasReservoirId());
        EscapeInput i = request.input(); EscapeCalculation c = request.calculation();
        long id = insertAndReturnKey("""
                INSERT INTO project_reservoir_escape_loss
                (project_id,gas_reservoir_id,record_no,record_name,previous_cushion_gas_volume,
                 movable_cushion_gas_volume,unused_inventory_volume,injection_volume,predicted_change_rate,
                 actual_change_rate,escape_loss_volume) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, request.projectId(), request.gasReservoirId(), no, "逸散性损耗" + no,
                i.previousCushionGasVolume(), i.movableCushionGasVolume(), i.unusedInventoryVolume(),
                i.injectionVolume(), i.predictedChangeRate(), c.actualChangeRate(), c.escapeLossVolume());
        return escapeDetail(id, request.projectId(), request.gasReservoirId()).summary();
    }

    public RecordSummary renameRecord(String type, long id, RenameRequest request) {
        String table = table(type);
        int changed = jdbc.update("UPDATE " + table + " SET record_name=? WHERE id=?", request.name().trim(), id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "损耗记录不存在");
        return jdbc.queryForObject("SELECT id,record_no,record_name,updated_at FROM " + table + " WHERE id=?",
                (rs, row) -> record(rs, type), id);
    }

    public void deleteRecord(String type, long id, long projectId, long gasReservoirId) {
        int changed = jdbc.update("DELETE FROM " + table(type) + " WHERE id=? AND project_id=? AND gas_reservoir_id=?",
                id, projectId, gasReservoirId);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "损耗记录不存在");
    }

    private int allocateNumber(String table, long projectId, long gasReservoirId) {
        Integer no = jdbc.queryForObject("SELECT COALESCE(MAX(record_no),0)+1 FROM " + table
                + " WHERE project_id=? AND gas_reservoir_id=?", Integer.class, projectId, gasReservoirId);
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
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("数据库未返回新增记录ID");
        return key.longValue();
    }
}
