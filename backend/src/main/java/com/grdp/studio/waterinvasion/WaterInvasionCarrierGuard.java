package com.grdp.studio.waterinvasion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 井和库必须先锁同一个井档案行，再检查双方持久任务，不能只依靠两张表各自的唯一键。 */
public final class WaterInvasionCarrierGuard {
    private WaterInvasionCarrierGuard() {}
    public static boolean installed(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c -> {
            try(var tables=c.getMetaData().getTables(c.getCatalog(),null,"project_storage_water_invasion",new String[]{"TABLE"})) {
                while(tables.next()) if("project_storage_water_invasion".equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
                return false;
            }
        }));
    }
    public static void checkStorageBusy(JdbcTemplate jdbc,long well) {
        if(installed(jdbc) && jdbc.queryForObject("SELECT COUNT(*) FROM project_storage_water_invasion WHERE active_carrier_well_id=?",Integer.class,well)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该井正被库水侵计算使用，或上次任务尚未确认结束；请到库水侵页面检查任务");
    }
    public static boolean wasUsed(JdbcTemplate jdbc,long well) {
        return installed(jdbc) && jdbc.queryForObject("SELECT COUNT(*) FROM project_storage_water_invasion WHERE carrier_well_id=? AND legacy_submitted_at IS NOT NULL",Integer.class,well)>0;
    }
}
