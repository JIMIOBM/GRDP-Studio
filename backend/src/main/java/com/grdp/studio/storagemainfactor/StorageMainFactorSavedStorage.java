package com.grdp.studio.storagemainfactor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.GasPvtParam;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SavedMainFactor;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.ToolboxInput;

/**
 * 主控因素分析结果的持久化。
 *
 * <p><b>一个库一份</b>：唯一键 {@code (project_id, gas_reservoir_id, storage_id)}，
 * 保存的语义是"先 UPDATE，影响行数为 0 再 INSERT"，与
 * {@code GeologicalLossStorageService} 的写法一致。因此重复保存只会更新同一行，
 * 不会累积出多条记录——这也是本功能不做记录列表与左侧树节点的原因。
 *
 * <p><b>null 一律存成 null，绝不用 0 顶替</b>：平台不可用时理论压力就是空的，
 * 存成 0 会让下次打开显示"理论压力 0"，一个看着像真数据的假值。
 *
 * <p>刻意不做作用域校验：{@code StorageMainFactorService} 已经在进入前调用
 * {@code StorageCatalogService.requireScope} 验过一遍，这里再查一次库是多余的往返。
 */
@Component
public class StorageMainFactorSavedStorage {

    private final JdbcTemplate jdbc;

    public StorageMainFactorSavedStorage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 保存（存在则更新）。 */
    public void save(long projectId, long gasReservoirId, long storageId, SavedMainFactor saved) {
        ToolboxInput in = saved.inputs();
        GasPvtParam pvt = in == null ? null : in.gasPvtParam();

        int changed = jdbc.update("""
                UPDATE project_reservoir_main_factor SET
                  gas_reservoir_type=?, original_pressure=?, formation_temperature=?,
                  original_gas_in_place=?, cumulative_gas_production=?,
                  rock_compression_coefficient=?, water_compression_coefficient=?, water_saturation=?,
                  gas_type=?, specific_gravity=?, h2s_mole_fraction=?, co2_mole_fraction=?,
                  n2_mole_fraction=?, modification_method=?, deviation_factor_method=?, viscosity_method=?,
                  theoretical_pressure=?, actual_pressure=?, updated_at=CURRENT_TIMESTAMP
                WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?
                """,
                in == null ? null : in.gasReservoirType(),
                in == null ? null : in.originalPressure(),
                in == null ? null : in.formationTemperature(),
                in == null ? null : in.originalGasInPlace(),
                in == null ? null : in.cumulativeGasProduction(),
                in == null ? null : in.rockCompressionCoefficient(),
                in == null ? null : in.waterCompressionCoefficient(),
                in == null ? null : in.waterSaturation(),
                pvt == null ? null : pvt.gasType(),
                pvt == null ? null : pvt.specificGravity(),
                pvt == null ? null : pvt.h2SMoleFraction(),
                pvt == null ? null : pvt.co2MoleFraction(),
                pvt == null ? null : pvt.n2MoleFraction(),
                pvt == null ? null : pvt.modificationMethod(),
                pvt == null ? null : pvt.deviationFactorMethod(),
                pvt == null ? null : pvt.viscosityMethod(),
                saved.theoreticalPressure(), saved.actualPressure(),
                projectId, gasReservoirId, storageId);

        if (changed > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO project_reservoir_main_factor
                (project_id, gas_reservoir_id, storage_id, gas_reservoir_type, original_pressure,
                 formation_temperature, original_gas_in_place, cumulative_gas_production,
                 rock_compression_coefficient, water_compression_coefficient, water_saturation,
                 gas_type, specific_gravity, h2s_mole_fraction, co2_mole_fraction, n2_mole_fraction,
                 modification_method, deviation_factor_method, viscosity_method,
                 theoretical_pressure, actual_pressure, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,
                projectId, gasReservoirId, storageId,
                in == null ? null : in.gasReservoirType(),
                in == null ? null : in.originalPressure(),
                in == null ? null : in.formationTemperature(),
                in == null ? null : in.originalGasInPlace(),
                in == null ? null : in.cumulativeGasProduction(),
                in == null ? null : in.rockCompressionCoefficient(),
                in == null ? null : in.waterCompressionCoefficient(),
                in == null ? null : in.waterSaturation(),
                pvt == null ? null : pvt.gasType(),
                pvt == null ? null : pvt.specificGravity(),
                pvt == null ? null : pvt.h2SMoleFraction(),
                pvt == null ? null : pvt.co2MoleFraction(),
                pvt == null ? null : pvt.n2MoleFraction(),
                pvt == null ? null : pvt.modificationMethod(),
                pvt == null ? null : pvt.deviationFactorMethod(),
                pvt == null ? null : pvt.viscosityMethod(),
                saved.theoreticalPressure(), saved.actualPressure());
    }

    /** 读取本库已保存的一份；从未保存过返回 {@link Optional#empty()}。 */
    public Optional<SavedMainFactor> load(long projectId, long gasReservoirId, long storageId) {
        List<SavedMainFactor> found = jdbc.query("""
                SELECT original_pressure, formation_temperature, original_gas_in_place,
                       cumulative_gas_production, rock_compression_coefficient,
                       water_compression_coefficient, water_saturation, gas_reservoir_type,
                       gas_type, specific_gravity, h2s_mole_fraction, co2_mole_fraction, n2_mole_fraction,
                       modification_method, deviation_factor_method, viscosity_method,
                       theoretical_pressure, actual_pressure
                FROM project_reservoir_main_factor
                WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?
                """, (rs, n) -> new SavedMainFactor(
                        new ToolboxInput(
                                doubleOrNull(rs, 1), doubleOrNull(rs, 2), doubleOrNull(rs, 3),
                                doubleOrNull(rs, 4), doubleOrNull(rs, 5), doubleOrNull(rs, 6),
                                doubleOrNull(rs, 7), intOrNull(rs, 8),
                                // 页岩气藏专属入参不入库，读回来就是空——它们由平台模板兜底
                                null, null, null, null,
                                new GasPvtParam(intOrZero(rs, 9), zeroIfNull(rs, 10), intOrZero(rs, 15),
                                        zeroIfNull(rs, 11), zeroIfNull(rs, 12), zeroIfNull(rs, 13),
                                        intOrZero(rs, 14), intOrZero(rs, 16))),
                        doubleOrNull(rs, 17), doubleOrNull(rs, 18)),
                projectId, gasReservoirId, storageId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    /**
     * 按列名取值并允许为 null。
     *
     * <p>用 {@code getObject} 转 {@code Number} 而不是 {@code getDouble}：
     * 后者会把 SQL NULL 静默变成 0，正好破坏上面那条 null 语义。
     */
    private static Double doubleOrNull(ResultSet rs, int index) throws SQLException {
        Object value = rs.getObject(index);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Integer intOrNull(ResultSet rs, int index) throws SQLException {
        Object value = rs.getObject(index);
        return value instanceof Number number ? number.intValue() : null;
    }

    /** 枚举型列缺省按 0：它们对应下拉框的第一项，不会造成"看着像真数据的假值"。 */
    private static int intOrZero(ResultSet rs, int index) throws SQLException {
        Integer value = intOrNull(rs, index);
        return value == null ? 0 : value;
    }

    private static double zeroIfNull(ResultSet rs, int index) throws SQLException {
        Double value = doubleOrNull(rs, index);
        return value == null ? 0d : value;
    }
}
