package com.grdp.studio.softwareintegration.support;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
/** Bind both dead-oil and dry-gas tables to one explicit PVT region. */
public final class EclipseMultiPvtParameters {
    public static final String SCHEMA="eclipse-multi-pvt-parameters/1";
    private EclipseMultiPvtParameters(){}
    private static boolean fields(JsonNode v,Set<String> expected){if(v==null||!v.isObject())return false;var names=new HashSet<String>();v.properties().forEach(p->names.add(p.getKey()));return names.equals(expected);}
    public static boolean validTables(JsonNode tables){
        if(tables==null||!tables.isArray()||tables.size()!=2)return false;int end=0,index=0;
        for(var t:tables){String key=index++==0?"PVDO":"PVDG";
            if(!fields(t,Set.of("keyword","keywordLineNumber","rows"))||!t.path("keyword").isString()||!key.equals(t.path("keyword").asText())||!t.path("keywordLineNumber").isIntegralNumber()||!t.path("keywordLineNumber").canConvertToInt()||t.path("keywordLineNumber").asInt()<=end||t.path("keywordLineNumber").asInt()>2000000)return false;
            if("PVDO".equals(key)?!EclipseOilPvtParameters.validRows(t.path("rows"),t.path("keywordLineNumber").asInt()):!EclipseGasPvtParameters.validRows(t.path("rows"),t.path("keywordLineNumber").asInt()))return false;
            var rows=t.path("rows");end=rows.path(rows.size()-1).path("lineNumber").asInt();
        }return true;
    }
    public static boolean validRegions(JsonNode regions,int count,long cells){
        if(count<2||count>8||regions==null||!regions.isArray()||regions.size()!=count||cells<1||cells>2000000)return false;
        long total=0;int index=0;int[] headers=new int[2],ends=new int[2];
        for(var r:regions){
            if(!fields(r,Set.of("number","cellCount","tables"))||!r.path("number").isIntegralNumber()||!r.path("number").canConvertToInt()||r.path("number").asInt()!=++index||!r.path("cellCount").isIntegralNumber()||!r.path("cellCount").canConvertToInt()||r.path("cellCount").asInt()<1||!validTables(r.path("tables")))return false;
            total+=r.path("cellCount").asInt();if(total>cells)return false;
            for(int k=0;k<2;k++){var t=r.path("tables").path(k);var rows=t.path("rows");int header=t.path("keywordLineNumber").asInt();
                if(index==1)headers[k]=header;else if(header!=headers[k]||rows.path(0).path("lineNumber").asInt()<=ends[k])return false;
                ends[k]=rows.path(rows.size()-1).path("lineNumber").asInt();
            }
        }return total==cells&&ends[0]<headers[1];
    }
    private static ObjectNode single(JsonNode p){
        var selected=p.path("tables").path("PVDO".equals(p.path("keyword").asText())?0:1);var v=(ObjectNode)p.deepCopy();
        v.remove("regionNumber");v.remove("tables");v.remove("keyword");v.put("schemaVersion","PVDO".equals(p.path("keyword").asText())?EclipseOilPvtParameters.FIELD_SCHEMA:EclipseGasPvtParameters.FIELD_SCHEMA);v.set("keywordLineNumber",selected.path("keywordLineNumber").deepCopy());v.set("rows",selected.path("rows").deepCopy());return v;
    }
    public static boolean valid(JsonNode p){
        if(!fields(p,Set.of("schemaVersion","baselineRunId","sourceFile","sourceSha256","unit","multiplier","field","tables","regionNumber","keyword"))||!p.path("schemaVersion").isString()||!SCHEMA.equals(p.path("schemaVersion").asText())||!p.path("regionNumber").isIntegralNumber()||!p.path("regionNumber").canConvertToInt()||p.path("regionNumber").asInt()<1||p.path("regionNumber").asInt()>8||!p.path("keyword").isString()||!Set.of("PVDO","PVDG").contains(p.path("keyword").asText())||!validTables(p.path("tables")))return false;
        return "PVDO".equals(p.path("keyword").asText())?EclipseOilPvtParameters.valid(single(p)):EclipseGasPvtParameters.valid(single(p));
    }
    public static boolean validContext(JsonNode c,JsonNode inspection){
        if(!fields(c,Set.of("schemaVersion","sourceFile","regionCount","regions"))||!c.path("schemaVersion").isString()||!"eclipse-multi-pvt-context/1".equals(c.path("schemaVersion").asText())||!c.path("sourceFile").isString()||!c.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$")||!c.path("regionCount").isIntegralNumber()||!c.path("regionCount").canConvertToInt()||inspection.path("packageFiles").size()!=1||!c.path("sourceFile").equals(inspection.path("packageFiles").path(0).path("relativePath"))||!c.path("sourceFile").equals(inspection.path("caseName"))||!Set.of("FIELD","METRIC","LAB","PVT-M").contains(inspection.path("unitSystem").asText()))return false;
        var phases=new HashSet<String>();for(var phase:inspection.path("phases"))phases.add(phase.asText());if(!phases.equals(Set.of("OIL","GAS","WATER")))return false;
        long cells=1;for(var axis:new String[]{"nx","ny","nz"}){var n=inspection.path("dimensions").path(axis);if(!n.isIntegralNumber()||!n.canConvertToInt()||n.asInt()<1||n.asInt()>2000000)return false;cells*=n.asInt();if(cells>2000000)return false;}
        return validRegions(c.path("regions"),c.path("regionCount").asInt(),cells);
    }
    public static boolean matchesInspection(JsonNode p,JsonNode inspection){
        if(!valid(p)||inspection==null||!inspection.isObject())return false;var c=inspection.path("multiPvtContext");if(!validContext(c,inspection))return false;
        var r=c.path("regions").path(p.path("regionNumber").asInt()-1);if(!p.path("tables").equals(r.path("tables")))return false;
        var adapted=(ObjectNode)inspection.deepCopy();boolean oil="PVDO".equals(p.path("keyword").asText());var t=r.path("tables").path(oil?0:1);var ctx=adapted.putObject(oil?"oilPvtContext":"gasPvtContext");ctx.put("schemaVersion",oil?"eclipse-oil-pvt-context/1":"eclipse-gas-pvt-context/1");ctx.set("sourceFile",c.path("sourceFile").deepCopy());ctx.set("keywordLineNumber",t.path("keywordLineNumber").deepCopy());ctx.set("rows",t.path("rows").deepCopy());
        return oil?EclipseOilPvtParameters.matchesInspection(single(p),adapted):EclipseGasPvtParameters.matchesInspection(single(p),adapted);
    }
}
