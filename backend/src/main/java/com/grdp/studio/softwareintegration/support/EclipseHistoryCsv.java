package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Observations stay separate from simulated values. Only exact native report times are comparable. */
public final class EclipseHistoryCsv {
    private EclipseHistoryCsv() {}
    public record Observation(String well,String keyword,String unit,double timeDays,double value,int sourceLine) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Point(String well,String keyword,String unit,double timeDays,double observed,Double simulated,Double difference,int sourceLine,String reason) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Metric(String well,String keyword,String unit,int observedCount,int matchedCount,int missingCount,double coverage,Double mae,Double rmse,Double bias,Double mape,int mapeCount,int mapeOverflowCount) {}
    public record Comparison(List<Point> points,List<Metric> metrics) {}
    private record Identity(String well,String keyword) {}
    private record Curve(String unit,Map<Double,Double> points) {}
    private record CsvRow(List<String> fields,int line) {}

    public static List<Observation> parse(String source) {
        if(source==null||source.getBytes(StandardCharsets.UTF_8).length>1_048_576||source.indexOf('\0')>=0)throw bad("CSV须为不超过1MiB的UTF-8文本");
        String text=source.startsWith("\uFEFF")?source.substring(1):source;
        var parsed=rows(text);
        if(parsed.isEmpty()||!parsed.getFirst().fields().equals(List.of("well","keyword","unit","timeDays","value")))throw bad("CSV表头须为well,keyword,unit,timeDays,value");
        var observations=new ArrayList<Observation>();var identities=new HashSet<String>();
        for(var row:parsed.subList(1,parsed.size())) {
            var fields=row.fields();if(fields.size()==1&&fields.getFirst().isBlank())continue;
            if(fields.size()!=5)throw bad("第"+row.line()+"行需要5列");
            String well=fields.get(0),keyword=fields.get(1),unit=fields.get(2);
            if(!keyword.matches("^[FWG][A-Z0-9]{1,7}$")||!well.equals(well.trim())||well.length()>64||well.codePoints().anyMatch(Character::isISOControl)
                ||keyword.startsWith("F")&&!well.isEmpty()||!keyword.startsWith("F")&&well.isEmpty()
                ||unit.isBlank()||!unit.equals(unit.trim())||unit.length()>64||unit.codePoints().anyMatch(Character::isISOControl))throw bad("第"+row.line()+"行的井/对象、指标或单位无效");
            double time=number(fields.get(3),row.line()),value=number(fields.get(4),row.line());
            if(time<0||time>1e9||Math.abs(value)>1e15)throw bad("第"+row.line()+"行数值超出范围");
            if(time==0)time=0;
            if(!identities.add(well+"\0"+keyword+"\0"+time))throw bad("同一井/指标/报告时间存在重复观察值");
            observations.add(new Observation(well,keyword,unit,time,value,row.line()));
            if(observations.size()>5000)throw bad("每个CSV最多5000个观察点");
        }
        if(observations.isEmpty())throw bad("CSV没有观察数据");
        return List.copyOf(observations);
    }

    private static double number(String token,int line) {
        String value=token.trim();
        if(value.length()>64||!value.matches("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eEdD][+-]?\\d+)?$"))throw bad("第"+line+"行需要有限数字");
        double n=Double.parseDouble(value.replace('D','E').replace('d','e'));if(!Double.isFinite(n))throw bad("第"+line+"行需要有限数字");return n;
    }

    private static List<CsvRow> rows(String text) {
        var result=new ArrayList<CsvRow>();var fields=new ArrayList<String>();var token=new StringBuilder();
        boolean quoted=false,closed=false;int line=1,startLine=1;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(quoted) {
                if(c=='"') { if(i+1<text.length()&&text.charAt(i+1)=='"'){token.append('"');i++;}else{quoted=false;closed=true;} }
                else {token.append(c);if(c=='\n')line++;}
            } else if(c=='"') {
                if(token.length()!=0||closed)throw bad("CSV引号位置无效");quoted=true;
            } else if(c==','||c=='\r'||c=='\n') {
                fields.add(token.toString());token.setLength(0);closed=false;
                if(c!=',') {
                    result.add(new CsvRow(List.copyOf(fields),startLine));fields.clear();
                    if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;
                    line++;startLine=line;
                }
                if(fields.size()>5||result.size()>10002)throw bad("CSV行或列超过限制");
            } else {
                if(closed)throw bad("CSV结束引号后须为分隔符");token.append(c);
            }
            if(token.length()>256)throw bad("CSV单列过长");
        }
        if(quoted)throw bad("CSV引号未闭合");
        if(token.length()>0||closed||!fields.isEmpty()){fields.add(token.toString());result.add(new CsvRow(List.copyOf(fields),startLine));}
        return result;
    }

    private static Map<Identity,Curve> index(JsonNode summary) {
        if(summary==null||!summary.path("series").isArray())throw bad("模型缺少实际Summary曲线");
        var curves=new HashMap<Identity,Curve>();
        for(var series:summary.path("series")) {
            String keyword=series.path("keyword").asText(),well=series.path("objectName").isNull()?"":series.path("objectName").asText();
            if(!keyword.matches("^[FWG][A-Z0-9]{1,7}$"))continue;
            if(!series.path("unit").isString()||series.path("unit").asText().isBlank())continue;
            if(!series.path("points").isArray())throw bad("模型曲线缺少报告值");
            var values=new HashMap<Double,Double>();double previous=-1;
            for(var point:series.path("points")) {
                if(!point.path("timeDays").isNumber()||!point.path("value").isNumber())throw bad("模型曲线数值类型无效");
                double time=point.path("timeDays").asDouble(),value=point.path("value").asDouble();if(time==0)time=0;
                if(!Double.isFinite(time)||time<0||time<=previous||!Double.isFinite(value)||Math.abs(value)>1e15)throw bad("模型报告时刻或数值无效");
                values.put(time,value);previous=time;
            }
            if(curves.put(new Identity(well,keyword),new Curve(series.path("unit").asText(),values))!=null)throw bad("模型曲线身份重复");
        }
        return curves;
    }

    public static void requireBaselineCoverage(List<Observation> observations,JsonNode summary) {
        var curves=index(summary);
        for(var observation:observations) {
            var curve=curves.get(new Identity(observation.well(),observation.keyword()));
            if(curve==null)throw bad("基线缺少带明确单位的曲线："+observation.keyword()+" · "+observation.well());
            if(!curve.unit().equals(observation.unit()))throw bad("观察单位须与原生模型单位一致："+observation.keyword()+"使用"+curve.unit());
        }
    }

    public static Comparison compare(List<Observation> observations,JsonNode summary) {
        var curves=index(summary);var points=new ArrayList<Point>();var groups=new LinkedHashMap<String,List<Point>>();
        for(var observation:observations) {
            var curve=curves.get(new Identity(observation.well(),observation.keyword()));
            String reason=curve==null?"模型没有该曲线":!curve.unit().equals(observation.unit())?"单位不一致":!curve.points().containsKey(observation.timeDays())?"没有同一报告时刻":"";
            Double simulated=reason.isEmpty()?curve.points().get(observation.timeDays()):null;
            var point=new Point(observation.well(),observation.keyword(),observation.unit(),observation.timeDays(),observation.value(),simulated,simulated==null?null:simulated-observation.value(),observation.sourceLine(),reason);
            points.add(point);groups.computeIfAbsent(point.well()+"\0"+point.keyword()+"\0"+point.unit(),key->new ArrayList<>()).add(point);
        }
        var metrics=new ArrayList<Metric>();
        for(var group:groups.values()) {
            var first=group.getFirst();int matched=0,mapeCount=0,mapeOverflowCount=0;double absolute=0,square=0,bias=0,percentage=0;
            for(var point:group)if(point.difference()!=null) {
                double delta=point.difference();matched++;absolute+=Math.abs(delta);square+=delta*delta;bias+=delta;
                if(point.observed()!=0){mapeCount++;double ratio=Math.abs(delta/point.observed())*100;if(!Double.isFinite(ratio))mapeOverflowCount++;else percentage+=(ratio-percentage)/mapeCount;}
            }
            metrics.add(new Metric(first.well(),first.keyword(),first.unit(),group.size(),matched,group.size()-matched,(double)matched/group.size(),matched==0?null:absolute/matched,matched==0?null:Math.sqrt(square/matched),matched==0?null:bias/matched,mapeCount==0||mapeOverflowCount>0?null:percentage,mapeCount,mapeOverflowCount));
        }
        return new Comparison(List.copyOf(points),List.copyOf(metrics));
    }
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
}
