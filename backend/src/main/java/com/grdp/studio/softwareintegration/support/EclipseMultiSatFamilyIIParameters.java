package com.grdp.studio.softwareintegration.support;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
/** Bind a complete SWFN/SGFN/SOF3 trio to its saturation region. */
public final class EclipseMultiSatFamilyIIParameters {
    public static final String SCHEMA="eclipse-multi-sat-relperm-parameters/2";
    private static final Set<String> FIELDS=Set.of("schemaVersion","baselineRunId","sourceFile","sourceSha256","unit","multiplier","field","tables","regionNumber");
    private EclipseMultiSatFamilyIIParameters(){}
    private static boolean fields(JsonNode v){if(v==null||!v.isObject())return false;var names=new HashSet<String>();v.properties().forEach(p->names.add(p.getKey()));return names.equals(FIELDS);}
    private static boolean orderedTables(JsonNode tables){int end=0;for(var t:tables){if(t.path("keywordLineNumber").asInt()<=end)return false;var rows=t.path("rows");end=rows.path(rows.size()-1).path("lineNumber").asInt();}return true;}
    private static ObjectNode singleRequest(JsonNode v){
        String key=switch(v.path("field").asText()){case "WATER_KR"->"SWFN";case "GAS_KR"->"SGFN";default->"SOF3";};
        JsonNode selected=null;for(var t:v.path("tables"))if(key.equals(t.path("keyword").asText()))selected=t;
        var p=(ObjectNode)v.deepCopy();p.remove("regionNumber");p.remove("tables");p.remove("field");p.put("schemaVersion","SGFN".equals(key)?EclipseRelpermParameters.GAS_SCHEMA:EclipseRelpermParameters.SCHEMA);p.put("keyword",key);p.set("keywordLineNumber",selected.path("keywordLineNumber").deepCopy());p.set("rows",selected.path("rows").deepCopy());return p;
    }
    public static boolean valid(JsonNode v){
        return fields(v)&&v.path("schemaVersion").isString()&&SCHEMA.equals(v.path("schemaVersion").asText())&&v.path("regionNumber").isIntegralNumber()&&v.path("regionNumber").canConvertToInt()&&v.path("regionNumber").asInt()>=1&&v.path("regionNumber").asInt()<=8
            &&v.path("field").isString()&&Set.of("WATER_KR","GAS_KR","OIL_KR").contains(v.path("field").asText())&&EclipseRelpermParameters.validTables(v.path("tables"))&&orderedTables(v.path("tables"))&&EclipseRelpermParameters.valid(singleRequest(v));
    }
    public static boolean matchesInspection(JsonNode v,JsonNode inspection){
        if(!valid(v)||inspection==null||!inspection.isObject())return false;var c=inspection.path("multiSatRelpermContext");long cells=1;
        for(var axis:new String[]{"nx","ny","nz"}){var n=inspection.path("dimensions").path(axis);if(!n.isIntegralNumber()||!n.canConvertToInt()||n.asInt()<1||n.asInt()>2000000)return false;cells*=n.asInt();if(cells>2000000)return false;}
        if(!"eclipse-multi-sat-relperm-context/2".equals(c.path("schemaVersion").asText())||!c.path("regionCount").isIntegralNumber()||!c.path("regionCount").canConvertToInt()||!EclipseMultiSatRelpermParameters.validRegions(c.path("regions"),c.path("regionCount").asInt(),cells,true))return false;
        var region=c.path("regions").path(v.path("regionNumber").asInt()-1);if(region.isMissingNode()||!v.path("tables").equals(region.path("tables")))return false;
        var adapted=(ObjectNode)inspection.deepCopy();var single=adapted.putObject("relpermContext");single.put("schemaVersion","eclipse-relperm-context/1");single.set("sourceFile",c.path("sourceFile").deepCopy());single.set("tables",region.path("tables").deepCopy());
        return EclipseRelpermParameters.matchesInspection(singleRequest(v),adapted);
    }
}
