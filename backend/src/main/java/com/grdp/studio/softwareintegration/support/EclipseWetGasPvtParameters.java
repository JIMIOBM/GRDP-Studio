package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** One explicit wet-gas PVTG table, including its original undersaturated branches. */
public final class EclipseWetGasPvtParameters {
    public static final String SCHEMA="eclipse-pvtg-parameters/1";
    private static final Set<String> FIELDS=Set.of("schemaVersion","baselineRunId","sourceFile","sourceSha256","keywordLineNumber","unit","multiplier","records","field");
    private EclipseWetGasPvtParameters() {}
    private static boolean fields(JsonNode value,Set<String> expected) {
        if(value==null||!value.isObject())return false;
        var names=new HashSet<String>();value.properties().forEach(p->names.add(p.getKey()));return names.equals(expected);
    }
    public static boolean validRecords(JsonNode records,int keywordLine) {
        if(!records.isArray()||records.size()<2||records.size()>32||records.path(records.size()-1).path("rows").size()<2)return false;
        int priorLine=keywordLine,total=0;double priorPressure=-1;
        for(var record:records){var rows=record.path("rows");
            if(!fields(record,Set.of("rows"))||!rows.isArray()||rows.size()<1||rows.size()>64)return false;
            total+=rows.size();if(total>256)return false;
            double priorRv=Double.POSITIVE_INFINITY;int index=0;
            for(var row:rows){
                if(!fields(row,Set.of("lineNumber","values"))||!row.path("lineNumber").isIntegralNumber()||!row.path("lineNumber").canConvertToInt())return false;
                int line=row.path("lineNumber").asInt();var values=row.path("values");boolean first=index++==0;int size=first?4:3,start=first?1:0;
                if(line<=priorLine||line>2_000_000||!values.isArray()||values.size()!=size)return false;
                var n=new double[size];for(int i=0;i<size;i++){
                    var token=values.path(i);if(!token.isString()||token.asText().length()>64||!token.asText().matches("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eEdD][+-]?[0-9]+)?$"))return false;
                    n[i]=number(token.asText());if(!Double.isFinite(n[i]))return false;
                }
                if(first){if(n[0]<0||n[0]<=priorPressure)return false;priorPressure=n[0];}
                if(n[start]<0||n[start]>=priorRv||n[start+1]<=0||n[start+1]>100000||n[start+2]<=0||n[start+2]>1000)return false;
                priorLine=line;priorRv=n[start];
            }
        }return true;
    }
    public static boolean valid(JsonNode v) {
        if(!fields(v,FIELDS)||!v.path("schemaVersion").isString()||!SCHEMA.equals(v.path("schemaVersion").asText())
            ||!v.path("field").isString()||!Set.of("VISCOSITY","FORMATION_VOLUME_FACTOR").contains(v.path("field").asText())
            ||!v.path("unit").isString()||!("VISCOSITY".equals(v.path("field").asText())?"cP".equals(v.path("unit").asText()):Set.of("rb/Mscf","rm3/sm3","rcc/scc").contains(v.path("unit").asText()))
            ||!v.path("sourceFile").isString()||!v.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$")
            ||!v.path("sourceSha256").isString()||!v.path("sourceSha256").asText().matches("^[a-f0-9]{64}$")
            ||!v.path("baselineRunId").isIntegralNumber()||!v.path("baselineRunId").canConvertToLong()||v.path("baselineRunId").asLong()<=0
            ||!v.path("keywordLineNumber").isIntegralNumber()||!v.path("keywordLineNumber").canConvertToInt()||v.path("keywordLineNumber").asInt()<=0||v.path("keywordLineNumber").asInt()>2_000_000
            ||!v.path("multiplier").isNumber())return false;
        double multiplier=v.path("multiplier").asDouble();
        if(!Double.isFinite(multiplier)||multiplier<.000001||multiplier>100||multiplier==1||!validRecords(v.path("records"),v.path("keywordLineNumber").asInt()))return false;
        boolean volume="FORMATION_VOLUME_FACTOR".equals(v.path("field").asText());double maximum=volume?100000:1000;
        for(var record:v.path("records")){for(var row:record.path("rows")){
            var values=row.path("values");double target=number(values.path(values.size()-(volume?2:1)).asText())*multiplier;
            if(!Double.isFinite(target)||target<=0||target>maximum)return false;
        }}return true;
    }
    public static boolean matchesInspection(JsonNode v,JsonNode inspection) {
        if(!valid(v)||inspection==null||!"eclipse-data-inspection/4".equals(inspection.path("schemaVersion").asText())
            ||!Set.of("METRIC","FIELD","LAB","PVT-M").contains(inspection.path("unitSystem").asText())||inspection.path("packageFiles").size()!=1)return false;
        String unit=switch(inspection.path("unitSystem").asText()){case "FIELD"->"rb/Mscf";case "LAB"->"rcc/scc";default->"rm3/sm3";};
        if(!v.path("unit").asText().equals("VISCOSITY".equals(v.path("field").asText())?"cP":unit))return false;
        var phases=new HashSet<String>();for(var phase:inspection.path("phases"))phases.add(phase.asText());if(!phases.containsAll(Set.of("OIL","GAS")))return false;
        var file=inspection.path("packageFiles").path(0);var c=inspection.path("wetGasPvtContext");
        return "eclipse-wet-gas-pvt-context/1".equals(c.path("schemaVersion").asText())&&v.path("sourceFile").equals(file.path("relativePath"))
            &&v.path("sourceSha256").equals(file.path("sha256"))&&v.path("sourceFile").equals(c.path("sourceFile"))
            &&v.path("keywordLineNumber").asInt()==c.path("keywordLineNumber").asInt()&&v.path("records").equals(c.path("records"));
    }
    private static double number(String value){try{return Double.parseDouble(value.replace('D','E').replace('d','e'));}catch(NumberFormatException error){return Double.NaN;}}
}
