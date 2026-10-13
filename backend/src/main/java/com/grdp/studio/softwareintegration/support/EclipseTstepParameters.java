package com.grdp.studio.softwareintegration.support;
import tools.jackson.databind.JsonNode;
import java.util.Set;
import java.util.regex.Pattern;

public final class EclipseTstepParameters {
    public static final String SCHEMA="eclipse-tstep-parameters/1";
    private static final Set<String> FIELDS=Set.of("schemaVersion","baselineRunId","sourceFile","lineNumber","unit","originalSteps","targetSteps");
    private EclipseTstepParameters() {}
    private static boolean integer(JsonNode v,long max) { return v.isIntegralNumber() && v.canConvertToLong() && v.asLong()>0 && v.asLong()<=max; }
    private static long sum(JsonNode v) { if(!v.isArray() || v.isEmpty() || v.size()>1000)return -1;long sum=0;for(var item:v){if(!integer(item,100000))return -1;sum+=item.asLong();}return sum<=100000 ? sum : -1; }
    public static boolean valid(JsonNode p) {
        if(p==null || !p.isObject() || p.size()!=FIELDS.size())return false;
        var fields=new java.util.HashSet<String>();p.properties().forEach(f->fields.add(f.getKey()));
        return fields.equals(FIELDS) && SCHEMA.equals(p.path("schemaVersion").asText()) && "day".equals(p.path("unit").asText())
            && integer(p.path("baselineRunId"),Long.MAX_VALUE) && integer(p.path("lineNumber"),Integer.MAX_VALUE)
            && p.path("sourceFile").isTextual() && Pattern.matches("^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$",p.path("sourceFile").asText())
            && sum(p.path("originalSteps"))>0 && sum(p.path("originalSteps"))==sum(p.path("targetSteps")) && !p.path("originalSteps").equals(p.path("targetSteps"));
    }
    public static boolean matchesInspection(JsonNode p,JsonNode i) {
        if(!valid(p) || i==null || !Set.of("eclipse-data-inspection/4","eclipse-data-inspection/5","eclipse-data-inspection/6", "eclipse-data-inspection/7", "eclipse-data-inspection/8", "eclipse-data-inspection/9", "eclipse-data-inspection/10", "eclipse-data-inspection/11", "eclipse-data-inspection/12", "eclipse-data-inspection/13").contains(i.path("schemaVersion").asText())
            || !Set.of("FIELD","METRIC","PVT-M").contains(i.path("unitSystem").asText()) || i.path("packageFiles").size()!=1
            || !p.path("sourceFile").asText().equals(i.path("caseName").asText()) || !p.path("sourceFile").asText().equals(i.path("packageFiles").get(0).path("relativePath").asText())
            || !i.path("scheduleTimeline").isArray() || i.path("scheduleTimeline").isEmpty())return false;
        int previous=0,matches=0;long total=0;
        for(var e:i.path("scheduleTimeline")) {
            if(!"TSTEP".equals(e.path("kind").asText()) || !p.path("sourceFile").asText().equals(e.path("sourceFile").asText()) || !integer(e.path("lineNumber"),Integer.MAX_VALUE)
                || e.path("lineNumber").asInt()<=previous || !e.path("steps").isArray() || e.path("steps").isEmpty() || e.path("steps").size()>1000)return false;
            previous=e.path("lineNumber").asInt();boolean selected=previous==p.path("lineNumber").asInt();
            if(selected && e.path("steps").size()!=p.path("originalSteps").size())return false;
            long duration=0;int n=0;
            for(var step:e.path("steps")) {
                if(!step.isTextual() || !Pattern.matches("[+]?[0-9]+",step.asText()))return false;
                int value;try{value=Integer.parseInt(step.asText());}catch(NumberFormatException error){return false;}
                if(value<=0 || value>100000 || selected && value!=p.path("originalSteps").get(n).asInt())return false;
                n++;duration+=value;
            }
            total+=duration;if(duration>100000 || total>100000)return false;if(selected)matches++;
        }
        return matches==1;
    }
}
