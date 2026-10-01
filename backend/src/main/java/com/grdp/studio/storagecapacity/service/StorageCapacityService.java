package com.grdp.studio.storagecapacity.service;

import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import com.grdp.studio.storagecapacity.dto.StorageCapacityDtos.Design;
import com.grdp.studio.storagecapacity.dto.StorageCapacityDtos.DesignInput;
import com.grdp.studio.storagecapacity.dto.StorageCapacityDtos.SaveRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 储气库库容设计读写。
 * 作用域校验直接复用 {@link StorageCatalogService#requireScope}，
 * 保证"库必须属于当前项目范围"的口径与损耗评价等库级模块完全一致，不另立一套。
 */
@Service
public class StorageCapacityService {
    private final JdbcTemplate jdbc;
    public StorageCapacityService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 读取。未保存时返回 null，由控制器包装成 data 为空。 */
    public Design find(long projectId, long gasReservoirId, long storageId) {
        StorageCatalogService.requireScope(jdbc, projectId, gasReservoirId, storageId);
        List<Design> rows = jdbc.query("""
                SELECT id,project_id,gas_reservoir_id,storage_id,
                       upper_limit_pressure,lower_limit_pressure,
                       storage_capacity,working_gas_volume,cushion_gas_volume,
                       supplementary_cushion_gas_volume,imported_file_name,updated_at
                FROM project_storage_capacity_design
                WHERE storage_id=? AND project_id=? AND gas_reservoir_id=?
                """, MAPPER, storageId, projectId, gasReservoirId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 保存。先 UPDATE、影响 0 行再 INSERT，配合 storage_id 唯一键实现幂等：
     * 同一储气库反复保存只会更新同一条记录，并发下最多一方收到唯一键冲突。
     */
    @Transactional
    public Design save(SaveRequest request) {
        StorageCatalogService.requireScope(jdbc, request.projectId(), request.gasReservoirId(), request.storageId());
        DesignInput input = request.input();
        validate(input);
        String importedFileName = trimToNull(input.importedFileName());

        int updated = jdbc.update("""
                UPDATE project_storage_capacity_design
                SET upper_limit_pressure=?,lower_limit_pressure=?,
                    storage_capacity=?,working_gas_volume=?,cushion_gas_volume=?,
                    supplementary_cushion_gas_volume=?,imported_file_name=?
                WHERE storage_id=? AND project_id=? AND gas_reservoir_id=?
                """,
                input.upperLimitPressure(), input.lowerLimitPressure(),
                input.storageCapacity(), input.workingGasVolume(), input.cushionGasVolume(),
                input.supplementaryCushionGasVolume(), importedFileName,
                request.storageId(), request.projectId(), request.gasReservoirId());

        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO project_storage_capacity_design
                      (project_id,gas_reservoir_id,storage_id,
                       upper_limit_pressure,lower_limit_pressure,
                       storage_capacity,working_gas_volume,cushion_gas_volume,
                       supplementary_cushion_gas_volume,imported_file_name)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """,
                    request.projectId(), request.gasReservoirId(), request.storageId(),
                    input.upperLimitPressure(), input.lowerLimitPressure(),
                    input.storageCapacity(), input.workingGasVolume(), input.cushionGasVolume(),
                    input.supplementaryCushionGasVolume(), importedFileName);
        }
        return find(request.projectId(), request.gasReservoirId(), request.storageId());
    }

    /** 清空该储气库的库容设计；未保存过时静默返回。 */
    @Transactional
    public void remove(long projectId, long gasReservoirId, long storageId) {
        StorageCatalogService.requireScope(jdbc, projectId, gasReservoirId, storageId);
        jdbc.update("DELETE FROM project_storage_capacity_design WHERE storage_id=? AND project_id=? AND gas_reservoir_id=?",
                storageId, projectId, gasReservoirId);
    }

    /**
     * 范围与关系校验。允许部分填写，但不允许全空（避免产生空记录）。
     * 数据库层的 CHECK 会兜底压力关系，此处提前给出可读的中文提示。
     */
    private void validate(DesignInput input) {
        boolean allNull = Stream.of(input.upperLimitPressure(), input.lowerLimitPressure(),
                        input.storageCapacity(), input.workingGasVolume(),
                        input.cushionGasVolume(), input.supplementaryCushionGasVolume())
                .allMatch(Objects::isNull);
        if (allNull) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少填写一项库容设计参数");
        }
        if (input.lowerLimitPressure() != null && input.lowerLimitPressure() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "下限压力必须大于0");
        }
        if (input.upperLimitPressure() != null && input.upperLimitPressure() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上限压力必须大于0");
        }
        if (input.upperLimitPressure() != null && input.lowerLimitPressure() != null
                && input.upperLimitPressure() <= input.lowerLimitPressure()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上限压力必须大于下限压力");
        }
        boolean negativeVolume = Stream.of(input.storageCapacity(), input.workingGasVolume(),
                        input.cushionGasVolume(), input.supplementaryCushionGasVolume())
                .filter(Objects::nonNull)
                .anyMatch(value -> value < 0);
        if (negativeVolume) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "库容参数不能为负数");
        }
        // 文本长度再兜一层：避免超长内容打到数据库列长度限制后变成 500。
        // 与 StorageCatalogService.create 对名称的处理方式一致。
        if (input.importedFileName() != null && input.importedFileName().trim().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件名不能超过255字");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final RowMapper<Design> MAPPER = (rs, rowNum) -> new Design(
            rs.getLong("id"),
            rs.getLong("project_id"),
            rs.getLong("gas_reservoir_id"),
            rs.getLong("storage_id"),
            new DesignInput(
                    nullableDouble(rs, "upper_limit_pressure"),
                    nullableDouble(rs, "lower_limit_pressure"),
                    nullableDouble(rs, "storage_capacity"),
                    nullableDouble(rs, "working_gas_volume"),
                    nullableDouble(rs, "cushion_gas_volume"),
                    nullableDouble(rs, "supplementary_cushion_gas_volume"),
                    rs.getString("imported_file_name")),
            timestampToLocalDateTime(rs.getTimestamp("updated_at")));

    /** DOUBLE 列可空，必须用 wasNull 区分"未填写"与 0。 */
    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime timestampToLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
