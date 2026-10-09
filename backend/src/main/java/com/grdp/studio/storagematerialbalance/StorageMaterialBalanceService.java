package com.grdp.studio.storagematerialbalance;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static com.grdp.studio.storagematerialbalance.StorageMaterialBalanceCalculator.*;

@Service
public class StorageMaterialBalanceService {
    private final JdbcTemplate jdbc;
    private final StorageCatalogService catalog;
    private final ObjectMapper json;
    public StorageMaterialBalanceService(JdbcTemplate jdbc, StorageCatalogService catalog, ObjectMapper json) {
        this.jdbc = jdbc; this.catalog = catalog; this.json = json;
    }

    private record Record(long id, Long inputId, Integer reservoirType, Double volume,
                          Double rSquared, Integer reliability) {}
    private record SnapshotRow(long id, String wellIdsJson, String resultJson, LocalDateTime savedAt) {}
    public record SavedResult(long id, List<Long> selectedWellIds, Result result, String savedAt) {}

    /** 服务端重新读取并汇总当前所选井，再将完整结果作为可恢复快照写入数据库。 */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public SavedResult save(long projectId, long gasReservoirId, long storageId, List<Long> selectedWellIds) {
        if (selectedWellIds == null) throw new BusinessException(400, "请选择参与汇总的井");
        var result = aggregate(projectId, gasReservoirId, storageId, selectedWellIds);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO storage_material_balance_snapshot
                    (project_id,gas_reservoir_id,storage_id,selected_well_ids_json,result_json,saved_at)
                    VALUES (?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, projectId); statement.setLong(2, gasReservoirId); statement.setLong(3, storageId);
            statement.setString(4, json.writeValueAsString(selectedWellIds));
            statement.setString(5, json.writeValueAsString(result)); statement.setObject(6, now);
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("数据库未返回物质平衡保存记录ID");
        return new SavedResult(key.getKey().longValue(), List.copyOf(selectedWellIds), result, now + "Z");
    }

    /** 返回当前库最近一次已保存的结果；尚未保存时返回 null。 */
    @Transactional(readOnly = true)
    public SavedResult latest(long projectId, long gasReservoirId, long storageId) {
        catalog.wells(storageId, projectId, gasReservoirId);
        var rows = jdbc.query("""
                SELECT id,selected_well_ids_json,result_json,saved_at
                FROM storage_material_balance_snapshot
                WHERE project_id=? AND gas_reservoir_id=? AND storage_id=?
                ORDER BY id DESC LIMIT 1
                """, (rs, n) -> new SnapshotRow(rs.getLong("id"), rs.getString("selected_well_ids_json"),
                rs.getString("result_json"), rs.getObject("saved_at", LocalDateTime.class)),
                projectId, gasReservoirId, storageId);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        var selectedWellIds = json.readValue(row.wellIdsJson(), new TypeReference<List<Long>>() {});
        var result = json.readValue(row.resultJson(), Result.class);
        return new SavedResult(row.id(), selectedWellIds, result, row.savedAt() + "Z");
    }

    /** 只读一致性快照；既不调用原平台 calc，也不写入任何原始数据或分析结果。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Result aggregate(long projectId, long gasReservoirId, long storageId) {
        return aggregate(projectId, gasReservoirId, storageId, null);
    }

    /** 未传井 ID 时汇总全部库成员；传入井 ID 时只汇总经过当前库成员关系校验的所选井。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Result aggregate(long projectId, long gasReservoirId, long storageId, List<Long> selectedWellIds) {
        var members = catalog.wells(storageId, projectId, gasReservoirId);
        if (selectedWellIds != null) {
            if (selectedWellIds.isEmpty() || selectedWellIds.size() > 2000
                    || selectedWellIds.stream().anyMatch(id -> id == null || id <= 0)
                    || new HashSet<>(selectedWellIds).size() != selectedWellIds.size()) {
                throw new BusinessException(400, "请选择有效的库成员井");
            }
            Set<Long> memberIds = members.stream().map(StorageCatalogService.Well::id)
                    .collect(java.util.stream.Collectors.toSet());
            if (selectedWellIds.stream().anyMatch(id -> !memberIds.contains(id))) {
                throw new BusinessException(400, "所选井不属于当前储气库");
            }
            Set<Long> selected = new HashSet<>(selectedWellIds);
            members = members.stream().filter(well -> selected.contains(well.id())).toList();
        }
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
