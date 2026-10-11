package com.grdp.studio.storagemainfactor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.util.List;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SeriesPointInput;

/**
 * 主控因素分析**时序数据**的持久化——页面导入进来的那份"每个时间点的累产气量与实测地层压力"。
 *
 * <p>它与 {@link StorageMainFactorSavedStorage} 是两件事，所以分成两个类：
 * 这里是**输入**（用户导入的事实数据），那边是**输出**（本次分析算出来的结果快照）。
 *
 * <p><b>导入语义是整体替换</b>：一次导入就是这个库当前的全部时序数据，先删后插、同一事务。
 * 不做增量合并有两个理由——上一次导错的文件不会残留成图上来历不明的点；
 * 也因此不需要"删除某一行"的按钮。
 *
 * <p>单位：库里存**界面口径**（累产气 10⁸m³ / 压力 MPa），与导入文件、与
 * {@code ToolboxInput.cumulativeGasProduction}、与结果表的压力列都一致，全程零换算。
 * 数据由 {@code StorageMainFactorSeriesService} 校验后再进来，本类不做业务校验；
 * 作用域也由它先调用 {@code StorageCatalogService.requireScope} 验过，这里再查库是多余的往返。
 */
@Component
public class StorageMainFactorPointStorage {

    private final JdbcTemplate jdbc;

    public StorageMainFactorPointStorage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 整体替换本库的时序数据：同一事务内先删后插。 */
    @Transactional
    public void replace(long projectId, long gasReservoirId, long storageId, List<SeriesPointInput> points) {
        jdbc.update("DELETE FROM project_reservoir_main_factor_point WHERE storage_id=?", storageId);
        if (points == null || points.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO project_reservoir_main_factor_point
                (project_id, gas_reservoir_id, storage_id, date, cumulative_gas_production, measured_pressure)
                VALUES(?,?,?,?,?,?)
                """, points, points.size(), (statement, point) -> {
            statement.setLong(1, projectId);
            statement.setLong(2, gasReservoirId);
            statement.setLong(3, storageId);
            statement.setDate(4, Date.valueOf(point.date()));
            statement.setDouble(5, point.cumulativeGasProduction());
            statement.setDouble(6, point.measuredPressure());
        });
    }

    /**
     * 读回本库的时序数据，**按日期升序**。
     *
     * <p>排序在这里定：折线图的 x 轴顺序不能由导入顺序或插入顺序决定，
     * 否则换一次导入顺序，图就横过来了。
     */
    public List<SeriesPointInput> load(long projectId, long gasReservoirId, long storageId) {
        return jdbc.query("""
                SELECT date, cumulative_gas_production, measured_pressure
                FROM project_reservoir_main_factor_point
                WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?
                ORDER BY date, id
                """, (rs, n) -> new SeriesPointInput(
                        rs.getDate("date").toLocalDate(),
                        rs.getDouble("cumulative_gas_production"),
                        rs.getDouble("measured_pressure")),
                projectId, gasReservoirId, storageId);
    }
}
