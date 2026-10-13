package com.grdp.studio.softwareintegration.support;
import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bound single explicit E100 water PVT reference viscosity, in cP. */
public final class EclipsePvtwParameters {
    public static final String SCHEMA="eclipse-pvtw-parameters/1";
    public static final String SCHEMA2="eclipse-pvtw-parameters/2";
    public static int index(String f) {return switch(f){case "REFERENCE_PRESSURE"->0;case "FORMATION_VOLUME_FACTOR"->1;case "COMPRESSIBILITY"->2;case "VISCOSITY"->3;case "VISCOSIBILITY"->4;default->-1;};}
    public static String unitFor(String system,String field) {
        if(index(field)<0)return null;
        return switch(field){case "VISCOSITY"->"cP";case "FORMATION_VOLUME_FACTOR"->"FIELD".equals(system)?"rb/stb":"LAB".equals(system)?"rcc/scc":"rm3/sm3";
        case "REFERENCE_PRESSURE"->"FIELD".equals(system)?"psia":Set.of("LAB","PVT-M").contains(system)?"atma":"barsa";
        default->"FIELD".equals(system)?"1/psi":Set.of("LAB","PVT-M").contains(system)?"1/atm":"1/bar";};
    }
    public static boolean scalar(String field,String unit,double n) {
        if(!Double.isFinite(n))return false;
        return switch(field){case "REFERENCE_PRESSURE"->n>=0&&n*("psia".equals(unit)?.0689475729:"atma".equals(unit)?1.01325:1)<=20000;
        case "FORMATION_VOLUME_FACTOR"->n>0&&n<=10;case "VISCOSITY"->n>0&&n<=1000;
        case "COMPRESSIBILITY","VISCOSIBILITY"->n>=0&&n*("1/psi".equals(unit)?14.5037738:"1/atm".equals(unit)?1/1.01325:1)<=.001;default->false;};
    }
    private static final Set<String> FIELDS=Set.of("schemaVersion","baselineRunId","sourceFile","sourceSha256","lineNumber","field","unit","originalValue","targetValue","recordValues");
    private EclipsePvtwParameters() {}
    public static boolean valid(JsonNode v) {
        if(v==null||!v.isObject())return false;
        var names=new HashSet<String>();v.properties().forEach(p->names.add(p.getKey()));
        if(!names.equals(FIELDS)||!Set.of(SCHEMA,SCHEMA2).contains(v.path("schemaVersion").asText())||!v.path("baselineRunId").isIntegralNumber()||!v.path("baselineRunId").canConvertToLong()||v.path("baselineRunId").asLong()<=0
            ||!v.path("lineNumber").isIntegralNumber()||!v.path("lineNumber").canConvertToInt()||v.path("lineNumber").asInt()<=0||v.path("lineNumber").asInt()>2_000_000)return false;
        for(String k:List.of("schemaVersion","sourceFile","sourceSha256","field","unit"))if(!v.path(k).isString())return false;
        if(!v.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$")||!v.path("sourceSha256").asText().matches("^[a-f0-9]{64}$")
            ||(SCHEMA.equals(v.path("schemaVersion").asText())&&!"VISCOSITY".equals(v.path("field").asText()))||List.of("METRIC","FIELD","LAB","PVT-M").stream().noneMatch(system->v.path("unit").asText().equals(unitFor(system,v.path("field").asText()))))return false;
        for(String k:List.of("originalValue","targetValue"))if(!v.path(k).isNumber()||!scalar(v.path("field").asText(),v.path("unit").asText(),v.path(k).asDouble()))return false;
        return v.path("originalValue").asDouble()!=v.path("targetValue").asDouble()&&validRow(v.path("recordValues"))&&number(v.path("recordValues").path(index(v.path("field").asText())).asText())==v.path("originalValue").asDouble();
    }
    public static boolean validRow(JsonNode row) {
        if(!row.isArray()||row.size()!=5)return false;
        for(int i=0;i<5;i++) {
            var token=row.path(i);if(!token.isString()||token.asText().length()>64||!token.asText().matches("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eEdD][+-]?[0-9]+)?$"))return false;
            double n=number(token.asText());if(!Double.isFinite(n)||((i==1||i==3)?n<=0:n<0))return false;
        }
        return true;
    }
    public static boolean matchesInspection(JsonNode v,JsonNode inspection) {
        if(!valid(v)||inspection==null||!"eclipse-data-inspection/4".equals(inspection.path("schemaVersion").asText())
            ||!Set.of("METRIC","FIELD","LAB","PVT-M").contains(inspection.path("unitSystem").asText())||inspection.path("phases").size()!=1||!"WATER".equals(inspection.path("phases").path(0).asText())
            ||inspection.path("packageFiles").size()!=1)return false;
        var file=inspection.path("packageFiles").path(0);var c=inspection.path("pvtwContext");
        return v.path("unit").asText().equals(unitFor(inspection.path("unitSystem").asText(),v.path("field").asText()))&&"eclipse-pvtw-context/1".equals(c.path("schemaVersion").asText())&&file.path("relativePath").equals(v.path("sourceFile"))&&file.path("sha256").equals(v.path("sourceSha256"))
            &&c.path("sourceFile").equals(v.path("sourceFile"))&&c.path("lineNumber").equals(v.path("lineNumber"))&&c.path("recordValues").equals(v.path("recordValues"));
    }
    private static double number(String text) {try{return Double.parseDouble(text.replace('D','E').replace('d','e'));}catch(NumberFormatException e){return Double.NaN;}}
}
