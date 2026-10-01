package com.grdp.studio.storagewaterinvasion;

import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import com.grdp.studio.waterinvasion.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.sql.*;
import java.util.*;
import static com.grdp.studio.storagewaterinvasion.StorageWaterInvasionModels.*;
import static com.grdp.studio.waterinvasion.WaterInvasionResultMapper.*;

/** 库级五张表的唯一读写入口。来源固定到单井历史批次，不会改写任何单井计算结果。 */
@Service
public class StorageWaterInvasionStorage {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final StorageCatalogService catalog;
    private final StorageWaterInvasionCalculator calculator;
    private final WaterInvasionResultMapper mapper;
    private final TransactionTemplate tx;
    public StorageWaterInvasionStorage(JdbcTemplate jdbc,ObjectMapper json,StorageCatalogService catalog,
        StorageWaterInvasionCalculator calculator,WaterInvasionResultMapper mapper,PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.json=json; this.catalog=catalog; this.calculator=calculator; this.mapper=mapper; tx=new TransactionTemplate(manager);
    }
    private static LocalDateTime now(){return LocalDateTime.now(ZoneOffset.UTC);}
    private void scope(Scope s){catalog.requireScope(s.projectId(),s.gasReservoirId(),s.storageId());}
    private List<StorageCatalogService.Well> members(Scope s){return catalog.wells(s.storageId(),s.projectId(),s.gasReservoirId());}
    public List<Map<String,Object>> candidates(Scope s) {
        scope(s); var result=new ArrayList<Map<String,Object>>();
        for(var well:members(s)) {
            var records=jdbc.queryForList("SELECT w.id,w.created_at,w.saved_at,r.original_gas_volume AS dynamic_gas_volume FROM project_well_water_invasion w JOIN project_well_water_invasion_result r ON r.water_invasion_id=w.id AND r.result_type='IDENTIFICATION' WHERE w.project_id=? AND w.gas_reservoir_id=? AND w.well_id=? AND w.task_status='COMPLETED' AND w.deleted_at IS NULL ORDER BY w.id DESC",s.projectId(),s.gasReservoirId(),well.id());
            for(var record:records)for(String key:List.of("created_at","saved_at")) {
                Object value=record.get(key);
                if(value instanceof Timestamp time)record.put(key,time.toLocalDateTime()+"Z");
                else if(value instanceof LocalDateTime time)record.put(key,time+"Z");
            }
            var item=new LinkedHashMap<String,Object>();item.put("wellId",well.id());item.put("wellName",well.wellName());item.put("records",records); result.add(item);
        }
        return result;
    }
    record Prepared(String name,List<StorageCatalogService.Well> members,List<Source> sources,Aggregate aggregate) {}
    private Prepared prepare(Start r) {
        scope(r.scope()); var wells=members(r.scope());
        if(wells.isEmpty())bad("当前库没有井，无法选择承载井");
        if(r.sources()==null || r.sources().isEmpty() || r.sources().size()>2000)bad("请选择参与计算的单井历史批次");
        var selected=new ArrayList<Source>();var seen=new HashSet<Long>();long rowCount=0;
        for(var selection:r.sources()) {
            if(!seen.add(selection.wellId()))bad("同一口井只能选择一个来源批次");
            var well=wells.stream().filter(w->w.id()==selection.wellId()).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"所选井不属于当前库"));
            var rows=jdbc.queryForList("SELECT i.response_snapshot_json,r.original_gas_volume FROM project_well_water_invasion w JOIN project_well_water_invasion_input i ON i.water_invasion_id=w.id JOIN project_well_water_invasion_result r ON r.water_invasion_id=w.id AND r.result_type='IDENTIFICATION' WHERE w.id=? AND w.well_id=? AND w.project_id=? AND w.gas_reservoir_id=? AND w.task_status='COMPLETED' AND w.deleted_at IS NULL",selection.recordId(),well.id(),r.projectId(),r.gasReservoirId());
            if(rows.size()!=1)bad(well.wellName()+" 的来源批次不存在、不属于该井或尚未成功保存");
            var row=rows.getFirst();Double gas=number(row.get("original_gas_volume"));
            if(gas==null || gas<=0)bad(well.wellName()+" 缺少有效正数动态储量，不能作为加权来源");
            var snapshot=json.readValue((String)row.get("response_snapshot_json"),Map.class);
            rowCount+=objects(snapshot.get("identifyInputItems")).size()+objects(snapshot.get("influxInputItems")).size();
            if(rowCount>500000)bad("所选来源输入超过 50 万条，请减少参与井后计算；未截断数据");
            if(!well.wellName().equals(snapshot.get("wellName")))bad("来源快照井名不匹配");
            selected.add(new Source(well.id(),well.wellName(),selection.recordId(),gas,snapshot));
        }
        // 覆盖旧平台前先确保承载井已有可恢复的单井输入快照。
        int carrierSaved=jdbc.queryForObject("SELECT COUNT(*) FROM project_well_water_invasion w JOIN project_well_water_invasion_input i ON i.water_invasion_id=w.id WHERE w.well_id=? AND w.project_id=? AND w.gas_reservoir_id=? AND w.task_status='COMPLETED' AND w.deleted_at IS NULL AND i.response_snapshot_json IS NOT NULL",Integer.class,wells.getFirst().id(),r.projectId(),r.gasReservoirId());
        if(carrierSaved==0)bad("承载井 "+wells.getFirst().wellName()+" 尚无本地单井输入，请先保存该井的水侵结果再进行库计算");
        String name=jdbc.queryForObject("SELECT storage_name FROM project_storage WHERE id=?",String.class,r.storageId());
        return new Prepared(name,wells,selected,calculator.aggregate(r.scope(),wells.getFirst().wellName(),selected,r.waterGasRatioLimit()));
    }
    public Preview preview(Start request){var p=prepare(request);return new Preview(p.name(),p.members().getFirst().wellName(),p.aggregate());}
    public Created create(Start request,String actor) {
        return tx.execute(status->{
            scope(request.scope());
            var existing=jdbc.queryForList("SELECT w.id,w.storage_id,w.project_id,w.gas_reservoir_id,w.created_by,i.client_request_json FROM project_storage_water_invasion w JOIN project_storage_water_invasion_input i ON i.water_invasion_id=w.id WHERE w.request_id=?",request.requestId());
            if(!existing.isEmpty()) {
                var row=existing.getFirst();
                if(!actor.equals(row.get("created_by")) || !json.readValue((String)row.get("client_request_json"),Start.class).equals(request))bad("相同请求标识不能修改库、来源或计算条件");
                return new Created(task(((Number)row.get("id")).longValue(),request.scope()),false);
            }
            var p=prepare(request);var carrier=p.members().getFirst();
            jdbc.queryForObject("SELECT id FROM project_well_heads WHERE id=? FOR UPDATE",Long.class,carrier.id());
            StorageCatalogService.lockScope(jdbc,request.projectId(),request.gasReservoirId(),request.storageId());
            WaterInvasionCarrierGuard.checkStorageBusy(jdbc,carrier.id());
            // 不跨模块释放已超时的井任务，旧平台可能仍在计算。
            if(jdbc.queryForObject("SELECT COUNT(*) FROM project_well_water_invasion WHERE active_well_id=?",Integer.class,carrier.id())>0) conflict("承载井正在执行单井任务，请等待其完成");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM project_storage_water_invasion WHERE active_storage_id=?",Integer.class,request.storageId())>0)conflict("该库已有运行任务");
            var values=new LinkedHashMap<String,Object>();
            values.put("project_id",request.projectId());values.put("gas_reservoir_id",request.gasReservoirId());values.put("storage_id",request.storageId());values.put("storage_name_snapshot",p.name());
            values.put("carrier_well_id",carrier.id());values.put("carrier_well_name_snapshot",carrier.wellName());values.put("request_id",request.requestId());values.put("created_by",actor);
            values.put("task_status","RUNNING");values.put("aggregation_version",StorageWaterInvasionCalculator.VERSION);values.put("active_storage_id",request.storageId());values.put("active_carrier_well_id",carrier.id());
            values.put("created_at",now());values.put("lease_expires_at",now().plusMinutes(12));
            long id=insert("project_storage_water_invasion",values);
            var input=new LinkedHashMap<String,Object>();input.put("water_invasion_id",id);
            input.put("client_request_json",json.writeValueAsString(request));input.put("request_snapshot_json",json.writeValueAsString(p.aggregate().payload()));
            input.put("aggregation_rules_json",json.writeValueAsString(p.aggregate().rules()));input.put("date_alignment_policy","UNION_AVAILABLE_WEIGHTED");
            var parameters=map(p.aggregate().payload().get("input"));
            for(var key:WaterInvasionPayload.INPUT_KEYS) if(parameters.containsKey(key))input.put(key.equals("condensateOilDensityUnderStandardCondition")?"condensate_oil_density":snake(key),parameters.get(key));
            insert("project_storage_water_invasion_input",input);
            for(var well:p.members()) {
                int position=-1;for(int i=0;i<p.sources().size();i++)if(p.sources().get(i).wellId()==well.id())position=i;
                var source=new LinkedHashMap<String,Object>();source.put("water_invasion_id",id);source.put("well_id",well.id());source.put("well_name_snapshot",well.wellName());
                source.put("participation_status",position<0?"EXCLUDED":"INCLUDED");source.put("excluded_reason",position<0?"本次未选择该井":null);
                if(position>=0){var s=p.sources().get(position);source.put("source_water_invasion_id",s.recordId());source.put("dynamic_gas_volume",s.dynamicGasVolume());source.put("weight",p.aggregate().weights().get(position));}
                insert("project_storage_water_invasion_source",source);
            }
            return new Created(task(id,request.scope()),true);
        });
    }
    public List<Task> list(Scope s) {
        scope(s);expire(s);
        return jdbc.query("SELECT * FROM project_storage_water_invasion WHERE storage_id=? AND project_id=? AND gas_reservoir_id=? AND deleted_at IS NULL ORDER BY id DESC",(rs,n)->readTask(rs),s.storageId(),s.projectId(),s.gasReservoirId());
    }
    private void expire(Scope s) {
        jdbc.update("UPDATE project_storage_water_invasion SET task_status='TIMED_OUT',error_message='任务超时或服务重启，请检查计算状态；已提交的承载井仍被保护',active_storage_id=NULL,active_carrier_well_id=CASE WHEN legacy_submitted_at IS NULL THEN NULL ELSE active_carrier_well_id END WHERE storage_id=? AND task_status IN ('RUNNING','SAVING') AND lease_expires_at<?",s.storageId(),now());
    }
    public Task task(long id,Scope s) {
        scope(s);expire(s);
        var rows=jdbc.query("SELECT * FROM project_storage_water_invasion WHERE id=? AND storage_id=? AND project_id=? AND gas_reservoir_id=? AND deleted_at IS NULL",(rs,n)->readTask(rs),id,s.storageId(),s.projectId(),s.gasReservoirId());
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"当前库不存在此分析批次");return rows.getFirst();
    }
    public Detail detail(long id,Scope s) {
        var task=task(id,s);var row=jdbc.queryForMap("SELECT request_snapshot_json,response_snapshot_json,aggregation_rules_json FROM project_storage_water_invasion_input WHERE water_invasion_id=?",id);
        Map<String,Object> result=row.get("response_snapshot_json")==null?null:json.readValue((String)row.get("response_snapshot_json"),Map.class);
        if(result!=null)result.put("storedSections",jdbc.queryForList("SELECT result_type,availability,has_summary,has_detail,has_chart FROM project_storage_water_invasion_result WHERE water_invasion_id=?",id));
        return new Detail(task,json.readValue((String)row.get("request_snapshot_json"),Map.class),json.readValue((String)row.get("aggregation_rules_json"),Map.class),
            jdbc.queryForList("SELECT * FROM project_storage_water_invasion_source WHERE water_invasion_id=? ORDER BY id",id),result);
    }
    public long submitted(long id) {
        var time=now();
        int changed=jdbc.update("UPDATE project_storage_water_invasion SET started_at=?,legacy_submitted_at=? WHERE id=? AND task_status='RUNNING' AND lease_expires_at>? AND legacy_submitted_at IS NULL",time,time,id,time);
        if(changed!=1)conflict("任务已过期或已经提交，拒绝重复调用旧算法");
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
    public Long submittedAt(long id){var time=jdbc.queryForObject("SELECT legacy_submitted_at FROM project_storage_water_invasion WHERE id=?",LocalDateTime.class,id);return time==null?null:time.toInstant(ZoneOffset.UTC).toEpochMilli();}
    public void fail(long id,String message,boolean timeout,boolean submitted) {
        jdbc.update("UPDATE project_storage_water_invasion SET task_status=?,error_message=?,active_storage_id=NULL,active_carrier_well_id=CASE WHEN ? THEN active_carrier_well_id ELSE NULL END WHERE id=? AND task_status IN ('RUNNING','SAVING')",timeout?"TIMED_OUT":"FAILED",message,submitted,id);
    }
    public void save(long id,String carrier,Map<String,Object> response) {
        var sections=mapper.parse(response,carrier);
        tx.executeWithoutResult(status->{
            var row=jdbc.queryForMap("SELECT task_status,carrier_well_name_snapshot,active_carrier_well_id,legacy_submitted_at FROM project_storage_water_invasion WHERE id=? FOR UPDATE",id);
            if(row.get("active_carrier_well_id")==null || !carrier.equals(row.get("carrier_well_name_snapshot")))conflict("任务已结束或承载井不匹配");
            if(row.get("legacy_submitted_at")==null)conflict("任务尚未提交旧平台，不能保存为计算结果");
            var request=json.readValue(jdbc.queryForObject("SELECT request_snapshot_json FROM project_storage_water_invasion_input WHERE water_invasion_id=?",String.class,id),Map.class);
            if(!WaterInvasionPayload.matches(request,response))conflict("旧平台结果与本批次完整输入不匹配，未保存");
            jdbc.update("UPDATE project_storage_water_invasion SET task_status='SAVING' WHERE id=?",id);
            jdbc.update("UPDATE project_storage_water_invasion_input SET response_snapshot_json=? WHERE water_invasion_id=?",json.writeValueAsString(response),id);
            persistSections(id,sections);
            long available=sections.stream().filter(Section::available).count();
            jdbc.update("UPDATE project_storage_water_invasion SET task_status='COMPLETED',result_completeness=?,finished_at=?,saved_at=?,active_storage_id=NULL,active_carrier_well_id=NULL,error_message=NULL WHERE id=?",available==5?"COMPLETE":available==0?"NONE":"PARTIAL",now(),now(),id);
        });
    }
    private void persistSections(long id,List<Section> sections) {
        for(int i=0;i<sections.size();i++) {
            var section=sections.get(i);var result=new LinkedHashMap<String,Object>();result.put("water_invasion_id",id);result.put("result_type",section.type());
            result.put("legacy_analysis_id",legacyId(section.raw().get("analysisId")));result.put("legacy_output_id",legacyId(section.output().get("id")));
            result.put("availability",section.available()?"HAS_DATA":"NO_DATA");result.put("has_summary",section.hasSummary());result.put("has_detail",section.hasDetail());result.put("has_chart",section.hasChart());
            result.put("chart_items_json",section.raw().get("chartItems")==null?null:json.writeValueAsString(section.raw().get("chartItems")));
            var keys=switch(i){case 0->List.of("originalGasVolume","waterInvasionState","waterInvasionStateDesc");case 1->List.of("waterBodySize","undergroundGasVolume","waterBodySizeMultiple");case 4->List.of("waterActiveness","waterActivenessDesc","abandonWaterInflux","reservoirOriginalVolume","waterInvasionReplacementCoefficient");default->List.<String>of();};
            for(String key:keys)result.put(snake(key),key.endsWith("Desc")?section.output().get(key):number(section.output().get(key)));
            long resultId=insert("project_storage_water_invasion_result",result);int sequence=0;
            for(var item:section.rows()) {
                var detail=new LinkedHashMap<String,Object>();detail.put("result_id",resultId);detail.put("sequence_no",sequence++);detail.put("observation_time",date(item.get("date")));detail.put("is_excluded",Boolean.TRUE.equals(item.get("isDeleted")));
                detail.put("legacy_output_item_id",legacyId(item.get("id")));detail.put("legacy_input_item_id",legacyId(item.get("WaterInvasionAnalysisInputItemId")));
                var fields=new ArrayList<>(List.of("pressure","cumulativeGasProduction","cumulativeWaterProduction"));
                if(i<3)fields.addAll(List.of("apparentPressure","theoreticalApparentPressure","recoveryDegree","theoreticalRecoveryDegree","deviationFactor"));
                if(i==2)fields.add("waterInflux");if(i==3)fields.addAll(List.of("gasDriveIndex","reservoirVolumetricDriveIndex","waterInvasionEnergyDriveIndex"));
                for(String key:fields)detail.put(snake(key),number(item.get(key)));insert("project_storage_water_invasion_detail",detail);
            }
        }
    }
    private long insert(String table,LinkedHashMap<String,Object> values) {
        var key=new GeneratedKeyHolder();
        jdbc.update(c->{var p=c.prepareStatement("INSERT INTO "+table+"("+String.join(",",values.keySet())+") VALUES("+String.join(",",Collections.nCopies(values.size(),"?"))+")",new String[]{"id"});int i=1;for(Object value:values.values())p.setObject(i++,value);return p;},key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private Task readTask(ResultSet r)throws SQLException{return new Task(r.getLong("id"),r.getLong("storage_id"),r.getString("storage_name_snapshot"),r.getString("carrier_well_name_snapshot"),r.getString("task_status"),r.getString("result_completeness"),r.getString("error_message"),r.getObject("active_carrier_well_id")!=null,time(r,"created_at"),time(r,"finished_at"));}
    private static String time(ResultSet r,String key)throws SQLException{var value=r.getObject(key,LocalDateTime.class);return value==null?null:value+"Z";}
    private static String snake(String key){return key.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);}
    private static void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static void conflict(String message){throw new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
