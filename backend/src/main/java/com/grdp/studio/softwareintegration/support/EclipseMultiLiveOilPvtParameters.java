package com.grdp.studio.softwareintegration.support;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
/** Complete live-oil branches and unchanged dry-gas table in one matched PVT/equilibration region. */
public final class EclipseMultiLiveOilPvtParameters {
    public static final String SCHEMA="eclipse-multi-pvto-parameters/1";
    private EclipseMultiLiveOilPvtParameters(){}
    private static boolean fields(JsonNode v,Set<String> expected){if(v==null||!v.isObject())return false;var keys=new HashSet<String>();v.properties().forEach(p->keys.add(p.getKey()));return keys.equals(expected);}
    public static boolean validGasTable(JsonNode t){return fields(t,Set.of("keywordLineNumber","rows"))&&t.path("keywordLineNumber").isIntegralNumber()&&t.path("keywordLineNumber").canConvertToInt()&&t.path("keywordLineNumber").asInt()>0&&t.path("keywordLineNumber").asInt()<=2000000&&EclipseGasPvtParameters.validRows(t.path("rows"),t.path("keywordLineNumber").asInt());}
    public static boolean validRegions(JsonNode regions,int count,long cells){
        if(count<2||count>8||regions==null||!regions.isArray()||regions.size()!=count||cells<1||cells>2000000)return false;
        long total=0;int index=0,oilHeader=0,gasHeader=0,oilEnd=0,gasEnd=0;
        for(var r:regions){
            if(!fields(r,Set.of("number","cellCount","keywordLineNumber","records","gasTable"))||!r.path("number").isIntegralNumber()||!r.path("number").canConvertToInt()||r.path("number").asInt()!=++index||!r.path("cellCount").isIntegralNumber()||!r.path("cellCount").canConvertToInt()||r.path("cellCount").asInt()<1||!r.path("keywordLineNumber").isIntegralNumber()||!r.path("keywordLineNumber").canConvertToInt()||r.path("keywordLineNumber").asInt()<1||r.path("keywordLineNumber").asInt()>2000000||!EclipseLiveOilPvtParameters.validRecords(r.path("records"),r.path("keywordLineNumber").asInt())||!validGasTable(r.path("gasTable")))return false;
            total+=r.path("cellCount").asInt();if(total>cells)return false;var gas=r.path("gasTable");var records=r.path("records");var rows=gas.path("rows");
            if(index==1){oilHeader=r.path("keywordLineNumber").asInt();gasHeader=gas.path("keywordLineNumber").asInt();}
            else if(oilHeader!=r.path("keywordLineNumber").asInt()||gasHeader!=gas.path("keywordLineNumber").asInt()||records.path(0).path("rows").path(0).path("lineNumber").asInt()<=oilEnd||rows.path(0).path("lineNumber").asInt()<=gasEnd)return false;
            var last=records.path(records.size()-1).path("rows");oilEnd=last.path(last.size()-1).path("lineNumber").asInt();gasEnd=rows.path(rows.size()-1).path("lineNumber").asInt();
        }return total==cells&&gasEnd<oilHeader;
    }
    private static ObjectNode single(JsonNode p){var v=(ObjectNode)p.deepCopy();v.remove("regionNumber");v.remove("gasTable");v.put("schemaVersion",EclipseLiveOilPvtParameters.SCHEMA);return v;}
    public static boolean valid(JsonNode p){
        if(!fields(p,Set.of("schemaVersion","baselineRunId","sourceFile","sourceSha256","keywordLineNumber","unit","multiplier","records","field","regionNumber","gasTable"))||!p.path("schemaVersion").isString()||!SCHEMA.equals(p.path("schemaVersion").asText())||!p.path("regionNumber").isIntegralNumber()||!p.path("regionNumber").canConvertToInt()||p.path("regionNumber").asInt()<1||p.path("regionNumber").asInt()>8||!validGasTable(p.path("gasTable"))||!EclipseLiveOilPvtParameters.valid(single(p)))return false;
        var rows=p.path("gasTable").path("rows");return rows.path(rows.size()-1).path("lineNumber").asInt()<p.path("keywordLineNumber").asInt();
    }
    public static boolean validContext(JsonNode c,JsonNode inspection){
        if(!fields(c,Set.of("schemaVersion","sourceFile","regionCount","regions"))||!c.path("schemaVersion").isString()||!"eclipse-multi-live-oil-pvt-context/1".equals(c.path("schemaVersion").asText())||!c.path("sourceFile").isString()||!c.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$")||!c.path("regionCount").isIntegralNumber()||!c.path("regionCount").canConvertToInt()||inspection.path("packageFiles").size()!=1||!c.path("sourceFile").equals(inspection.path("packageFiles").path(0).path("relativePath"))||!c.path("sourceFile").equals(inspection.path("caseName"))||!Set.of("FIELD","METRIC","LAB","PVT-M").contains(inspection.path("unitSystem").asText()))return false;
        var phases=new HashSet<String>();for(var phase:inspection.path("phases"))phases.add(phase.asText());if(!phases.equals(Set.of("OIL","GAS","WATER")))return false;long cells=1;
        for(var axis:new String[]{"nx","ny","nz"}){var n=inspection.path("dimensions").path(axis);if(!n.isIntegralNumber()||!n.canConvertToInt()||n.asInt()<1||n.asInt()>2000000)return false;cells*=n.asInt();if(cells>2000000)return false;}
        return validRegions(c.path("regions"),c.path("regionCount").asInt(),cells);
    }
    public static boolean matchesInspection(JsonNode p,JsonNode inspection){
        if(!valid(p)||inspection==null||!inspection.isObject())return false;var c=inspection.path("multiLiveOilPvtContext");if(!validContext(c,inspection))return false;var r=c.path("regions").path(p.path("regionNumber").asInt()-1);
        if(!p.path("gasTable").equals(r.path("gasTable"))||!p.path("records").equals(r.path("records"))||!p.path("keywordLineNumber").equals(r.path("keywordLineNumber")))return false;
        var adapted=(ObjectNode)inspection.deepCopy();var ctx=adapted.putObject("liveOilPvtContext");ctx.put("schemaVersion","eclipse-live-oil-pvt-context/1");ctx.set("sourceFile",c.path("sourceFile").deepCopy());ctx.set("keywordLineNumber",r.path("keywordLineNumber").deepCopy());ctx.set("records",r.path("records").deepCopy());return EclipseLiveOilPvtParameters.matchesInspection(single(p),adapted);
    }
}
