package com.grdp.studio.wellbore.risk.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.wellbore.risk.dto.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class WellboreRiskStorageService {
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    private final LiquidLoadingCalculator liquidCalculator; private final HydrateCalculator hydrateCalculator;
    public WellboreRiskStorageService(JdbcTemplate jdbc,ObjectMapper json,LiquidLoadingCalculator liquidCalculator,HydrateCalculator hydrateCalculator){this.jdbc=jdbc;this.json=json;this.liquidCalculator=liquidCalculator;this.hydrateCalculator=hydrateCalculator;}

    @Transactional
    public Map<String,Object> saveLiquid(LiquidLoadingSaveRequest save){
        LiquidLoadingRequest req=save.calculation(); long wellId=wellId(req.projectId(),req.gasReservoirId(),req.wellName());
        LiquidLoadingResult r=liquidCalculator.calculate(req); int no=nextNo("project_well_liquid_loading",wellId);
        KeyHolder key=new GeneratedKeyHolder(); String sql="""
                INSERT INTO project_well_liquid_loading(well_id,calculation_no,calculation_name,q_gas_1e4_m3d,q_water_m3d,
                pressure_mpa,temperature_c,gas_specific_gravity,liquid_density_kg_m3,surface_tension_mn_m,tubing_id_mm,
                gas_density_kg_m3,z_factor,actual_velocity_m_s,critical_velocity_m_s,critical_rate_1e4_m3d,velocity_ratio,
                critical_velocity_turner_m_s,critical_velocity_turner20_m_s,critical_velocity_limin_m_s,ratio_turner,ratio_turner20,ratio_limin,
                liquid_loading_level,level_key,input_json,result_json,remark) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        jdbc.update(c->{PreparedStatement p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);int i=1;
            p.setLong(i++,wellId);p.setInt(i++,no);p.setString(i++,name(save.calculationName(),"积液方案"+no));p.setDouble(i++,r.qg());
            if(r.qw()==null)p.setNull(i++,java.sql.Types.DOUBLE);else p.setDouble(i++,r.qw());
            p.setDouble(i++,r.pressureMpa());p.setDouble(i++,r.temperatureC());p.setDouble(i++,r.gasSpecificGravity());p.setDouble(i++,r.liquidDensityKgM3());p.setDouble(i++,r.surfaceTensionMnM());p.setDouble(i++,r.tubingIdMm());
            p.setDouble(i++,r.gasDensityKgM3());p.setDouble(i++,r.zFactor());p.setDouble(i++,r.actualVelocityMs());p.setDouble(i++,r.criticalVelocityMs());p.setDouble(i++,r.criticalRate1e4M3d());p.setDouble(i++,r.ratio());
            p.setDouble(i++,r.criticalVelocityTurnerMs());p.setDouble(i++,r.criticalVelocityTurner20Ms());p.setDouble(i++,r.criticalVelocityLiMinMs());p.setDouble(i++,r.ratioTurner());p.setDouble(i++,r.ratioTurner20());p.setDouble(i++,r.ratioLiMin());
            p.setString(i++,r.level());p.setString(i++,r.levelKey());p.setString(i++,write(req));p.setString(i++,write(r));p.setString(i,blank(save.remark()));return p;},key);
        return liquidDetail(key.getKey().longValue(),wellId);
    }

    public List<Map<String,Object>> liquidList(long projectId,long reservoirId,String wellName){return list("project_well_liquid_loading",wellId(projectId,reservoirId,wellName),"liquid_loading_level");}
    public Map<String,Object> liquidDetail(long id,long projectId,long reservoirId,String wellName){return liquidDetail(id,wellId(projectId,reservoirId,wellName));}
    private Map<String,Object> liquidDetail(long id,long wellId){return one("SELECT *,calculation_no AS calculationNo,calculation_name AS calculationName,liquid_loading_level AS liquidLoadingLevel FROM project_well_liquid_loading WHERE id=? AND well_id=?",id,wellId);}
    public void deleteLiquid(long id,long projectId,long reservoirId,String wellName){delete("project_well_liquid_loading",id,wellId(projectId,reservoirId,wellName));}

    @Transactional
    public Map<String,Object> saveHydrate(HydrateSaveRequest save){
        HydrateRequest req=save.calculation();long wellId=wellId(req.projectId(),req.gasReservoirId(),req.wellName());validateSources(req,wellId);
        HydrateResult r=hydrateCalculator.calculate(req);int no=nextNo("project_well_hydrate_prediction",wellId);KeyHolder key=new GeneratedKeyHolder();
        String sql="""
                INSERT INTO project_well_hydrate_prediction(well_id,pvt_id,temperature_id,pressure_conversion_id,calculation_no,calculation_name,
                pressure_mpa,actual_temperature_c,fugacity_scale,raw_hydrate_temperature_c,hydrate_temperature_c,temperature_margin_c,risk_level,risk_description,
                correction_factor,method_name,input_json,result_json,remark) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        jdbc.update(c->{PreparedStatement p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);int i=1;p.setLong(i++,wellId);nullableLong(p,i++,req.pvtId());nullableLong(p,i++,req.temperatureId());nullableLong(p,i++,req.pressureConversionId());p.setInt(i++,no);p.setString(i++,name(save.calculationName(),"水合物方案"+no));p.setDouble(i++,r.pressureMpa());p.setDouble(i++,r.actualTemperatureC());p.setDouble(i++,r.fugacityScale());p.setDouble(i++,r.rawHydrateTemperatureC());p.setDouble(i++,r.hydrateTemperatureC());p.setDouble(i++,r.temperatureMarginC());p.setString(i++,r.riskLevel());p.setString(i++,r.riskDescription());p.setDouble(i++,r.temperatureCorrectionFactor());p.setString(i++,r.method());p.setString(i++,write(req));p.setString(i++,write(r));p.setString(i,blank(save.remark()));return p;},key);
        long id=key.getKey().longValue();LinkedHashMap<String,Double> normalized=hydrateCalculator.parseComposition(req.composition());double matchedTotal=r.matchedComponents().values().stream().mapToDouble(Double::doubleValue).sum();int index=0;
        for(var e:normalized.entrySet()){boolean participates=r.matchedComponents().containsKey(e.getKey());Double raw=findRaw(req.composition(),e.getKey());jdbc.update("INSERT INTO project_well_hydrate_component(hydrate_prediction_id,component_no,component_name,input_value,normalized_fraction,model_fraction,model_participating) VALUES(?,?,?,?,?,?,?)",id,index++,e.getKey(),raw==null?e.getValue():raw,e.getValue(),participates?e.getValue()/matchedTotal:null,participates);}
        return hydrateDetail(id,wellId);
    }

    public List<Map<String,Object>> hydrateList(long projectId,long reservoirId,String wellName){return list("project_well_hydrate_prediction",wellId(projectId,reservoirId,wellName),"risk_level");}
    public Map<String,Object> hydrateDetail(long id,long projectId,long reservoirId,String wellName){return hydrateDetail(id,wellId(projectId,reservoirId,wellName));}
    private Map<String,Object> hydrateDetail(long id,long wellId){Map<String,Object> result=new LinkedHashMap<>(one("SELECT *,calculation_no AS calculationNo,calculation_name AS calculationName,risk_level AS riskLevel FROM project_well_hydrate_prediction WHERE id=? AND well_id=?",id,wellId));result.put("components",jdbc.queryForList("SELECT component_no AS componentNo,component_name AS componentName,input_value AS inputValue,normalized_fraction AS normalizedFraction,model_fraction AS modelFraction,model_participating AS modelParticipating FROM project_well_hydrate_component WHERE hydrate_prediction_id=? ORDER BY component_no",id));return result;}
    public void deleteHydrate(long id,long projectId,long reservoirId,String wellName){delete("project_well_hydrate_prediction",id,wellId(projectId,reservoirId,wellName));}

    private List<Map<String,Object>> list(String table,long wellId,String statusColumn){return jdbc.queryForList("SELECT id,calculation_no AS calculationNo,calculation_name AS calculationName,"+statusColumn+" AS status,created_at AS createdAt FROM "+table+" WHERE well_id=? ORDER BY calculation_no DESC",wellId);}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);if(rows.size()!=1)throw new BusinessException(404,"未找到当前井计算方案");return rows.getFirst();}
    private void delete(String table,long id,long wellId){int count=jdbc.update("DELETE FROM "+table+" WHERE id=? AND well_id=?",id,wellId);if(count==0)throw new BusinessException(404,"未找到当前井计算方案");}
    private int nextNo(String table,long wellId){Integer value=jdbc.queryForObject("SELECT COALESCE(MAX(calculation_no),0)+1 FROM "+table+" WHERE well_id=? FOR UPDATE",Integer.class,wellId);return value==null?1:value;}
    private long wellId(Long projectId,Long reservoirId,String wellName){if(projectId==null||reservoirId==null||wellName==null||wellName.isBlank())throw new BusinessException(400,"项目、气藏和井名不能为空");List<Long> ids=jdbc.queryForList("SELECT id FROM project_well_heads WHERE project_id=? AND project_gas_reservoir_id=? AND well_name=?",Long.class,projectId,reservoirId,wellName.trim());if(ids.size()!=1)throw new BusinessException(ids.isEmpty()?404:409,"当前项目、气藏和井名无法唯一定位井记录");return ids.getFirst();}
    private void validateSources(HydrateRequest r,long wellId){validateRef("project_well_pvt",r.pvtId(),wellId,"PVT");validateRef("project_well_temperature",r.temperatureId(),wellId,"温度方案");validateRef("project_well_pressure_conversion",r.pressureConversionId(),wellId,"压力方案");}
    private void validateRef(String table,Long id,long wellId,String label){if(id==null)return;Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=? AND well_id=?",Integer.class,id,wellId);if(count==null||count==0)throw new BusinessException(400,label+"不属于当前井");}
    private static void nullableLong(PreparedStatement p,int index,Long value)throws java.sql.SQLException{if(value==null)p.setNull(index,java.sql.Types.BIGINT);else p.setLong(index,value);}
    private Double findRaw(Map<String,Double> raw,String normalizedName){for(var e:raw.entrySet()){String key=e.getKey().trim().toUpperCase(Locale.ROOT).replace(" ","");if((key.equals(normalizedName))||(normalizedName.equals("C1")&&key.equals("CH4"))||(normalizedName.equals("C2")&&key.equals("C2H6"))||(normalizedName.equals("C3")&&key.equals("C3H8")))return e.getValue();}return null;}
    private String write(Object v){try{return json.writeValueAsString(v);}catch(JacksonException e){throw new BusinessException(500,"计算快照序列化失败");}}
    private static String name(String v,String fallback){return v==null||v.isBlank()?fallback:v.trim();}private static String blank(String v){return v==null||v.isBlank()?null:v.trim();}
}
