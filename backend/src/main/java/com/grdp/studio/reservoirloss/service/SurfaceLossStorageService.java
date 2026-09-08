package com.grdp.studio.reservoirloss.service;

import com.grdp.studio.reservoirloss.dto.SurfaceLossDtos.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static com.grdp.studio.reservoirloss.dto.SurfaceLossDtos.DIRECT_MODE;
import static com.grdp.studio.reservoirloss.dto.SurfaceLossDtos.FORMULA_MODE;

/** 地面损耗记录及其放空段明细的事务化存储。 */
@Service
public class SurfaceLossStorageService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public SurfaceLossStorageService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public List<RecordSummary> list(long projectId, long gasReservoirId) {
        return jdbc.query("""
                SELECT id,record_no,record_name,updated_at
                FROM project_reservoir_surface_loss
                WHERE project_id=? AND gas_reservoir_id=? ORDER BY record_no
                """, (rs, row) -> summary(rs), projectId, gasReservoirId);
    }

    public Detail detail(long id, long projectId, long gasReservoirId) {
        try {
            return jdbc.queryForObject("""
                    SELECT id,record_no,record_name,updated_at,calculation_mode,input_loss_volume,
                      average_temperature_k,pressure_before,pressure_after,total_segment_volume,
                      gas_type,specific_gravity,h2s_mole_fraction,co2_mole_fraction,n2_mole_fraction,
                      modification_method,deviation_factor_method,viscosity_method,imported_file_name,
                      deviation_factor_toolbox_id,deviation_factor_before,deviation_factor_after,
                      vent_loss_volume,condensate_volume,gas_oil_ratio,condensate_loss_volume,input_condensate_loss_volume
                    FROM project_reservoir_surface_loss
                    WHERE id=? AND project_id=? AND gas_reservoir_id=?
                    """, (rs, row) -> mapDetail(rs, loadSegments(id)), id, projectId, gasReservoirId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前储气库下不存在该地面损耗记录");
        }
    }

    public synchronized RecordSummary save(SaveRequest request) {
        SaveRequest normalized = new SaveRequest(request.recordId(), request.projectId(),
                request.gasReservoirId(), request.input().normalized(), request.calculation());
        validateCalculation(normalized.input(), normalized.calculation());
        // 事务提交完成后才释放本实例锁；多实例争用编号时由唯一索引兜底并重试。
        for (int attempt = 0; ; attempt++) {
            try {
                return transaction.execute(status -> saveInTransaction(normalized));
            } catch (DuplicateKeyException conflict) {
                if (request.recordId() != null || attempt >= 2) throw conflict;
            }
        }
    }

    private RecordSummary saveInTransaction(SaveRequest request) {
        if (request.recordId() != null) {
            detail(request.recordId(), request.projectId(), request.gasReservoirId());
            updateMain(request.recordId(), request.input(), request.calculation());
            replaceSegments(request.recordId(), request.input());
            return detail(request.recordId(), request.projectId(), request.gasReservoirId()).summary();
        }

        int no = allocateNumber(request.projectId(), request.gasReservoirId());
        long id = insertMain(request, no);
        replaceSegments(id, request.input());
        return detail(id, request.projectId(), request.gasReservoirId()).summary();
    }

    public RecordSummary rename(long id, long projectId, long gasReservoirId, String name) {
        int changed = jdbc.update("UPDATE project_reservoir_surface_loss SET record_name=? WHERE id=? AND project_id=? AND gas_reservoir_id=?",
                name.trim(), id, projectId, gasReservoirId);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "地面损耗记录不存在");
        return jdbc.queryForObject("SELECT id,record_no,record_name,updated_at FROM project_reservoir_surface_loss WHERE id=?",
                (rs, row) -> summary(rs), id);
    }

    public void delete(long id, long projectId, long gasReservoirId) {
        int changed = jdbc.update("""
                DELETE FROM project_reservoir_surface_loss
                WHERE id=? AND project_id=? AND gas_reservoir_id=?
                """, id, projectId, gasReservoirId);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "地面损耗记录不存在");
        // 放空段明细由数据库外键 ON DELETE CASCADE 自动清理。
    }

    private Detail mapDetail(ResultSet rs, List<SegmentInput> segments) throws SQLException {
        String mode = rs.getInt(5) == 0 ? DIRECT_MODE : FORMULA_MODE;
        SurfaceInput input = new SurfaceInput(mode, nullableDouble(rs, 6), nullableDouble(rs, 7),
                nullableDouble(rs, 8), nullableDouble(rs, 9), segments, nullableInteger(rs, 11),
                nullableDouble(rs, 12), nullableDouble(rs, 13), nullableDouble(rs, 14), nullableDouble(rs, 15),
                nullableInteger(rs, 16), nullableInteger(rs, 17), nullableInteger(rs, 18), rs.getString(19),
                nullableDouble(rs, 24), nullableDouble(rs, 25), nullableDouble(rs, 27));
        Calculation calculation = new Calculation(nullableLong(rs, 20), nullableDouble(rs, 21),
                nullableDouble(rs, 22), nullableDouble(rs, 10), rs.getDouble(23), rs.getDouble(26));
        return new Detail(summary(rs), input, calculation);
    }

    private List<SegmentInput> loadSegments(long recordId) {
        return jdbc.query("""
                SELECT segment_volume FROM project_reservoir_surface_loss_segment
                WHERE surface_loss_id=? ORDER BY segment_no
                """, (rs, row) -> new SegmentInput(rs.getDouble(1)), recordId);
    }

    private long insertMain(SaveRequest request, int no) {
        SurfaceInput i = request.input();
        Calculation c = request.calculation();
        return insertAndReturnKey("""
                INSERT INTO project_reservoir_surface_loss
                (project_id,gas_reservoir_id,record_no,record_name,calculation_mode,input_loss_volume,
                 average_temperature_k,pressure_before,pressure_after,total_segment_volume,gas_type,
                 specific_gravity,h2s_mole_fraction,co2_mole_fraction,n2_mole_fraction,
                 modification_method,deviation_factor_method,viscosity_method,imported_file_name,
                 deviation_factor_toolbox_id,deviation_factor_before,deviation_factor_after,vent_loss_volume,
                 condensate_volume,gas_oil_ratio,condensate_loss_volume,input_condensate_loss_volume)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, request.projectId(), request.gasReservoirId(), no, "地面损耗" + no,
                modeNumber(i.calculationMode()), i.inputLossVolume(), i.averageTemperatureK(),
                i.pressureBefore(), i.pressureAfter(), c.totalSegmentVolume(), i.gasType(), i.specificGravity(),
                i.h2SMoleFraction(), i.co2MoleFraction(), i.n2MoleFraction(), i.modificationMethod(),
                i.deviationFactorMethod(), i.viscosityMethod(), i.importedFileName(),
                c.deviationFactorToolboxId(), c.deviationFactorBefore(), c.deviationFactorAfter(),
                c.ventLossVolume(), i.condensateVolume(), i.gasOilRatio(), c.condensateLossVolume(), i.inputCondensateLossVolume());
    }

    private void updateMain(long id, SurfaceInput i, Calculation c) {
        jdbc.update("""
                UPDATE project_reservoir_surface_loss SET calculation_mode=?,input_loss_volume=?,
                  average_temperature_k=?,pressure_before=?,pressure_after=?,total_segment_volume=?,
                  gas_type=?,specific_gravity=?,h2s_mole_fraction=?,co2_mole_fraction=?,n2_mole_fraction=?,
                  modification_method=?,deviation_factor_method=?,viscosity_method=?,imported_file_name=?,
                  deviation_factor_toolbox_id=?,deviation_factor_before=?,deviation_factor_after=?,
                  vent_loss_volume=?,condensate_volume=?,gas_oil_ratio=?,condensate_loss_volume=?,input_condensate_loss_volume=? WHERE id=?
                """, modeNumber(i.calculationMode()), i.inputLossVolume(), i.averageTemperatureK(),
                i.pressureBefore(), i.pressureAfter(), c.totalSegmentVolume(), i.gasType(), i.specificGravity(),
                i.h2SMoleFraction(), i.co2MoleFraction(), i.n2MoleFraction(), i.modificationMethod(),
                i.deviationFactorMethod(), i.viscosityMethod(), i.importedFileName(),
                c.deviationFactorToolboxId(), c.deviationFactorBefore(), c.deviationFactorAfter(),
                c.ventLossVolume(), i.condensateVolume(), i.gasOilRatio(), c.condensateLossVolume(), i.inputCondensateLossVolume(), id);
    }

    private void replaceSegments(long recordId, SurfaceInput input) {
        jdbc.update("DELETE FROM project_reservoir_surface_loss_segment WHERE surface_loss_id=?", recordId);
        if (!FORMULA_MODE.equals(input.calculationMode())) return;
        for (int index = 0; index < input.segments().size(); index++) {
            jdbc.update("""
                    INSERT INTO project_reservoir_surface_loss_segment
                    (surface_loss_id,segment_no,segment_volume) VALUES(?,?,?)
                    """, recordId, index + 1, input.segments().get(index).segmentVolume());
        }
    }

    private int allocateNumber(long projectId, long gasReservoirId) {
        Integer number = jdbc.queryForObject("""
                SELECT COALESCE(MAX(record_no),0)+1 FROM project_reservoir_surface_loss
                WHERE project_id=? AND gas_reservoir_id=?
                """, Integer.class, projectId, gasReservoirId);
        return number == null ? 1 : number;
    }

    private static void validateCalculation(SurfaceInput input, Calculation calculation) {
        // 复用放空快照校验，但地面记录保存在独立主子表，绝不写入井筒模块。
        double condensate = SurfaceLossCalculationService.calculateCondensate(input);
        WellboreLossStorageService.validateCalculation(input.toVentInput(),
                new com.grdp.studio.reservoirloss.dto.WellboreLossDtos.Calculation(
                        calculation.deviationFactorToolboxId(), calculation.deviationFactorBefore(),
                        calculation.deviationFactorAfter(), calculation.totalSegmentVolume(),
                        calculation.ventLossVolume()));
        if (!sameNumber(condensate, calculation.condensateLossVolume())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "凝液损耗结果与当前参数不一致，请重新计算");
        }
    }

    private static boolean sameNumber(double first, double second) {
        return Double.isFinite(first) && Double.isFinite(second)
                && Math.abs(first - second) <= Math.max(1d, Math.abs(first)) * 1e-9d;
    }

    private static int modeNumber(String mode) {
        return DIRECT_MODE.equals(mode) ? 0 : 1;
    }

    private static RecordSummary summary(ResultSet rs) throws SQLException {
        return new RecordSummary(rs.getLong(1), rs.getInt(2), rs.getString(3),
                "surface", rs.getTimestamp(4).toLocalDateTime());
    }

    private static Double nullableDouble(ResultSet rs, int index) throws SQLException {
        double value = rs.getDouble(index);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rs, int index) throws SQLException {
        int value = rs.getInt(index);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, int index) throws SQLException {
        long value = rs.getLong(index);
        return rs.wasNull() ? null : value;
    }

    private long insertAndReturnKey(String sql, Object... args) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // 明确只取主键，避免驱动同时返回自动生成的时间戳字段。
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("数据库未返回新增记录ID");
        return key.longValue();
    }
}
