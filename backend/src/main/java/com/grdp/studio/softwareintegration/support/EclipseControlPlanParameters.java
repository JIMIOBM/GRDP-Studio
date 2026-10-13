package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Existing complete initial/dated OPEN-ORAT chain; source-bound edits only. */
public final class EclipseControlPlanParameters {
    private static final Set<String> EDIT=Set.of("well","sourceFile","lineNumber","date","timeDays","unit","originalOilRate","targetOilRate");
    private static final Set<String> RECORD=Set.of("well","sourceFile","lineNumber","date","timeDays","unit","originalOilRate","bhpLimit");
    private EclipseControlPlanParameters() {}
    private static boolean shape(JsonNode n,Set<String> keys){return n!=null && n.isObject() && n.size()==keys.size() && n.properties().stream().allMatch(e->keys.contains(e.getKey()));}
    private static boolean name(JsonNode n){return n.isTextual() && n.asText().matches("[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}");}
    private static boolean source(JsonNode n){return n.isTextual() && n.asText().matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]");}
    private static boolean positive(JsonNode n){return n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble()>0 && n.asDouble()<=100_000_000;}
    private static boolean integer(JsonNode n,int min){return n.isIntegralNumber() && n.canConvertToInt() && n.asInt()>=min;}
    private static LocalDate date(JsonNode n){try{return n.isTextual() && n.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")?LocalDate.parse(n.asText()):null;}catch(Exception e){return null;}}
    public static boolean valid(JsonNode p) {
        if(!shape(p,Set.of("schemaVersion","baselineRunId","edits")) || !"eclipse-control-plan-parameters/1".equals(p.path("schemaVersion").asText())
            || !p.path("baselineRunId").isIntegralNumber() || !p.path("baselineRunId").canConvertToLong() || p.path("baselineRunId").asLong()<=0
            || !p.path("edits").isArray() || p.path("edits").isEmpty() || p.path("edits").size()>24)return false;
        int previous=0;String file=null;var seen=new HashSet<String>();
        for(var e:p.path("edits")){
            if(!shape(e,EDIT) || !name(e.path("well")) || !source(e.path("sourceFile")) || !integer(e.path("lineNumber"),1) || e.path("lineNumber").asInt()<=previous
                || date(e.path("date"))==null || !integer(e.path("timeDays"),0) || !Set.of("STB/day","sm3/day").contains(e.path("unit").asText())
                || !positive(e.path("originalOilRate")) || !positive(e.path("targetOilRate")) || e.path("originalOilRate").asDouble()==e.path("targetOilRate").asDouble()
                || !seen.add(e.path("well").asText()+":"+e.path("date").asText()) || file!=null && !file.equals(e.path("sourceFile").asText()))return false;
            file=e.path("sourceFile").asText();previous=e.path("lineNumber").asInt();
        }
        return true;
    }
    public static boolean matchesInspection(JsonNode p,JsonNode i) {
        if(!valid(p) || !validPlan(i))return false;
        for(var e:p.path("edits")){
            int found=0;
            for(var c:i.path("productionControlPlan").path("records"))if(EDIT.stream().filter(k->!k.equals("targetOilRate")).allMatch(k->k.equals("originalOilRate")?e.path(k).asDouble()==c.path(k).asDouble():e.path(k).equals(c.path(k))))found++;
            if(found!=1)return false;
        }
        return true;
    }
    private record Report(int line,LocalDate date) {}
    private static LocalDate reportDate(JsonNode r){
        try{
            if(!r.path("time").isNull() && !r.path("time").asText().equals("00:00:00"))return null;
            String month=r.path("month").asText();int m=List.of("JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC").indexOf(month)+1;
            return m>0?LocalDate.of(Integer.parseInt(r.path("year").asText()),m,Integer.parseInt(r.path("day").asText())):null;
        }catch(Exception e){return null;}
    }
    private static List<String> expand(JsonNode values){
        if(!values.isArray())return null;var result=new ArrayList<String>();
        for(var v:values){
            if(!v.isTextual())return null;String s=v.asText();int count=1;
            if(s.matches("[0-9]+\\*.*")){int split=s.indexOf('*');try{count=Integer.parseInt(s.substring(0,split));}catch(Exception e){return null;}s=s.substring(split+1);}
            if(count<1 || count>128 || result.size()+count>128)return null;
            for(int n=0;n<count;n++)result.add(s);
        }
        return result;
    }
    private static boolean number(String s,double target){try{return Double.isFinite(Double.parseDouble(s)) && Double.parseDouble(s)==target;}catch(Exception e){return false;}}
    public static boolean validPlan(JsonNode i) {
        if(i==null || !"eclipse-data-inspection/13".equals(i.path("schemaVersion").asText()) || !Set.of("FIELD","METRIC").contains(i.path("unitSystem").asText())
            || i.path("packageFiles").size()!=1 || !i.path("packageFiles").get(0).path("relativePath").equals(i.path("caseName")) || !source(i.path("caseName")))return false;
        var plan=i.path("productionControlPlan");var start=date(plan.path("startDate"));
        if(!shape(plan,Set.of("startDate","endTimeDays","records")) || start==null || !integer(plan.path("endTimeDays"),1) || !plan.path("records").isArray()
            || plan.path("records").isEmpty() || plan.path("records").size()>1000 || !i.path("scheduleTimeline").isArray() || i.path("scheduleTimeline").size()<2)return false;
        var reports=new ArrayList<Report>();LocalDate previousDate=start;int previousLine=0;
        for(var e:i.path("scheduleTimeline")){
            if(!"DATES".equals(e.path("kind").asText()) || !e.path("sourceFile").equals(i.path("caseName")) || !integer(e.path("lineNumber"),1) || e.path("lineNumber").asInt()<=previousLine
                || !e.path("records").isArray() || e.path("records").isEmpty())return false;
            for(var r:e.path("records")){var d=reportDate(r);if(d==null || !d.isAfter(previousDate))return false;previousDate=d;}
            previousLine=e.path("lineNumber").asInt();reports.add(new Report(previousLine,previousDate));
        }
        if(ChronoUnit.DAYS.between(start,previousDate)!=plan.path("endTimeDays").asInt())return false;
        String unit="FIELD".equals(i.path("unitSystem").asText())?"STB/day":"sm3/day";
        var initial=new HashSet<String>();var seen=new HashSet<String>();var phases=new HashSet<Integer>();previousLine=0;
        var metadata=i.path("scheduleMetadata");int productionCount=0;
        if(!metadata.path("wells").isArray() || !metadata.path("records").isArray() || !metadata.path("completions").isArray() || !metadata.path("groups").isArray() || !metadata.path("groups").isEmpty())return false;
        for(var m:metadata.path("records")){
            if(m.path("keyword").asText().equals("WCONPROD"))productionCount++;
            if(!Set.of("WCONPROD","WCONINJE","COMPDAT").contains(m.path("keyword").asText()) || !m.path("sourceFile").equals(i.path("caseName")))return false;
            if(!m.path("keyword").asText().equals("WCONPROD") && m.path("lineNumber").asInt()>=reports.get(0).line())return false;
        }
        if(productionCount!=plan.path("records").size())return false;
        for(var c:plan.path("records")){
            var d=date(c.path("date"));String well=c.path("well").asText();int line=c.path("lineNumber").asInt();
            if(!shape(c,RECORD) || !name(c.path("well")) || !c.path("sourceFile").equals(i.path("caseName")) || !integer(c.path("lineNumber"),1) || line<=previousLine
                || d==null || !integer(c.path("timeDays"),0) || c.path("timeDays").asInt()>=plan.path("endTimeDays").asInt() || !unit.equals(c.path("unit").asText())
                || !positive(c.path("originalOilRate")) || !positive(c.path("bhpLimit")) || !seen.add(well+":"+c.path("timeDays").asInt()))return false;
            LocalDate expected=start;for(var report:reports)if(report.line()<line)expected=report.date();
            if(!d.equals(expected) || ChronoUnit.DAYS.between(start,d)!=c.path("timeDays").asInt())return false;
            if(c.path("timeDays").asInt()==0)initial.add(well);else{if(!initial.contains(well))return false;phases.add(c.path("timeDays").asInt());}
            int found=0;for(var w:metadata.path("wells"))if(w.path("name").equals(c.path("well")))found++;if(found!=1)return false;
            int completions=0;for(var perf:metadata.path("completions"))if(perf.path("well").equals(c.path("well"))){if(!"OPEN".equals(perf.path("status").asText()) || perf.path("lineNumber").asInt()>=reports.get(0).line())return false;completions++;}
            if(completions==0)return false;
            found=0;
            for(var m:metadata.path("records")){
                if(m.path("keyword").asText().equals("WCONINJE") && m.path("values").get(0)!=null && m.path("values").get(0).equals(c.path("well")))return false;
                if(!m.path("keyword").asText().equals("WCONPROD") || m.path("lineNumber").asInt()!=line)continue;
                var values=expand(m.path("values"));
                if(values==null || values.size()<9 || values.size()>12 || !values.get(0).equals(well) || !values.get(1).equals("OPEN") || !values.get(2).equals("ORAT")
                    || !number(values.get(3),c.path("originalOilRate").asDouble()) || values.subList(4,8).stream().anyMatch(s->!s.isEmpty()) || !number(values.get(8),c.path("bhpLimit").asDouble())
                    || values.subList(9,values.size()).stream().anyMatch(s->!s.isEmpty() && !s.equals("0")))return false;found++;
            }
            if(found!=1)return false;previousLine=line;
        }
        return phases.size()>=2 && !initial.isEmpty();
    }
}
