package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** Single explicit dry-gas PVDG table. Source pressure and Bg columns are immutable. */
public final class EclipseGasPvtParameters {
    public static final String SCHEMA="eclipse-pvdg-parameters/1";
    private static final Set<String> FIELDS=Set.of("schemaVersion","baselineRunId","sourceFile","sourceSha256","keywordLineNumber","unit","multiplier","rows");
    private EclipseGasPvtParameters() {}
    private static boolean fields(JsonNode v,Set<String> expected) {
        if(v==null||!v.isObject())return false;
        var names=new HashSet<String>();v.properties().forEach(p->names.add(p.getKey()));return names.equals(expected);
    }
    public static boolean validRows(JsonNode rows,int keywordLine) {
        if(!rows.isArray()||rows.size()<2||rows.size()>64)return false;
        int priorLine=keywordLine;double priorPressure=Double.NEGATIVE_INFINITY,priorBg=Double.POSITIVE_INFINITY,priorMu=0;
        for(var row:rows) {
            if(!fields(row,Set.of("lineNumber","values"))||!row.path("lineNumber").isIntegralNumber()||!row.path("lineNumber").canConvertToInt())return false;
            int line=row.path("lineNumber").asInt();var values=row.path("values");
            if(line<=priorLine||line>2_000_000||!values.isArray()||values.size()!=3)return false;
            var n=new double[3];
            for(int i=0;i<3;i++) {
                var token=values.path(i);
                if(!token.isString()||token.asText().length()>64||!token.asText().matches("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eEdD][+-]?[0-9]+)?$"))return false;
                n[i]=number(token.asText());if(!Double.isFinite(n[i]))return false;
            }
            if(n[0]<0||n[0]<=priorPressure||n[1]<=0||n[1]>=priorBg||n[1]>100000||n[2]<=0||n[2]<priorMu||n[2]>1000)return false;
            priorLine=line;priorPressure=n[0];priorBg=n[1];priorMu=n[2];
        }
        return true;
    }
    public static boolean valid(JsonNode v) {
        if(!fields(v,FIELDS)||!v.path("schemaVersion").isString()||!SCHEMA.equals(v.path("schemaVersion").asText())
            ||!v.path("unit").isString()||!"cP".equals(v.path("unit").asText())
            ||!v.path("sourceFile").isString()||!v.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$")
            ||!v.path("sourceSha256").isString()||!v.path("sourceSha256").asText().matches("^[a-f0-9]{64}$")
            ||!v.path("baselineRunId").isIntegralNumber()||!v.path("baselineRunId").canConvertToLong()||v.path("baselineRunId").asLong()<=0
            ||!v.path("keywordLineNumber").isIntegralNumber()||!v.path("keywordLineNumber").canConvertToInt()||v.path("keywordLineNumber").asInt()<=0||v.path("keywordLineNumber").asInt()>2_000_000
            ||!v.path("multiplier").isNumber())return false;
        double multiplier=v.path("multiplier").asDouble();
        if(!Double.isFinite(multiplier)||multiplier<.000001||multiplier>100||multiplier==1||!validRows(v.path("rows"),v.path("keywordLineNumber").asInt()))return false;
        for(var row:v.path("rows")){double target=number(row.path("values").path(2).asText())*multiplier;if(!Double.isFinite(target)||target<=0||target>1000)return false;}
        return true;
    }
    public static boolean matchesInspection(JsonNode v,JsonNode inspection) {
        if(!valid(v)||inspection==null||!"eclipse-data-inspection/4".equals(inspection.path("schemaVersion").asText())
            ||!Set.of("METRIC","FIELD","LAB","PVT-M").contains(inspection.path("unitSystem").asText())||inspection.path("packageFiles").size()!=1)return false;
        boolean oil=false;for(var phase:inspection.path("phases"))oil|="GAS".equals(phase.asText());if(!oil)return false;
        var file=inspection.path("packageFiles").path(0);var c=inspection.path("gasPvtContext");
        return "eclipse-gas-pvt-context/1".equals(c.path("schemaVersion").asText())&&v.path("sourceFile").equals(file.path("relativePath"))
            &&v.path("sourceSha256").equals(file.path("sha256"))&&v.path("sourceFile").equals(c.path("sourceFile"))
            &&v.path("keywordLineNumber").asInt()==c.path("keywordLineNumber").asInt()&&v.path("rows").equals(c.path("rows"));
    }
    private static double number(String s){try{return Double.parseDouble(s.replace('D','E').replace('d','e'));}catch(NumberFormatException e){return Double.NaN;}}
}
