package com.grdp.studio.reservoirloss.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import org.springframework.jdbc.core.ConnectionCallback;
import com.grdp.studio.waterinvasion.WaterInvasionCarrierGuard;

/** 本系统独立储气库档案；两个外部ID共同限定原系统项目范围。 */
@Service
public class StorageCatalogService {
    private final JdbcTemplate jdbc;
    public StorageCatalogService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    // 两个原系统ID共同限定项目范围；storageId才是本模块独立储气库的主键。
    public record Storage(long storageId, long projectId, long gasReservoirId, String name) {}
    public record Well(long id, String wellName) {}

    private void requireProjectScope(long projectId, long gasReservoirId) {
        if (projectId <= 0 || gasReservoirId <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有效的项目范围");
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_gas_reservoir g
                JOIN project_summaries p ON p.id=g.project_id
                WHERE g.id=? AND g.project_id=? AND p.delete_status=0
                """, Integer.class, gasReservoirId, projectId);
        if (count == null || count != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前项目范围不存在或已删除");
    }

    public List<Well> candidateWells(long projectId, long gasReservoirId) {
        requireProjectScope(projectId, gasReservoirId);
        // 不排除已属于其他库的井：同一口井允许加入多个储气库。
        return jdbc.query("""
                SELECT id,well_name FROM project_well_heads
                WHERE project_id=? AND project_gas_reservoir_id=? ORDER BY well_name,id
                """, (rs, n) -> new Well(rs.getLong(1), rs.getString(2)), projectId, gasReservoirId);
    }

    public List<Well> wells(long storageId, long projectId, long gasReservoirId) {
        requireScope(projectId, gasReservoirId, storageId);
        return jdbc.query("""
                SELECT w.id,w.well_name FROM project_storage_well r
                JOIN project_well_heads w ON w.id=r.well_id
                WHERE r.storage_id=? AND w.project_id=? AND w.project_gas_reservoir_id=?
                ORDER BY w.well_name,w.id
                """, (rs, n) -> new Well(rs.getLong(1), rs.getString(2)), storageId, projectId, gasReservoirId);
    }

    public List<Storage> list(long projectId, long gasReservoirId) {
        return jdbc.query("SELECT id,project_id,gas_reservoir_id,storage_name FROM project_storage WHERE project_id=? AND gas_reservoir_id=? ORDER BY id",
                (rs, n) -> new Storage(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)), projectId, gasReservoirId);
    }

    @Transactional
    public Storage create(long projectId, long gasReservoirId, String name, List<Long> wellIds) {
        // 同一项目范围允许多个库；名称用于显示，不作为记录归属键。
        if (projectId <= 0 || gasReservoirId <= 0 || name == null || name.trim().isEmpty() || name.trim().length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写有效的项目关联和储气库名称（不超过100字）");
        requireProjectScope(projectId, gasReservoirId);
        if (wellIds == null || wellIds.isEmpty() || wellIds.size() > 2000
                || wellIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择1至2000口有效单井");
        List<Long> selectedIds = wellIds.stream().distinct().sorted().toList();
        // 在建库前锁定并核对所有选井；固定主键顺序降低多个建库事务同时选井时的死锁风险。
        String placeholders = String.join(",", Collections.nCopies(selectedIds.size(), "?"));
        List<Long> existingIds = jdbc.query("SELECT id FROM project_well_heads WHERE id IN ("
                        + placeholders + ") AND project_id=? AND project_gas_reservoir_id=? ORDER BY id FOR UPDATE",
                (rs, n) -> rs.getLong(1), java.util.stream.Stream.concat(selectedIds.stream(),
                        java.util.stream.Stream.of(projectId, gasReservoirId)).toArray());
        if (!existingIds.equals(selectedIds))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选单井不存在或不属于当前项目范围，请刷新后重新选择");
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO project_storage(project_id,gas_reservoir_id,storage_name) VALUES(?,?,?)", new String[]{"id"});
            statement.setLong(1, projectId); statement.setLong(2, gasReservoirId); statement.setString(3, name.trim());
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("数据库未返回储气库ID");
        long storageId = key.getKey().longValue();
        // 主表与关系表在同一事务中写入，任何一条关联失败都不能留下空壳库档案。
        jdbc.batchUpdate("INSERT INTO project_storage_well(storage_id,well_id) VALUES(?,?)",
                selectedIds, 200, (statement, wellId) -> {
                    statement.setLong(1, storageId);
                    statement.setLong(2, wellId);
                });
        return new Storage(storageId, projectId, gasReservoirId, name.trim());
    }

    /** 只删除库拥有的数据；井档案、单井历史和其他库的共享来源不属于删除范围。 */
    @Transactional
    public void delete(long storageId, long projectId, long gasReservoirId) {
        requireProjectScope(projectId, gasReservoirId);
        lockScope(jdbc, projectId, gasReservoirId, storageId);
        // 模块可能分批部署。只跳过尚未安装的已知表，不关闭外键，也不扫描删除任意表。
        Set<String> tables = jdbc.execute((ConnectionCallback<Set<String>>) connection -> {
            var installed = new HashSet<String>();
            try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (rows.next()) installed.add(rows.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT));
            }
            return installed;
        });
        if (tables.contains("project_storage_water_invasion")) {
            var tasks = jdbc.queryForList("""
                    SELECT task_status,active_carrier_well_id,active_storage_id,carrier_well_id,legacy_submitted_at
                    FROM project_storage_water_invasion WHERE storage_id=? FOR UPDATE
                    """, storageId);
            if (tasks.stream().anyMatch(task -> List.of("RUNNING", "SAVING").contains(task.get("task_status"))
                    || task.get("active_carrier_well_id") != null || task.get("active_storage_id") != null))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "该库仍有水侵计算任务运行或尚未确认结束，请先到库水侵分析中确认任务结束后再删除");
            for (var task : tasks) {
                if (task.get("legacy_submitted_at") != null)
                    WaterInvasionCarrierGuard.remember(jdbc, ((Number) task.get("carrier_well_id")).longValue());
            }
            String taskIds = "SELECT id FROM project_storage_water_invasion WHERE storage_id=?";
            if (tables.contains("project_storage_water_invasion_detail") && tables.contains("project_storage_water_invasion_result"))
                jdbc.update("DELETE FROM project_storage_water_invasion_detail WHERE result_id IN (SELECT id FROM project_storage_water_invasion_result WHERE water_invasion_id IN (" + taskIds + "))", storageId);
            for (String table : List.of("project_storage_water_invasion_result", "project_storage_water_invasion_input", "project_storage_water_invasion_source")) {
                if (tables.contains(table)) jdbc.update("DELETE FROM " + table + " WHERE water_invasion_id IN (" + taskIds + ")", storageId);
            }
            jdbc.update("DELETE FROM project_storage_water_invasion WHERE storage_id=?", storageId);
        }
        for (String type : List.of("wellbore", "surface")) {
            String table = "project_reservoir_" + type + "_loss";
            if (tables.contains(table) && tables.contains(table + "_segment"))
                jdbc.update("DELETE FROM " + table + "_segment WHERE " + type + "_loss_id IN (SELECT id FROM " + table + " WHERE storage_id=?)", storageId);
        }
        // 新增库级模块中，物质平衡快照与压力梯度表没有级联外键，必须在删除父库前清理。
        for (String table : List.of("storage_material_balance_snapshot", "storage_pressure_gradient_point",
                "storage_pressure_gradient_config", "project_reservoir_main_factor", "project_storage_network_topology",
                "project_reservoir_wellbore_loss", "project_reservoir_surface_loss",
                "project_reservoir_microscopic_loss", "project_reservoir_escape_loss", "project_storage_capacity_design", "project_storage_well")) {
            if (tables.contains(table)) jdbc.update("DELETE FROM " + table + " WHERE storage_id=?", storageId);
        }
        int changed = jdbc.update("DELETE FROM project_storage WHERE id=? AND project_id=? AND gas_reservoir_id=?", storageId, projectId, gasReservoirId);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "储气库状态已变化，请刷新后重试");
    }

    public void requireScope(long projectId, long gasReservoirId, long storageId) {
        requireScope(jdbc, projectId, gasReservoirId, storageId);
    }
    public static void requireScope(JdbcTemplate jdbc, long projectId, long gasReservoirId, long storageId) {
        // 同时核对库ID与项目范围，避免只凭有效库ID访问其他范围的数据；此检查不替代用户权限校验。
        if (storageId <= 0 || projectId <= 0 || gasReservoirId <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先选择具体储气库");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM project_storage WHERE id=? AND project_id=? AND gas_reservoir_id=?",
                Integer.class, storageId, projectId, gasReservoirId);
        if (count == null || count != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该储气库不属于当前项目范围");
    }
    // 必须在保存事务内调用；同一库并发保存时串行分配各类记录编号。
    public static void lockScope(JdbcTemplate jdbc, long projectId, long gasReservoirId, long storageId) {
        // 首条损耗记录尚不存在时也能锁住父库行，保护后续MAX+1编号分配；锁随调用方事务结束释放。
        requireScope(jdbc, projectId, gasReservoirId, storageId);
        jdbc.queryForObject("SELECT id FROM project_storage WHERE id=? AND project_id=? AND gas_reservoir_id=? FOR UPDATE",
                Long.class, storageId, projectId, gasReservoirId);
    }
}
