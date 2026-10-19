package com.grdp.studio.softwareintegration.support;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Uniform original full-grid thermal rock arrays, bound to their complete source. */
public final class EclipseThermalRockParameters {
    public static final String SCHEMA="eclipse-thermal-rock-parameters/1";
    private EclipseThermalRockParameters(){}
    private static boolean shape(JsonNode n,Set<String> fields){if(n==null||!n.isObject()||n.size()!=fields.size())return false;var names=new HashSet<String>();n.properties().forEach(p->names.add(p.getKey()));return names.equals(fields);}
    private static boolean integer(JsonNode n,long max){return n.isIntegralNumber()&&n.canConvertToLong()&&n.asLong()>=1&&n.asLong()<=max;}
    private static boolean text(JsonNode n,String pattern){return n.isTextual()&&n.asText().matches(pattern);}
    private static boolean value(String field,JsonNode n){return n.isNumber()&&Double.isFinite(n.asDouble())&&n.asDouble()<=1e9&&("HEATCR".equals(field)?n.asDouble()>0:"THCONR".equals(field)&&n.asDouble()>=0);}
    private static boolean unit(String field,String unit){return "HEATCR".equals(field)?Set.of("Btu/ft3/degR","kJ/m3/K").contains(unit):"THCONR".equals(field)&&Set.of("Btu/ft/day/degR","kJ/m/day/K").contains(unit);}
    public static boolean valid(JsonNode p){return shape(p,Set.of("schemaVersion","baselineRunId","sourceFile","sourceSha256","field","lineNumber","cellCount","unit","originalValue","targetValue"))
        &&SCHEMA.equals(p.path("schemaVersion").asText())&&integer(p.path("baselineRunId"),Long.MAX_VALUE)&&integer(p.path("lineNumber"),Integer.MAX_VALUE)&&integer(p.path("cellCount"),250000)
        &&text(p.path("sourceFile"),"[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]")&&text(p.path("sourceSha256"),"[a-f0-9]{64}")
        &&unit(p.path("field").asText(),p.path("unit").asText())&&value(p.path("field").asText(),p.path("originalValue"))&&value(p.path("field").asText(),p.path("targetValue"))&&p.path("originalValue").asDouble()!=p.path("targetValue").asDouble();}
    public static boolean validContext(JsonNode c,JsonNode i){
        if(!shape(c,Set.of("schemaVersion","sourceFile","sourceSha256","sourceSizeBytes","cellCount","activeCellCount","records"))||!"eclipse-thermal-rock-context/1".equals(c.path("schemaVersion").asText())
            ||i==null||!"eclipse-data-inspection/23".equals(i.path("schemaVersion").asText())||!EclipseThermalInjectionParameters.validContext(i.path("thermalInjectionContext"),i)
            ||!integer(c.path("cellCount"),250000)||!integer(c.path("activeCellCount"),c.path("cellCount").asLong())||!c.path("records").isArray()||c.path("records").size()!=2)return false;
        var original=i.path("thermalInjectionContext");
        for(var k:new String[]{"sourceFile","sourceSha256","sourceSizeBytes"})if(!c.path(k).equals(original.path(k)))return false;
        var d=i.path("dimensions");long count=1;for(var k:new String[]{"nx","ny","nz"}){if(!integer(d.path(k),250000))return false;count*=d.path(k).asLong();}
        if(count!=c.path("cellCount").asLong())return false;
        var fields=new HashSet<String>();var lines=new HashSet<Integer>();boolean field="FIELD".equals(i.path("unitSystem").asText());
        for(var r:c.path("records")){
            String key=r.path("field").asText(),expected="HEATCR".equals(key)?field?"Btu/ft3/degR":"kJ/m3/K":field?"Btu/ft/day/degR":"kJ/m/day/K";
            if(!shape(r,Set.of("field","lineNumber","unit","originalValue"))||!Set.of("HEATCR","THCONR").contains(key)||!fields.add(key)||!integer(r.path("lineNumber"),c.path("sourceSizeBytes").asLong())
                ||!lines.add(r.path("lineNumber").asInt())||!expected.equals(r.path("unit").asText())||!value(key,r.path("originalValue")))return false;
        }
        return fields.equals(Set.of("HEATCR","THCONR"));
    }
    public static boolean matchesInspection(JsonNode p,JsonNode i){
        if(!valid(p)||i==null||!validContext(i.path("thermalRockContext"),i))return false;
        var c=i.path("thermalRockContext");for(var k:new String[]{"sourceFile","sourceSha256","cellCount"})if(!p.path(k).equals(c.path(k)))return false;
        int matches=0;for(var r:c.path("records"))if(p.path("field").equals(r.path("field"))&&p.path("lineNumber").asInt()==r.path("lineNumber").asInt()
            &&p.path("unit").equals(r.path("unit"))&&p.path("originalValue").asDouble()==r.path("originalValue").asDouble())matches++;
        return matches==1;
    }
}
