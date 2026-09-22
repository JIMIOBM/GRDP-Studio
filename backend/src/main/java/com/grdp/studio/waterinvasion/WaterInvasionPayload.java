package com.grdp.studio.waterinvasion;

import java.util.*;
import static com.grdp.studio.waterinvasion.WaterInvasionResultMapper.*;

/** 计算负载和结果核对共用白名单，旧记录 ID 不得随输入再次提交。 */
public final class WaterInvasionPayload {
    private WaterInvasionPayload() {}
    public static final List<String> INPUT_KEYS=List.of("gasType","specificGravity","hydrogenSulfide","carbonDioxide","nitrogen",
        "modificationMethod","deviationFactorMethod","viscosityMethod","originalFormationPressure","formationTemperature",
        "rockCompressionCoefficient","waterCompressionCoefficient","waterSaturation","waterGasRatioLimit","reservoirOriginalGasVolume",
        "currCumulativeGasProduction","reservoirAbandonmentPressure","waterVolumeCoefficient","condensateOilDensityUnderStandardCondition");
    public static Map<String,Object> fromSnapshot(WaterInvasionModels.Scope scope,Map<String,Object> snapshot,double limit) {
        var body=new LinkedHashMap<String,Object>(); body.put("wellName",scope.wellName()); body.put("projectId",scope.projectId()); body.put("gasReservoirId",scope.gasReservoirId()); body.put("analysisId",null);
        var input=new LinkedHashMap<String,Object>(); var saved=map(snapshot.get("input"));
        for(String key:INPUT_KEYS) if(saved.containsKey(key) && saved.get(key)!=null) input.put(key,saved.get(key));
        input.put("waterGasRatioLimit",limit);body.put("input",input);
        for(String key:List.of("identifyInputItems","influxInputItems")) {
            var rows=new ArrayList<Map<String,Object>>();
            for(var row:objects(snapshot.get(key))) {
                if(Boolean.TRUE.equals(row.get("isDeleted")))continue;
                var clean=new LinkedHashMap<String,Object>();
                for(String field:key.equals("influxInputItems")?List.of("date","pressure","dailyGasProduction","cumulativeGasProduction","cumulativeWaterProduction"):List.of("date","pressure","cumulativeGasProduction","cumulativeWaterProduction")) clean.put(field,row.get(field));
                rows.add(clean);
            }
            body.put(key,rows);
        }
        return body;
    }
    public static boolean matches(Map<String,Object> payload,Map<String,Object> response) {
        if(response==null || !Objects.equals(payload.get("wellName"),response.get("wellName"))) return false;
        if(!contains(map(payload.get("input")),map(response.get("input"))))return false;
        for(String key:List.of("identifyInputItems","influxInputItems")) {
            if(!(response.get(key) instanceof List<?>))return false;
            var expected=objects(payload.get(key));var actual=objects(response.get(key));
            if(expected.size()!=actual.size())return false;
            for(int i=0;i<expected.size();i++)if(!contains(expected.get(i),actual.get(i)))return false;
        }
        return true;
    }
    private static boolean contains(Map<String,Object> expected,Map<String,Object> actual) {
        for(var entry:expected.entrySet()) {
            Object x=entry.getValue(),y=actual.get(entry.getKey());
            if(entry.getKey().equals("date")) { if(date(x)==null || !date(x).equals(date(y))) return false; }
            else if(x instanceof Number) {
                Double a=number(x),b=number(y);
                if(a==null || b==null || Math.abs(a-b)>1e-9*Math.max(1,Math.abs(a)))return false;
            } else if(!Objects.equals(x,y))return false;
        }
        return true;
    }
}
