package com.grdp.studio.waterinvasion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 井和库必须先锁同一个井档案行，再检查双方持久任务，不能只依靠两张表各自的唯一键。 */
public final class WaterInvasionCarrierGuard {
    private WaterInvasionCarrierGuard() {}
    public static boolean installed(JdbcTemplate jdbc) {
        return installed(jdbc, "project_storage_water_invasion");
    }
    private static boolean installed(JdbcTemplate jdbc, String table) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c -> {
            try(var tables=c.getMetaData().getTables(c.getCatalog(),null,table,new String[]{"TABLE"})) {
                while(tables.next()) if(table.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
                return false;
            }
        }));
    }
    public static void checkStorageBusy(JdbcTemplate jdbc,long well) {
        if(installed(jdbc) && jdbc.queryForObject("SELECT COUNT(*) FROM project_storage_water_invasion WHERE active_carrier_well_id=?",Integer.class,well)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该井正被库水侵计算使用，或上次任务尚未确认结束；请到库水侵页面检查任务");
    }
    public static boolean wasUsed(JdbcTemplate jdbc,long well) {
        var queries = new java.util.ArrayList<String>();
        var params = new java.util.ArrayList<Long>();
        if (installed(jdbc, "project_well_water_invasion_carrier_guard")) {
            queries.add("EXISTS (SELECT 1 FROM project_well_water_invasion_carrier_guard WHERE well_id=?)");
            params.add(well);
        }
        if (installed(jdbc)) {
            queries.add("EXISTS (SELECT 1 FROM project_storage_water_invasion WHERE carrier_well_id=? AND legacy_submitted_at IS NOT NULL)");
            params.add(well);
        }
        // 一次查询读取同一快照，避免删除事务提交于两次查询之间时丢失保护。
        return !queries.isEmpty() && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT " + String.join(" OR ", queries), Boolean.class, params.toArray()));
    }
    /** 与删除事务一起提交，只保留井 ID 安全标记，不保留库、参数或计算结果。 */
    public static void remember(JdbcTemplate jdbc, long well) {
        if (!installed(jdbc, "project_well_water_invasion_carrier_guard"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先执行 storage_delete_guard.sql，以保留承载井安全保护后再删除库");
        jdbc.update("INSERT INTO project_well_water_invasion_carrier_guard(well_id) VALUES(?) ON DUPLICATE KEY UPDATE well_id=VALUES(well_id)", well);
    }
}
