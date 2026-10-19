package com.grdp.studio.softwareintegration.support;
import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
/** One explicit saturation region. SOF3 oil columns always move together. */
public final class EclipseRelpermParameters {
    public static final String SCHEMA="eclipse-relperm-parameters/1";
    public static final String GAS_SCHEMA="eclipse-relperm-parameters/2";
    private static final Set<String> FIELDS=Set.of("schemaVersion","baselineRunId","sourceFile","sourceSha256","keyword","keywordLineNumber","unit","multiplier","rows");
    private EclipseRelpermParameters(){}
    private static boolean fields(JsonNode v,Set<String> expected){if(v==null||!v.isObject())return false;var names=new HashSet<String>();v.properties().forEach(p->names.add(p.getKey()));return names.equals(expected);}
    public static boolean validRows(String keyword,JsonNode rows,int keywordLine){
        if(!Set.of("SWFN","SGFN","SOF3").contains(keyword)||!rows.isArray()||rows.size()<2||rows.size()>64)return false;
        int priorLine=keywordLine;double saturation=-1,kr=-1,third="SWFN".equals(keyword)?Double.POSITIVE_INFINITY:Double.NEGATIVE_INFINITY;
        for(var row:rows){
            if(!fields(row,Set.of("lineNumber","values"))||!row.path("lineNumber").isIntegralNumber()||!row.path("lineNumber").canConvertToInt())return false;
            int line=row.path("lineNumber").asInt();var values=row.path("values");if(line<=priorLine||line>2000000||!values.isArray()||values.size()!=3)return false;
            var n=new double[3];for(int i=0;i<3;i++){var token=values.path(i);if(!token.isString()||token.asText().length()>64||!token.asText().matches("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eEdD][+-]?[0-9]+)?$"))return false;n[i]=number(token.asText());if(!Double.isFinite(n[i]))return false;}
            if(n[0]<0||n[0]>1||n[0]<=saturation||n[1]<0||n[1]>1||n[1]<kr||"SOF3".equals(keyword)&&(n[2]<0||n[2]>1)||"SWFN".equals(keyword)&&n[2]>third||!"SWFN".equals(keyword)&&n[2]<third)return false;
            saturation=n[0];kr=n[1];third=n[2];priorLine=line;
        }
        if(number(rows.path(0).path("values").path(1).asText())!=0)return false;
        if("SGFN".equals(keyword)&&number(rows.path(0).path("values").path(0).asText())!=0)return false;
        if("SOF3".equals(keyword)){
            var last=rows.path(rows.size()-1).path("values");
            if(number(rows.path(0).path("values").path(2).asText())!=0||Math.abs(number(last.path(1).asText())-number(last.path(2).asText()))>1e-12)return false;
        }
        return true;
    }
    public static boolean validTables(JsonNode tables){
        if(!tables.isArray()||tables.size()!=3)return false;var seen=new HashSet<String>();JsonNode water=null,oil=null;
        for(var t:tables){
            if(!fields(t,Set.of("keyword","keywordLineNumber","rows"))||!t.path("keyword").isString()||!t.path("keywordLineNumber").isIntegralNumber()||!t.path("keywordLineNumber").canConvertToInt()||t.path("keywordLineNumber").asInt()<=0||t.path("keywordLineNumber").asInt()>2000000)return false;
            String key=t.path("keyword").asText();if(!seen.add(key)||!validRows(key,t.path("rows"),t.path("keywordLineNumber").asInt()))return false;
            if("SWFN".equals(key))water=t;if("SOF3".equals(key))oil=t;
        }
        if(!seen.equals(Set.of("SWFN","SGFN","SOF3")))return false;
        double swco=number(water.path("rows").path(0).path("values").path(0).asText());var rows=oil.path("rows");double somax=number(rows.path(rows.size()-1).path("values").path(0).asText());return Math.abs(swco+somax-1)<=1e-12;
    }
    public static boolean valid(JsonNode v){
        if(!fields(v,FIELDS)||!v.path("schemaVersion").isString()||!(SCHEMA.equals(v.path("schemaVersion").asText())&&Set.of("SWFN","SOF3").contains(v.path("keyword").asText())||GAS_SCHEMA.equals(v.path("schemaVersion").asText())&&"SGFN".equals(v.path("keyword").asText()))||!v.path("unit").isString()||!"1".equals(v.path("unit").asText())||!v.path("keyword").isString()
            ||!v.path("sourceFile").isString()||!v.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$")||!v.path("sourceSha256").isString()||!v.path("sourceSha256").asText().matches("^[a-f0-9]{64}$")
            ||!v.path("baselineRunId").isIntegralNumber()||!v.path("baselineRunId").canConvertToLong()||v.path("baselineRunId").asLong()<=0||!v.path("keywordLineNumber").isIntegralNumber()||!v.path("keywordLineNumber").canConvertToInt()||v.path("keywordLineNumber").asInt()<=0||v.path("keywordLineNumber").asInt()>2000000||!v.path("multiplier").isNumber())return false;
        double m=v.path("multiplier").asDouble();String key=v.path("keyword").asText();if(!Double.isFinite(m)||m<.000001||m>100||m==1||!validRows(key,v.path("rows"),v.path("keywordLineNumber").asInt()))return false;
        boolean changed=false;
        for(var row:v.path("rows"))for(int column=1;column<=("SOF3".equals(key)?2:1);column++){double kr=number(row.path("values").path(column).asText()),target=kr*m;if(!Double.isFinite(target)||target<0||target>1)return false;changed|=target!=kr;}
        return changed;
    }
    public static boolean matchesInspection(JsonNode v,JsonNode inspection){
        if(!valid(v)||inspection==null||!"eclipse-data-inspection/4".equals(inspection.path("schemaVersion").asText())||!Set.of("METRIC","FIELD","LAB","PVT-M").contains(inspection.path("unitSystem").asText())||inspection.path("packageFiles").size()!=1)return false;
        var phases=new HashSet<String>();for(var p:inspection.path("phases"))phases.add(p.asText());if(!phases.equals(Set.of("OIL","GAS","WATER")))return false;
        var file=inspection.path("packageFiles").path(0);var c=inspection.path("relpermContext");if(!"eclipse-relperm-context/1".equals(c.path("schemaVersion").asText())||!v.path("sourceFile").equals(file.path("relativePath"))||!v.path("sourceSha256").equals(file.path("sha256"))||!v.path("sourceFile").equals(c.path("sourceFile"))||!validTables(c.path("tables")))return false;
        for(var t:c.path("tables"))if(t.path("keyword").equals(v.path("keyword")))return t.path("keywordLineNumber").equals(v.path("keywordLineNumber"))&&t.path("rows").equals(v.path("rows"));return false;
    }
    private static double number(String s){try{return Double.parseDouble(s.replace('D','E').replace('d','e'));}catch(NumberFormatException e){return Double.NaN;}}
}
