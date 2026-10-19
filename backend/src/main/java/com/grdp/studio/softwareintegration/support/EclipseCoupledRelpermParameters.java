package com.grdp.studio.softwareintegration.support;
import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
/** Explicit SWOF/SGOF tables; the shared oil endpoint is always scaled together. */
public final class EclipseCoupledRelpermParameters {
    public static final String SCHEMA="eclipse-coupled-relperm-parameters/1";
    private static final Set<String> FIELDS=Set.of("schemaVersion","baselineRunId","sourceFile","sourceSha256","unit","multiplier","field","tables");
    private EclipseCoupledRelpermParameters(){}
    private static boolean fields(JsonNode v,Set<String> expected){if(v==null||!v.isObject())return false;var names=new HashSet<String>();v.properties().forEach(p->names.add(p.getKey()));return names.equals(expected);}
    private static double number(JsonNode token){if(!token.isString()||token.asText().length()>64||!token.asText().matches("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eEdD][+-]?[0-9]+)?$"))return Double.NaN;try{return Double.parseDouble(token.asText().replace('D','E').replace('d','e'));}catch(NumberFormatException e){return Double.NaN;}}
    public static boolean validTables(JsonNode tables){
        if(!tables.isArray()||tables.size()!=2)return false;var seen=new HashSet<String>();int last=0;double waterOil=Double.NaN,gasOil=Double.NaN;
        for(var t:tables){
            if(!fields(t,Set.of("keyword","keywordLineNumber","rows"))||!t.path("keyword").isString()||!t.path("keywordLineNumber").isIntegralNumber()||!t.path("keywordLineNumber").canConvertToInt())return false;
            String key=t.path("keyword").asText();int line=t.path("keywordLineNumber").asInt();var rows=t.path("rows");
            if(!Set.of("SWOF","SGOF").contains(key)||!seen.add(key)||line<=last||line>2000000||!rows.isArray()||rows.size()<2||rows.size()>64)return false;
            double sat=-1,kr=-1,oil=Double.POSITIVE_INFINITY,pc="SWOF".equals(key)?Double.POSITIVE_INFINITY:Double.NEGATIVE_INFINITY;
            for(var row:rows){
                if(!fields(row,Set.of("lineNumber","values"))||!row.path("lineNumber").isIntegralNumber()||!row.path("lineNumber").canConvertToInt()||row.path("lineNumber").asInt()<=line||row.path("lineNumber").asInt()>2000000||!row.path("values").isArray()||row.path("values").size()!=4)return false;
                double[] n=new double[4];for(int i=0;i<4;i++){n[i]=number(row.path("values").path(i));if(!Double.isFinite(n[i]))return false;}
                if(n[0]<0||n[0]>1||n[0]<=sat||n[1]<0||n[1]>1||n[1]<kr||n[2]<0||n[2]>1||n[2]>oil||"SWOF".equals(key)&&n[3]>pc||"SGOF".equals(key)&&n[3]<pc)return false;
                sat=n[0];kr=n[1];oil=n[2];pc=n[3];line=row.path("lineNumber").asInt();
            }
            if(number(rows.path(0).path("values").path(1))!=0||number(rows.path(rows.size()-1).path("values").path(2))!=0||"SGOF".equals(key)&&number(rows.path(0).path("values").path(0))!=0)return false;
            if("SWOF".equals(key))waterOil=number(rows.path(0).path("values").path(2));else gasOil=number(rows.path(0).path("values").path(2));last=line;
        }return seen.equals(Set.of("SWOF","SGOF"))&&Math.abs(waterOil-gasOil)<=1e-12;
    }
    public static boolean valid(JsonNode v){
        if(!fields(v,FIELDS)||!v.path("schemaVersion").isString()||!v.path("unit").isString()||!SCHEMA.equals(v.path("schemaVersion").asText())||!"1".equals(v.path("unit").asText())||!v.path("field").isString()||!Set.of("WATER_KR","GAS_KR","OIL_KR").contains(v.path("field").asText())
            ||!v.path("sourceFile").isString()||!v.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$")||!v.path("sourceSha256").isString()||!v.path("sourceSha256").asText().matches("^[a-f0-9]{64}$")
            ||!v.path("baselineRunId").isIntegralNumber()||!v.path("baselineRunId").canConvertToLong()||v.path("baselineRunId").asLong()<=0||!v.path("multiplier").isNumber()||!validTables(v.path("tables")))return false;
        double m=v.path("multiplier").asDouble();if(!Double.isFinite(m)||m<.000001||m>100||m==1)return false;String field=v.path("field").asText();int column="OIL_KR".equals(field)?2:1;boolean changed=false;
        for(var t:v.path("tables"))if("OIL_KR".equals(field)||t.path("keyword").asText().equals("WATER_KR".equals(field)?"SWOF":"SGOF"))for(var row:t.path("rows")){double kr=number(row.path("values").path(column)),target=kr*m;if(!Double.isFinite(target)||target<0||target>1)return false;changed|=kr!=target;}return changed;
    }
    public static boolean matchesInspection(JsonNode v,JsonNode inspection){
        if(!valid(v)||inspection==null||!"eclipse-data-inspection/4".equals(inspection.path("schemaVersion").asText())||!Set.of("METRIC","FIELD","LAB","PVT-M").contains(inspection.path("unitSystem").asText())||inspection.path("packageFiles").size()!=1)return false;
        var phases=new HashSet<String>();for(var p:inspection.path("phases"))phases.add(p.asText());if(!phases.equals(Set.of("OIL","GAS","WATER")))return false;
        var file=inspection.path("packageFiles").path(0);var c=inspection.path("coupledRelpermContext");return "eclipse-coupled-relperm-context/1".equals(c.path("schemaVersion").asText())&&v.path("sourceFile").equals(file.path("relativePath"))&&v.path("sourceSha256").equals(file.path("sha256"))&&v.path("sourceFile").equals(c.path("sourceFile"))&&validTables(c.path("tables"))&&v.path("tables").equals(c.path("tables"));
    }
}
