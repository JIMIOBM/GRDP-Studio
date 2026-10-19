package com.grdp.studio.softwareintegration.support;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Set;
/** Bind one complete SWOF/SGOF pair to its explicit SATNUM region. */
public final class EclipseMultiSatRelpermParameters {
    public static final String SCHEMA="eclipse-multi-sat-relperm-parameters/1";
    private static final Set<String> FIELDS=Set.of("schemaVersion","baselineRunId","sourceFile","sourceSha256","unit","multiplier","field","tables","regionNumber");
    private EclipseMultiSatRelpermParameters(){}
    private static boolean fields(JsonNode v,Set<String> expected){if(v==null||!v.isObject())return false;var names=new HashSet<String>();v.properties().forEach(p->names.add(p.getKey()));return names.equals(expected);}
    private static ObjectNode singleRequest(JsonNode v){var p=(ObjectNode)v.deepCopy();p.remove("regionNumber");p.put("schemaVersion",EclipseCoupledRelpermParameters.SCHEMA);return p;}
    public static boolean valid(JsonNode v){return fields(v,FIELDS)&&v.path("schemaVersion").isString()&&SCHEMA.equals(v.path("schemaVersion").asText())&&v.path("regionNumber").isIntegralNumber()&&v.path("regionNumber").canConvertToInt()&&v.path("regionNumber").asInt()>=1&&v.path("regionNumber").asInt()<=8&&EclipseCoupledRelpermParameters.valid(singleRequest(v));}
    public static boolean validRegions(JsonNode regions,int count,long cells){return validRegions(regions,count,cells,false);}
    public static boolean validRegions(JsonNode regions,int count,long cells,boolean familyII){
        if(count<2||count>8||cells<1||cells>2000000||!regions.isArray()||regions.size()!=count)return false;
        long total=0;int number=1;var ends=new HashMap<String,Integer>();var headers=new HashMap<String,Integer>();
        for(var region:regions){
            if(!fields(region,Set.of("number","cellCount","tables"))||!region.path("number").isIntegralNumber()||!region.path("number").canConvertToInt()||region.path("number").asInt()!=number++
                ||!region.path("cellCount").isIntegralNumber()||!region.path("cellCount").canConvertToInt()||region.path("cellCount").asInt()<1||region.path("cellCount").asInt()>2000000||(familyII?!EclipseRelpermParameters.validTables(region.path("tables")):!EclipseCoupledRelpermParameters.validTables(region.path("tables"))))return false;
            total+=region.path("cellCount").asInt();
            int priorEnd=0;
            for(var table:region.path("tables")){
                String key=table.path("keyword").asText();int header=table.path("keywordLineNumber").asInt();var rows=table.path("rows");if(header<=priorEnd)return false;priorEnd=rows.path(rows.size()-1).path("lineNumber").asInt();
                if(headers.containsKey(key)&&headers.get(key)!=header||rows.path(0).path("lineNumber").asInt()<=ends.getOrDefault(key,header))return false;
                headers.put(key,header);ends.put(key,rows.path(rows.size()-1).path("lineNumber").asInt());
            }
        }return total==cells;
    }
    public static boolean matchesInspection(JsonNode v,JsonNode inspection){
        if(!valid(v)||inspection==null||!inspection.isObject())return false;var c=inspection.path("multiSatRelpermContext");
        long cells=1;for(var axis:new String[]{"nx","ny","nz"}){var n=inspection.path("dimensions").path(axis);if(!n.isIntegralNumber()||!n.canConvertToInt()||n.asInt()<1||n.asInt()>2000000)return false;cells*=n.asInt();if(cells>2000000)return false;}
        if(!"eclipse-multi-sat-relperm-context/1".equals(c.path("schemaVersion").asText())||!c.path("regionCount").isIntegralNumber()||!c.path("regionCount").canConvertToInt()||!validRegions(c.path("regions"),c.path("regionCount").asInt(),cells))return false;
        var region=c.path("regions").path(v.path("regionNumber").asInt()-1);if(region.isMissingNode()||region.path("number").asInt()!=v.path("regionNumber").asInt())return false;
        var adapted=(ObjectNode)inspection.deepCopy();var single=adapted.putObject("coupledRelpermContext");single.put("schemaVersion","eclipse-coupled-relperm-context/1");single.set("sourceFile",c.path("sourceFile").deepCopy());single.set("tables",region.path("tables").deepCopy());
        return EclipseCoupledRelpermParameters.matchesInspection(singleRequest(v),adapted);
    }
}
