package com.grdp.studio.storagewaterinvasion;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;
import static com.grdp.studio.waterinvasion.WaterInvasionResultMapper.*;
import static com.grdp.studio.storagewaterinvasion.StorageWaterInvasionModels.*;

/** 取日期并集，各字段按当日有值井的动态储量重新归一化。所有生产数值均加权，不求和、不补零。 */
@Component
public class StorageWaterInvasionCalculator {
    public static final String VERSION = "storage-water-v1-union-weighted";
    public static final List<String> WEIGHTED = List.of("specificGravity", "hydrogenSulfide", "carbonDioxide", "nitrogen",
        "originalFormationPressure", "formationTemperature", "rockCompressionCoefficient", "waterCompressionCoefficient",
        "waterSaturation", "reservoirAbandonmentPressure", "waterVolumeCoefficient", "reservoirOriginalGasVolume");
    public static final List<String> UNIFIED = List.of("gasType", "modificationMethod", "deviationFactorMethod", "viscosityMethod");

    public Aggregate aggregate(Scope scope, String carrier, List<Source> sources, Double limit) {
        if (sources.isEmpty()) bad("请选择至少一口有完整输入和有效动态储量的井");
        if (limit == null || !Double.isFinite(limit) || (limit != -1 && limit <= 0)) bad("生产水气比上限必须为正数，或 -1 表示不限");
        if (sources.stream().map(Source::wellId).distinct().count() != sources.size()) bad("同一口井不能重复参与计算");
        double total = 0;
        for (var source : sources) {
            if (!Double.isFinite(source.dynamicGasVolume()) || source.dynamicGasVolume() <= 0) bad(source.wellName()+" 的动态储量必须为有效正数");
            total += source.dynamicGasVolume();
        }
        if (!Double.isFinite(total)) bad("动态储量总和超出有效范围");
        final double denominator = total;
        var weights = sources.stream().map(s -> s.dynamicGasVolume()/denominator).toList();
        var input = new LinkedHashMap<String,Object>();
        for (String key : WEIGHTED) {
            double value = 0;
            for (int i=0;i<sources.size();i++) value += required(map(sources.get(i).snapshot().get("input")).get(key), sources.get(i).wellName()+" 的 "+key)*weights.get(i);
            input.put(key, finite(value,key));
        }
        for (String key : UNIFIED) {
            Object value = map(sources.getFirst().snapshot().get("input")).get(key);
            if (value == null || value.toString().isBlank()) bad("缺少统一计算选项："+key);
            for (var source : sources) {
                Object other = map(source.snapshot().get("input")).get(key);
                boolean same = key.equals("gasType") ? Objects.equals(value,other) : number(value)!=null && Objects.equals(number(value),number(other)) && number(value)==Math.rint(number(value));
                if (!same) bad("各井的 "+key+" 不一致，请先统一单井计算条件，不能对方法编号取平均");
            }
            input.put(key,value);
        }
        // 凝析气的附加参数只有在各来源都提供时才汇总，不能为缺少字段的井补零。
        String condensate = "condensateOilDensityUnderStandardCondition";
        if (sources.stream().anyMatch(s -> map(s.snapshot().get("input")).get(condensate) != null)) {
            double value = 0;
            for (int i=0;i<sources.size();i++) value += required(map(sources.get(i).snapshot().get("input")).get(condensate),sources.get(i).wellName()+" 的凝析油密度")*weights.get(i);
            input.put(condensate,finite(value,condensate));
        }
        if (number(input.get("reservoirOriginalGasVolume")) <= 0) bad("加权地质储量必须大于 0");
        input.put("waterGasRatioLimit",limit);
        var counts = new LinkedHashMap<String,Object>();
        var identify = rows(sources,"identifyInputItems",false,counts);
        var influx = rows(sources,"influxInputItems",true,counts);
        if (identify.size()<2) bad("水侵识别日期不足 2 个，请完善单井识别输入");
        if (influx.isEmpty()) bad("参与井没有生产数据");
        input.put("currCumulativeGasProduction",influx.getLast().get("cumulativeGasProduction"));
        var payload = new LinkedHashMap<String,Object>();
        payload.put("wellName",carrier); payload.put("projectId",scope.projectId()); payload.put("gasReservoirId",scope.gasReservoirId());
        payload.put("analysisId",null); payload.put("input",input); payload.put("identifyInputItems",identify); payload.put("influxInputItems",influx);
        var rules = new LinkedHashMap<String,Object>();
        rules.put("version",VERSION); rules.put("datePolicy","UNION_AVAILABLE_WEIGHTED"); rules.put("weightedFields",WEIGHTED);
        rules.put("productionWeightedFields",List.of("pressure","dailyGasProduction","cumulativeGasProduction","cumulativeWaterProduction"));
        rules.put("unifiedFields",UNIFIED); rules.put("weightTotal",total); rules.put("rowCounts",counts);
        rules.put("currentGasPolicy","LAST_UNION_PRODUCTION_DATE");
        return new Aggregate(payload,rules,weights);
    }

    private List<Map<String,Object>> rows(List<Source> sources,String key,boolean daily,Map<String,Object> counts) {
        var byWell = new ArrayList<Map<LocalDate,Map<String,Object>>>();
        var union = new TreeSet<LocalDate>();
        for (var source:sources) {
            var indexed = new TreeMap<LocalDate,Map<String,Object>>();
            var seen = new HashSet<LocalDate>();
            var raw = objects(source.snapshot().get(key));
            for (var row:raw) {
                var time = date(row.get("date"));
                if (time == null) bad(source.wellName()+" 的 "+key+" 包含无效日期，请先修正单井数据");
                LocalDate day = time.toLocalDate(); union.add(day);
                if (!seen.add(day)) bad(source.wellName()+" 的 "+key+" 存在同日多条记录，请先确认取值规则");
                if (Boolean.TRUE.equals(row.get("isDeleted"))) continue;
                indexed.put(day,row);
            }
            byWell.add(indexed);
        }
        var result = new ArrayList<Map<String,Object>>();
        var evidence = new ArrayList<Map<String,Object>>();
        for (var day:union) {
            var row = new LinkedHashMap<String,Object>(); row.put("date",day.toString());
            var dayWeights = new LinkedHashMap<String,Object>(); dayWeights.put("date",day.toString());
            for (String field:daily?List.of("pressure","dailyGasProduction","cumulativeGasProduction","cumulativeWaterProduction"):List.of("pressure","cumulativeGasProduction","cumulativeWaterProduction")) {
                double availableG=0;
                var available = new ArrayList<Integer>();
                for(int i=0;i<byWell.size();i++) if(byWell.get(i).containsKey(day) && valid(byWell.get(i).get(day),field)) { available.add(i); availableG+=sources.get(i).dynamicGasVolume(); }
                if(available.isEmpty()) bad(key+" 的 "+day+" 没有任何井提供有效 "+field+"；保留日期且不补零，请完善数据");
                double value=0; var contributors=new ArrayList<Map<String,Object>>();
                for(int i:available) {
                    double weight=sources.get(i).dynamicGasVolume()/availableG;
                    value+=number(byWell.get(i).get(day).get(field))*weight;
                    contributors.add(Map.of("wellId",sources.get(i).wellId(),"weight",weight));
                }
                row.put(field,finite(value,field)); dayWeights.put(field,contributors);
            }
            // 通常四列参与井相同，权重只保存一份，避免数千日期、大量井时重复四倍快照。
            Object first=dayWeights.get("pressure");
            if(dayWeights.entrySet().stream().filter(e->!e.getKey().equals("date")).allMatch(e->Objects.equals(first,e.getValue()))) {
                dayWeights.clear();dayWeights.put("date",day.toString());dayWeights.put("allFields",first);
            }
            result.add(row); evidence.add(dayWeights);
        }
        counts.put(key,Map.of("rows",result.size(),"dateWeights",evidence));
        return result;
    }
    private static boolean valid(Map<String,Object> row,String key) { return number(row.get(key))!=null; }
    private static double required(Object value,String label) { var n=number(value); if(n==null) bad(label+" 缺失或无效"); return n; }
    private static double finite(double value,String label) { if(!Double.isFinite(value)) bad(label+" 超出有效数值范围"); return value; }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
