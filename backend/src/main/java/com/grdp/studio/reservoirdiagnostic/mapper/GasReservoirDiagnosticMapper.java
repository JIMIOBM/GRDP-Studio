package com.grdp.studio.reservoirdiagnostic.mapper;

import com.grdp.studio.reservoirdiagnostic.dto.GasReservoirDiagnosticModels;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 库级诊断曲线查询。
 *
 * <p>全部 SQL 使用注解实现，不需要 XML。</p>
 */
@Mapper
public interface GasReservoirDiagnosticMapper {

    /**
     * 查询当前项目、气藏下的全部井。
     *
     * <p>保留该方法供其他场景使用；库级诊断计算本身应使用
     * {@link #selectStorageWells(long, long, long)}。</p>
     */
    @Select("""
            SELECT
                w.id        AS wellId,
                w.well_name AS wellName
            FROM project_well_heads w
            WHERE w.project_id = #{projectId}
              AND w.project_gas_reservoir_id = #{gasReservoirId}
            ORDER BY w.id
            """)
    List<GasReservoirDiagnosticModels.WellRow>
    selectReservoirWells(
            @Param("projectId")
            long projectId,

            @Param("gasReservoirId")
            long gasReservoirId
    );

    /**
     * 查询指定储气库内，每口井编号最大的 CALCULATED 诊断方案。
     *
     * <p>注意：必须按 storageId 限定井集合，不能把同一气藏中
     * 其他储气库的井带入库级诊断。</p>
     */
    @Select("""
            SELECT
                d.id            AS diagnosticId,
                d.well_id       AS wellId,
                w.well_name     AS wellName,
                d.diagnostic_no AS diagnosticNo,
                d.pvt_id        AS pvtId,
                d.pvt_name      AS pvtName,
                d.pvt_snapshot  AS pvtSnapshot,

                (
                    SELECT COUNT(1)
                    FROM project_well_diagnostic_input i
                    WHERE i.diagnostic_id = d.id
                ) AS inputCount

            FROM project_well_diagnostic d

            INNER JOIN project_well_heads w
                ON w.id = d.well_id

            INNER JOIN (
                SELECT
                    d2.well_id,
                    MAX(d2.diagnostic_no) AS maxDiagnosticNo
                FROM project_well_diagnostic d2
                WHERE d2.project_id = #{projectId}
                  AND d2.gas_reservoir_id = #{gasReservoirId}
                  AND d2.status = 'CALCULATED'
                  AND EXISTS (
                      SELECT 1
                      FROM project_storage_well sw2
                      WHERE sw2.storage_id = #{storageId}
                        AND sw2.well_id = d2.well_id
                  )
                GROUP BY d2.well_id
            ) latest
                ON latest.well_id = d.well_id
               AND latest.maxDiagnosticNo = d.diagnostic_no

            WHERE d.project_id = #{projectId}
              AND d.gas_reservoir_id = #{gasReservoirId}
              AND d.status = 'CALCULATED'
              AND EXISTS (
                  SELECT 1
                  FROM project_storage_well sw
                  WHERE sw.storage_id = #{storageId}
                    AND sw.well_id = d.well_id
              )

            ORDER BY w.id
            """)
    List<GasReservoirDiagnosticModels.LatestDiagnosticRow>
    selectLatestCalculatedDiagnostics(
            @Param("projectId")
            long projectId,

            @Param("gasReservoirId")
            long gasReservoirId,

            @Param("storageId")
            long storageId
    );

    /**
     * 一次读取指定储气库内所有井“当前有效诊断方案”的 input。
     *
     * <p>Service 再根据 time_text 做库级 SUM。</p>
     */
    @Select("""
            SELECT
                d.id            AS diagnosticId,
                d.well_id       AS wellId,
                w.well_name     AS wellName,

                i.sequence_no   AS sequenceNo,
                i.time_text     AS timeText,
                i.cycle_name    AS cycleName,
                i.gas_volume    AS gasVolume

            FROM project_well_diagnostic d

            INNER JOIN project_well_heads w
                ON w.id = d.well_id

            INNER JOIN (
                SELECT
                    d2.well_id,
                    MAX(d2.diagnostic_no) AS maxDiagnosticNo
                FROM project_well_diagnostic d2
                WHERE d2.project_id = #{projectId}
                  AND d2.gas_reservoir_id = #{gasReservoirId}
                  AND d2.status = 'CALCULATED'
                  AND EXISTS (
                      SELECT 1
                      FROM project_storage_well sw2
                      WHERE sw2.storage_id = #{storageId}
                        AND sw2.well_id = d2.well_id
                  )
                GROUP BY d2.well_id
            ) latest
                ON latest.well_id = d.well_id
               AND latest.maxDiagnosticNo = d.diagnostic_no

            INNER JOIN project_well_diagnostic_input i
                ON i.diagnostic_id = d.id

            WHERE d.project_id = #{projectId}
              AND d.gas_reservoir_id = #{gasReservoirId}
              AND d.status = 'CALCULATED'
              AND EXISTS (
                  SELECT 1
                  FROM project_storage_well sw
                  WHERE sw.storage_id = #{storageId}
                    AND sw.well_id = d.well_id
              )

            ORDER BY
                i.time_text,
                d.well_id,
                i.sequence_no
            """)
    List<GasReservoirDiagnosticModels.DiagnosticInputRow>
    selectLatestDiagnosticInputs(
            @Param("projectId")
            long projectId,

            @Param("gasReservoirId")
            long gasReservoirId,

            @Param("storageId")
            long storageId
    );

    /**
     * 查询指定储气库中包含的井。
     */
    @Select("""
            SELECT DISTINCT
                w.id        AS wellId,
                w.well_name AS wellName
            FROM project_storage_well r
            INNER JOIN project_well_heads w
                ON w.id = r.well_id
            WHERE r.storage_id = #{storageId}
              AND w.project_id = #{projectId}
              AND w.project_gas_reservoir_id = #{gasReservoirId}
            ORDER BY w.well_name, w.id
            """)
    List<GasReservoirDiagnosticModels.WellRow>
    selectStorageWells(
            @Param("projectId")
            long projectId,

            @Param("gasReservoirId")
            long gasReservoirId,

            @Param("storageId")
            long storageId
    );
}
