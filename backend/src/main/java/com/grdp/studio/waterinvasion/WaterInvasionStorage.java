package com.grdp.studio.waterinvasion;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static com.grdp.studio.waterinvasion.WaterInvasionModels.*;
import static com.grdp.studio.waterinvasion.WaterInvasionResultMapper.*;

/** 仅操作新平台四张表；快照和结构化结果在同一事务提交，不写旧平台分析表。 */
@Service
public class WaterInvasionStorage {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final WaterInvasionResultMapper mapper;
    private final TransactionTemplate tx;
    public WaterInvasionStorage(JdbcTemplate jdbc, ObjectMapper json, WaterInvasionResultMapper mapper, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.json = json; this.mapper = mapper; this.tx = new TransactionTemplate(manager);
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private long well(Scope s, boolean lock) {
        if (s.projectId() <= 0 || s.gasReservoirId() <= 0 || s.wellName() == null || s.wellName().isBlank()) bad("请先选择项目、气藏和井");
        var ids = jdbc.queryForList("SELECT id FROM project_well_heads WHERE project_id=? AND project_gas_reservoir_id=? AND well_name=?" + (lock ? " FOR UPDATE" : ""),
            Long.class, s.projectId(), s.gasReservoirId(), s.wellName());
        if (ids.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前项目、气藏无法唯一定位该井");
        return ids.getFirst();
    }
    public Created create(Start request, String actor, boolean importing) {
        return tx.execute(status -> {
            var scope = new Scope(request.projectId(), request.gasReservoirId(), request.wellName());
            long well = well(scope, true);
            WaterInvasionCarrierGuard.checkStorageBusy(jdbc,well);
            if(importing && WaterInvasionCarrierGuard.wasUsed(jdbc,well)) bad("此井曾作为库水侵承载井，旧平台结果可能属于库；请查看已保存的单井历史，不可直接导入旧结果");
            var existing = jdbc.queryForList("SELECT id,well_id,created_by,source_type FROM project_well_water_invasion WHERE request_id=?", request.requestId());
            if (!existing.isEmpty()) {
                var row = existing.getFirst();
                if (((Number)row.get("well_id")).longValue() != well || !actor.equals(row.get("created_by"))
                    || !(importing ? "IMPORT" : "CALCULATE").equals(row.get("source_type"))) bad("请求标识已被其它操作使用");
                long id = ((Number) row.get("id")).longValue();
                var saved = jdbc.queryForObject("SELECT request_snapshot_json FROM project_well_water_invasion_input WHERE water_invasion_id=?", String.class, id);
                if (!json.readValue(saved, Start.class).equals(request)) bad("相同请求标识不能修改计算条件");
                return new Created(task(id,scope), false);
            }
            expire(well);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM project_well_water_invasion WHERE active_well_id=?", Integer.class, well) > 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该井已有水侵分析任务，请等待完成后再操作");
            var values = new LinkedHashMap<String,Object>();
            values.put("project_id",scope.projectId()); values.put("gas_reservoir_id",scope.gasReservoirId());
            values.put("well_id",well); values.put("well_name_snapshot",scope.wellName());
            values.put("request_id",request.requestId()); values.put("source_type",importing ? "IMPORT" : "CALCULATE");
            values.put("task_status","RUNNING"); values.put("mapping_version",VERSION); values.put("created_by",actor);
            values.put("active_well_id",well); values.put("created_at",now());
            values.put("started_at",null); values.put("lease_expires_at",now().plusMinutes(12));
            long id = insert("project_well_water_invasion", values);
            var input = new LinkedHashMap<String,Object>(); input.put("water_invasion_id",id);
            input.put("request_snapshot_json",json.writeValueAsString(request));
            input.put("analysis_type",importing ? null : 1);
            input.put("is_use_actual_static_pressure",importing ? null : request.isUseActualStaticPressure());
            input.put("water_gas_ratio_limit",importing ? null : request.waterGasRatioLimit());
            insert("project_well_water_invasion_input",input);
            return new Created(task(id,scope),true);
        });
    }
    private void expire(long well) {
        jdbc.update("UPDATE project_well_water_invasion SET task_status='TIMED_OUT',error_message='后台任务超时或中断；已提交的旧平台计算需确认结束后再操作',active_well_id=CASE WHEN started_at IS NULL THEN NULL ELSE active_well_id END WHERE active_well_id=? AND task_status IN ('RUNNING','SAVING') AND lease_expires_at<?", well, now());
    }
    public List<Task> list(Scope scope) {
        long well = well(scope,false); expire(well);
        return jdbc.query("SELECT * FROM project_well_water_invasion WHERE well_id=? AND project_id=? AND gas_reservoir_id=? AND deleted_at IS NULL ORDER BY id DESC",
            (r,i)->readTask(r),well,scope.projectId(),scope.gasReservoirId());
    }
    /** 承载井重新算单井时，只使用本地单井快照，绝不沿用旧平台上的库级输入。 */
    public Map<String,Object> recalculationSnapshot(Scope scope) {
        long well=well(scope,false);
        if(!WaterInvasionCarrierGuard.wasUsed(jdbc,well))return null;
        var rows=jdbc.queryForList("SELECT i.response_snapshot_json FROM project_well_water_invasion w JOIN project_well_water_invasion_input i ON i.water_invasion_id=w.id WHERE w.well_id=? AND w.project_id=? AND w.gas_reservoir_id=? AND w.task_status='COMPLETED' AND w.deleted_at IS NULL ORDER BY w.id DESC LIMIT 1",well,scope.projectId(),scope.gasReservoirId());
        if(rows.isEmpty())bad("此承载井缺少已保存的单井输入，不能使用库级输入重新计算单井");
        return json.readValue((String)rows.getFirst().get("response_snapshot_json"),Map.class);
    }
    public Task task(long id, Scope scope) {
        long well = well(scope,false); expire(well);
        var rows=jdbc.query("SELECT * FROM project_well_water_invasion WHERE id=? AND well_id=? AND project_id=? AND gas_reservoir_id=? AND deleted_at IS NULL",
            (r,i)->readTask(r),id,well,scope.projectId(),scope.gasReservoirId());
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"当前井的水侵记录不存在");
        return rows.getFirst();
    }
    public Detail detail(long id, Scope scope) {
        Task record=task(id,scope);
        if(!record.taskStatus().equals("COMPLETED")) throw new ResponseStatusException(HttpStatus.CONFLICT,"该批次结果尚未成功保存");
        var input=jdbc.queryForMap("SELECT response_snapshot_json,is_use_actual_static_pressure FROM project_well_water_invasion_input WHERE water_invasion_id=?",id);
        // 快照也存于新数据库，含 influxInputItems 生产数据和 identifyInputItems 识别输入。
        // 按批次原样读取，页面切换历史时不会混入旧平台的最新生产数据；库后续查询结构化列。
        Map<String,Object> response=json.readValue((String)input.get("response_snapshot_json"),Map.class);
        Object prefer=input.get("is_use_actual_static_pressure");
        if(prefer!=null) map(response.get("input")).put("isUseActualStaticPressure",Boolean.TRUE.equals(prefer) || prefer instanceof Number n && n.intValue()!=0);
        var states=jdbc.queryForList("SELECT result_type,availability,has_summary,has_detail,has_chart FROM project_well_water_invasion_result WHERE water_invasion_id=?",id);
        response.put("storedSections",states);
        return new Detail(record,response);
    }
    public void save(long id, String wellName, Map<String,Object> response, boolean importing) {
        var sections=mapper.parse(response,wellName);
        tx.executeWithoutResult(status -> {
            var tasks=jdbc.queryForList("SELECT task_status,well_name_snapshot,source_type,lease_expires_at FROM project_well_water_invasion WHERE id=? FOR UPDATE",id);
            if(tasks.isEmpty() || !tasks.getFirst().get("task_status").equals("RUNNING")) throw new ResponseStatusException(HttpStatus.CONFLICT,"任务已结束，拒绝重复或过期写入");
            var task=tasks.getFirst();
            if(!wellName.equals(task.get("well_name_snapshot")) || !(importing?"IMPORT":"CALCULATE").equals(task.get("source_type"))) bad("结果与任务归属不一致");
            var lease=jdbc.queryForObject("SELECT lease_expires_at FROM project_well_water_invasion WHERE id=?",LocalDateTime.class,id);
            if(lease.isBefore(now())) throw new ResponseStatusException(HttpStatus.CONFLICT,"任务已过期，拒绝保存迟到结果");
            jdbc.update("UPDATE project_well_water_invasion SET task_status='SAVING' WHERE id=?",id);
            var input=map(response.get("input"));
            var values=new LinkedHashMap<String,Object>();
            values.put("legacy_input_id",legacyId(input.get("id")));
            for(String key:List.of("originalFormationPressure","formationTemperature","rockCompressionCoefficient","waterCompressionCoefficient","waterVolumeCoefficient","waterSaturation","reservoirOriginalGasVolume","currCumulativeGasProduction","reservoirAbandonmentPressure","waterGasRatioLimit","specificGravity","hydrogenSulfide","carbonDioxide","nitrogen","modificationMethod","deviationFactorMethod","viscosityMethod"))
                values.put(snake(key),number(input.get(key)));
            values.put("gas_type",input.get("gasType"));
            values.put("condensate_oil_density",number(input.get("condensateOilDensityUnderStandardCondition")));
            values.put("response_snapshot_json",json.writeValueAsString(response));
            updateInput(id,values);
            int available=0;
            for(int i=0;i<sections.size();i++) {
                var section=sections.get(i); if(section.available()) available++;
                var result=new LinkedHashMap<String,Object>(); result.put("water_invasion_id",id); result.put("result_type",section.type());
                result.put("legacy_analysis_id",legacyId(section.raw().get("analysisId"))); result.put("legacy_output_id",legacyId(section.output().get("id")));
                result.put("availability",section.available()?"HAS_DATA":"NO_DATA");
                result.put("has_summary",section.hasSummary()); result.put("has_detail",section.hasDetail()); result.put("has_chart",section.hasChart());
                result.put("chart_items_json",section.raw().get("chartItems")==null ? null : json.writeValueAsString(section.raw().get("chartItems")));
                List<String> summaryKeys=switch(i) {
                    case 0 -> List.of("originalGasVolume","waterInvasionState","waterInvasionStateDesc");
                    case 1 -> List.of("waterBodySize","undergroundGasVolume","waterBodySizeMultiple");
                    case 4 -> List.of("waterActiveness","waterActivenessDesc","abandonWaterInflux","reservoirOriginalVolume","waterInvasionReplacementCoefficient");
                    default -> List.of();
                };
                for(String key:summaryKeys) result.put(snake(key),key.endsWith("Desc") ? section.output().get(key) : number(section.output().get(key)));
                long resultId=insert("project_well_water_invasion_result",result);
                int sequence=0;
                for(var row:section.rows()) {
                    var detail=new LinkedHashMap<String,Object>(); detail.put("result_id",resultId); detail.put("sequence_no",sequence++);
                    detail.put("legacy_output_item_id",legacyId(row.get("id"))); detail.put("legacy_input_item_id",legacyId(row.get("WaterInvasionAnalysisInputItemId")));
                    detail.put("observation_time",date(row.get("date"))); detail.put("is_excluded",Boolean.TRUE.equals(row.get("isDeleted")));
                    var keys=new ArrayList<>(List.of("pressure","cumulativeGasProduction","cumulativeWaterProduction"));
                    if(i<3) keys.addAll(List.of("apparentPressure","theoreticalApparentPressure","recoveryDegree","theoreticalRecoveryDegree","deviationFactor"));
                    if(i==2) keys.add("waterInflux");
                    if(i==3) keys.addAll(List.of("gasDriveIndex","reservoirVolumetricDriveIndex","waterInvasionEnergyDriveIndex"));
                    for(String key:keys) detail.put(snake(key),number(row.get(key)));
                    insert("project_well_water_invasion_detail",detail);
                }
            }
            jdbc.update("UPDATE project_well_water_invasion SET task_status='COMPLETED',result_completeness=?,finished_at=?,saved_at=?,active_well_id=NULL,error_message=NULL WHERE id=?",
                available==5?"COMPLETE":available==0?"NONE":"PARTIAL",importing?null:now(),now(),id);
        });
    }
    public void fail(long id,String message,boolean timeout) {
        jdbc.update("UPDATE project_well_water_invasion SET task_status=?,error_message=?,active_well_id=NULL WHERE id=? AND task_status IN ('RUNNING','SAVING')",
            timeout?"TIMED_OUT":"FAILED",message,id);
    }
    public void submitted(long id) {
        if(jdbc.update("UPDATE project_well_water_invasion SET started_at=? WHERE id=? AND task_status='RUNNING' AND lease_expires_at>?",now(),id,now())!=1)bad("单井任务已过期，未提交旧算法");
    }
    public void failSubmitted(long id,String message,boolean timeout) {
        jdbc.update("UPDATE project_well_water_invasion SET task_status=?,error_message=? WHERE id=? AND task_status IN ('RUNNING','SAVING')",timeout?"TIMED_OUT":"FAILED",message+"；承载井保持保护，请检查本次计算状态",id);
    }
    public record Recovery(Start request,Long startedAt,boolean blocked) {}
    public Recovery recovery(long id,Scope scope) {
        task(id,scope);
        var row=jdbc.queryForMap("SELECT w.started_at,w.active_well_id,i.request_snapshot_json FROM project_well_water_invasion w JOIN project_well_water_invasion_input i ON i.water_invasion_id=w.id WHERE w.id=?",id);
        var time=jdbc.queryForObject("SELECT started_at FROM project_well_water_invasion WHERE id=?",LocalDateTime.class,id);
        return new Recovery(json.readValue((String)row.get("request_snapshot_json"),Start.class),time==null?null:time.toInstant(ZoneOffset.UTC).toEpochMilli(),row.get("active_well_id")!=null);
    }
    public void saveRecovered(long id,Scope scope,Map<String,Object> result) {
        tx.executeWithoutResult(status->{
            well(scope,true);var task=task(id,scope);
            if(!List.of("FAILED","TIMED_OUT").contains(task.taskStatus()))bad("任务状态已变化，请刷新记录");
            if(!recovery(id,scope).blocked())bad("此任务未占用承载井，不可重新保存");
            jdbc.update("UPDATE project_well_water_invasion SET task_status='RUNNING',lease_expires_at=? WHERE id=?",now().plusMinutes(2),id);
            save(id,scope.wellName(),result,false);
        });
    }
    public void delete(long id,Scope scope) {
        tx.executeWithoutResult(status -> {
            well(scope,true); var task=task(id,scope);
            if(List.of("RUNNING","SAVING").contains(task.taskStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT,"运行中的任务不能删除");
            if(recovery(id,scope).blocked())throw new ResponseStatusException(HttpStatus.CONFLICT,"本次旧平台任务尚未确认结束，请先检查计算状态，不能隐藏承载井保护记录");
            jdbc.update("UPDATE project_well_water_invasion SET deleted_at=? WHERE id=?",now(),id);
        });
    }
    private long insert(String table,LinkedHashMap<String,Object> values) {
        var key=new GeneratedKeyHolder();
        String sql="INSERT INTO "+table+"("+String.join(",",values.keySet())+") VALUES("+String.join(",",Collections.nCopies(values.size(),"?"))+")";
        jdbc.update(c->{var p=c.prepareStatement(sql,new String[]{"id"}); int i=1; for(Object value:values.values())p.setObject(i++,value); return p;},key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private void updateInput(long id,LinkedHashMap<String,Object> values) {
        var args=new ArrayList<>(values.values());args.add(id);
        jdbc.update("UPDATE project_well_water_invasion_input SET "+String.join(",",values.keySet().stream().map(k->k+"=?").toList())+" WHERE water_invasion_id=?",args.toArray());
    }
    private static String snake(String name){return name.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);}
    private static Task readTask(ResultSet r) throws SQLException {
        return new Task(r.getLong("id"),r.getString("well_name_snapshot"),r.getString("source_type"),r.getString("task_status"),r.getString("result_completeness"),r.getString("error_message"),time(r,"created_at"),time(r,"finished_at"),time(r,"saved_at"));
    }
    private static String time(ResultSet r,String key) throws SQLException {var value=r.getObject(key,LocalDateTime.class); return value==null?null:value+"Z";}
    private static void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
