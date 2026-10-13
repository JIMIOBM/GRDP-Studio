package com.grdp.studio.softwareintegration.support;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
/** A bounded collection of independently validated scenarios, all from one unchanged baseline. */
public final class EclipseBatchParameters {
    private EclipseBatchParameters(){}
    private static boolean fields(JsonNode n,Set<String> keys){if(n==null||!n.isObject()||n.size()!=keys.size())return false;var names=new HashSet<String>();n.properties().forEach(p->names.add(p.getKey()));return names.equals(keys);}
    private static boolean name(JsonNode n,int max){return n.isString()&&!n.asText().isBlank()&&n.asText().length()<=max&&n.asText().equals(n.asText().trim())&&n.asText().codePoints().noneMatch(Character::isISOControl);}
    public static boolean valid(JsonNode n){
        if(!fields(n,Set.of("requestKey","name","baselineRunId","cases"))||!name(n.path("name"),100)||!n.path("requestKey").isString()||!n.path("requestKey").asText().matches("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")
            ||!n.path("baselineRunId").isIntegralNumber()||!n.path("baselineRunId").canConvertToLong()||n.path("baselineRunId").asLong()<=0||!n.path("cases").isArray()||n.path("cases").size()<2||n.path("cases").size()>8||n.toString().getBytes(StandardCharsets.UTF_8).length>262144)return false;
        var names=new HashSet<String>();var parameters=new java.util.ArrayList<JsonNode>();long baseline=n.path("baselineRunId").asLong();
        for(var item:n.path("cases")){
            if(!fields(item,Set.of("name","parameters"))||!name(item.path("name"),80)||!names.add(item.path("name").asText().toLowerCase(Locale.ROOT)))return false;
            var p=item.path("parameters");
            if(!scenario(p)||p.path("baselineRunId").asLong()!=baseline||parameters.stream().anyMatch(old->sameContent(old,p)))return false;
            parameters.add(p);
        }
        return true;
    }
    public static boolean sameContent(JsonNode a,JsonNode b){
        if(a==null||b==null)return a==b;
        if(a.isNumber()&&b.isNumber())return a.decimalValue().compareTo(b.decimalValue())==0;
        if(a.isObject()&&b.isObject())return a.size()==b.size()&&a.properties().stream().allMatch(e->b.has(e.getKey())&&sameContent(e.getValue(),b.path(e.getKey())));
        if(a.isArray()&&b.isArray()){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!sameContent(a.path(i),b.path(i)))return false;return true;}
        return a.equals(b);
    }
    public static boolean scenario(JsonNode p){return EclipsePvtwParameters.valid(p)||EclipseOilPvtParameters.valid(p)||EclipseGasPvtParameters.valid(p)||EclipseRelpermParameters.valid(p)||EclipseGridFieldParameters.valid(p)||EclipseGridRegionParameters.valid(p);}
}
