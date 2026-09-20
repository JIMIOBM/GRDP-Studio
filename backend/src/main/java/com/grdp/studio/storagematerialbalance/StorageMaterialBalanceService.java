package com.grdp.studio.storagematerialbalance;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.grdp.studio.storagematerialbalance.StorageMaterialBalanceCalculator.*;

@Service
public class StorageMaterialBalanceService {
    private final JdbcTemplate jdbc;
    private final StorageCatalogService catalog;
    public StorageMaterialBalanceService(JdbcTemplate jdbc, StorageCatalogService catalog) {
        this.jdbc = jdbc; this.catalog = catalog;
    }

    private record Record(long id, Long inputId, Integer reservoirType, Double volume,
                          Double rSquared, Integer reliability) {}

    /** 只读一致性快照；既不调用原平台 calc，也不写入任何原始数据或分析结果。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Result aggregate(long projectId, long gasReservoirId, long storageId) {
        var members = catalog.wells(storageId, projectId, gasReservoirId);
        Integer active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_gas_reservoir g JOIN project_summaries p ON p.id=g.project_id
                WHERE g.id=? AND g.project_id=? AND p.delete_status=0
                """, Integer.class, gasReservoirId, projectId);
        if (active == null || active != 1) throw new BusinessException(404, "当前项目范围不存在或已删除");
        List<Source> sources = new ArrayList<>();
        int rowCount = 0;
        for (var well : members) {
            // 方法1经原平台数据库核对为“定容气藏物质平衡方程-根据实测静压”。
            // 严格核对三个归属字段；同名的其他工程井或方法2的计算静压永不回退混入。
            List<Record> records = jdbc.query("""
                    SELECT d.id,p.id AS input_id,p.gas_reservoir_type,o.original_gas_volume,o.rsquared,o.reliablity
                    FROM dynamic_original_gas_in_place d
                    LEFT JOIN dynamic_original_gas_in_place_by_mb_input p ON p.dynamic_original_gas_in_place_id=d.id
                    LEFT JOIN dynamic_original_gas_in_place_output o ON o.dynamic_original_gas_in_place_id=d.id
                      AND o.project_id=d.project_id AND o.project_gas_reservoir_id=d.project_gas_reservoir_id
                      AND o.well_name=d.well_name AND o.dynamic_original_gas_inplace_method=1
                    WHERE d.project_id=? AND d.project_gas_reservoir_id=? AND d.well_name=?
                      AND d.dynamic_original_gas_inplace_method=1
                    ORDER BY COALESCE(d.update_time,d.create_time) DESC,d.id DESC LIMIT 2
                    """, (rs, n) -> new Record(rs.getLong("id"), rs.getObject("input_id", Long.class),
                    rs.getObject("gas_reservoir_type", Integer.class), rs.getObject("original_gas_volume", Double.class),
                    rs.getObject("rsquared", Double.class), rs.getObject("reliablity", Integer.class)),
                    projectId, gasReservoirId, well.wellName());
            if (records.isEmpty()) {
                sources.add(new Source(well.id(), well.wellName(), null, null, null,
                        "未找到定容气藏实测静压结果，整井排除", null, List.of()));
                continue;
            }
            Record record = records.getFirst();
            String problem = record.inputId() == null || !Objects.equals(record.reservoirType(), 1)
                    ? "实测静压输入缺失或不是定容气藏，整井排除"
                    : record.reliability() == null || record.reliability() <= 0
                    ? "实测静压分析结果缺失或失败，整井排除" : null;
            List<String> warnings = new ArrayList<>();
            if (records.size() > 1) warnings.add("有多条实测静压分析，使用最近更新的一条；无效时不回退旧结果");
            if (Objects.equals(record.reliability(), 1)) warnings.add("原平台标记结果可靠性偏低，请核对权重");
            List<Sample> samples = List.of();
            if (problem == null) {
                // 数据库存Pa、m³。统一输出MPa、10⁸m³（气）、10⁴m³（水），不使用输出图的Pp代替输入压力。
                // getDate直接取得业务日期，避免先转浏览器UTC时间再截日造成偏移。
                samples = jdbc.query("""
                        SELECT date,formation_pressure,cumulative_production,cumulative_water_production,is_deleted
                        FROM dynamic_original_gas_in_place_by_mb_input_item
                        WHERE dynamic_original_gas_inplace_by_mb_input_id=? ORDER BY date,id LIMIT 100001
                        """, (rs, n) -> new Sample(rs.getDate("date") == null ? null : rs.getDate("date").toLocalDate(),
                        scale(rs.getObject("formation_pressure", Double.class), 1e6),
                        scale(rs.getObject("cumulative_production", Double.class), 1e8),
                        scale(rs.getObject("cumulative_water_production", Double.class), 1e4), rs.getBoolean("is_deleted")),
                        record.inputId());
                rowCount += samples.size();
                if (rowCount > 100000) throw new BusinessException(400, "来源数据超过10万行，请缩小库内井范围后重试；未返回截断结果");
            }
            sources.add(new Source(well.id(), well.wellName(), record.id(), scale(record.volume(), 1e8),
                    record.rSquared(), problem, String.join("；", warnings), samples));
        }
        return calculate(sources);
    }

    private static Double scale(Double value, double divisor) { return value == null ? null : value / divisor; }
}
