package com.grdp.studio.softwareintegration.support;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
/** An explicit water reference record bound to one inspected fluid region. */
public final class EclipseMultiWaterPvtParameters {
    public static final String SCHEMA="eclipse-multi-pvtw-parameters/1";
    public static final String SCHEMA2="eclipse-multi-pvtw-parameters/2";
    public static final String SCHEMA3="eclipse-multi-pvtw-parameters/3";
    private EclipseMultiWaterPvtParameters(){}
    private static boolean fields(JsonNode v,Set<String> expected){if(v==null||!v.isObject())return false;var keys=new HashSet<String>();v.properties().forEach(p->keys.add(p.getKey()));return keys.equals(expected);}
    private static ObjectNode single(JsonNode p){var v=(ObjectNode)p.deepCopy();v.remove("regionNumber");v.put("schemaVersion",EclipsePvtwParameters.SCHEMA2);v.set("recordValues",effective(v.path("recordValues")));return v;}
    public static boolean valid(JsonNode p){return fields(p,Set.of("schemaVersion","regionNumber","baselineRunId","sourceFile","sourceSha256","lineNumber","field","unit","originalValue","targetValue","recordValues"))&&Set.of(SCHEMA,SCHEMA2,SCHEMA3).contains(p.path("schemaVersion").asText())&&validRecord(p.path("recordValues"),!SCHEMA.equals(p.path("schemaVersion").asText()),SCHEMA3.equals(p.path("schemaVersion").asText()))&&p.path("regionNumber").isIntegralNumber()&&p.path("regionNumber").canConvertToInt()&&p.path("regionNumber").asInt()>=1&&p.path("regionNumber").asInt()<=8&&EclipsePvtwParameters.valid(single(p));}
    public static boolean validContext(JsonNode c,JsonNode inspection){
        if(inspection==null||!inspection.isObject()||!fields(c,Set.of("schemaVersion","sourceFile","regionCount","regions"))||!c.path("schemaVersion").isString()||!Set.of("eclipse-multi-water-pvt-context/1","eclipse-multi-water-pvt-context/2","eclipse-multi-water-pvt-context/3").contains(c.path("schemaVersion").asText())||!c.path("sourceFile").isString()||!c.path("regionCount").isIntegralNumber()||!c.path("regionCount").canConvertToInt()||!Set.of("FIELD","METRIC","LAB","PVT-M").contains(inspection.path("unitSystem").asText()))return false;
        var parent=inspection.path("multiPvtContext");boolean fluid=EclipseMultiPvtParameters.validContext(parent,inspection),pure=inspection.path("phases").size()==1&&"WATER".equals(inspection.path("phases").path(0).asText())&&!inspection.has("multiPvtContext");
        if(!fluid&&!pure||!c.path("sourceFile").equals(inspection.path("caseName"))||inspection.path("packageFiles").size()!=1||!c.path("sourceFile").equals(inspection.path("packageFiles").path(0).path("relativePath")))return false;
        if(fluid&&(!c.path("sourceFile").equals(parent.path("sourceFile"))||!c.path("regionCount").equals(parent.path("regionCount"))))return false;
        long cells=1;for(var axis:new String[]{"nx","ny","nz"}){var n=inspection.path("dimensions").path(axis);if(!n.isIntegralNumber()||!n.canConvertToInt()||n.asInt()<1||n.asInt()>2000000||cells*n.asInt()>2000000)return false;cells*=n.asInt();}
        int count=c.path("regionCount").asInt();var regions=c.path("regions");if(count<2||count>8||!regions.isArray()||regions.size()!=count)return false;
        var otherLines=new HashSet<Integer>();for(var key:new String[]{"multiPvtContext","multiSatRelpermContext"})for(var r:inspection.path(key).path("regions"))for(var table:r.path("tables")){otherLines.add(table.path("keywordLineNumber").asInt());for(var row:table.path("rows"))otherLines.add(row.path("lineNumber").asInt());}
        var names=new String[]{"REFERENCE_PRESSURE","FORMATION_VOLUME_FACTOR","COMPRESSIBILITY","VISCOSITY","VISCOSIBILITY"};int prior=0,index=0;long total=0;boolean missing=false,star=false,allowsMissing=!"eclipse-multi-water-pvt-context/1".equals(c.path("schemaVersion").asText()),allowsStar="eclipse-multi-water-pvt-context/3".equals(c.path("schemaVersion").asText());
        for(var r:regions){var p=parent.path("regions").path(index++);
            if(!fields(r,Set.of("number","cellCount","lineNumber","recordValues"))||!r.path("number").isIntegralNumber()||!r.path("number").canConvertToInt()||r.path("number").asInt()!=index||!r.path("cellCount").isIntegralNumber()||!r.path("cellCount").canConvertToInt()||r.path("cellCount").asInt()<1||(fluid&&(!r.path("number").equals(p.path("number"))||!r.path("cellCount").equals(p.path("cellCount"))))||!r.path("lineNumber").isIntegralNumber()||!r.path("lineNumber").canConvertToInt()||r.path("lineNumber").asInt()<=prior||r.path("lineNumber").asInt()>2000000||otherLines.contains(r.path("lineNumber").asInt())||!validRecord(r.path("recordValues"),allowsMissing,allowsStar))return false;
            missing|=r.path("recordValues").size()==4;star|=hasStar(r.path("recordValues"));var row=effective(r.path("recordValues"));total+=r.path("cellCount").asInt();if(total>cells)return false;prior=r.path("lineNumber").asInt();for(int column=0;column<5;column++){double n=Double.parseDouble(row.path(column).asText().replace('D','E').replace('d','e'));if(!EclipsePvtwParameters.scalar(names[column],EclipsePvtwParameters.unitFor(inspection.path("unitSystem").asText(),names[column]),n))return false;}
        }return total==cells&&(star?"eclipse-multi-water-pvt-context/3":missing?"eclipse-multi-water-pvt-context/2":"eclipse-multi-water-pvt-context/1").equals(c.path("schemaVersion").asText());
    }
    private static boolean hasStar(JsonNode row){return row.isArray()&&row.size()==5&&row.path(4).isString()&&"1*".equals(row.path(4).asText());}
    private static JsonNode effective(JsonNode row){if(row.isArray()&&row.size()==4){var copy=(tools.jackson.databind.node.ArrayNode)row.deepCopy();copy.add("0");return copy;}if(hasStar(row)){var copy=(tools.jackson.databind.node.ArrayNode)row.deepCopy();copy.remove(4);copy.add("0");return copy;}return row;}
    private static boolean validRecord(JsonNode row,boolean allowsMissing,boolean allowsStar){return row.isArray()&&(row.size()==5||(allowsMissing&&row.size()==4))&&(!hasStar(row)||allowsStar)&&EclipsePvtwParameters.validRow(effective(row));}
    public static boolean matchesInspection(JsonNode p,JsonNode inspection){
        if(!valid(p)||inspection==null||!inspection.isObject())return false;var c=inspection.path("multiWaterPvtContext");if(!validContext(c,inspection)||!(SCHEMA3.equals(p.path("schemaVersion").asText())?"eclipse-multi-water-pvt-context/3":SCHEMA2.equals(p.path("schemaVersion").asText())?"eclipse-multi-water-pvt-context/2":"eclipse-multi-water-pvt-context/1").equals(c.path("schemaVersion").asText()))return false;
        var r=c.path("regions").path(p.path("regionNumber").asInt()-1);
        return p.path("sourceFile").equals(c.path("sourceFile"))&&p.path("sourceSha256").equals(inspection.path("packageFiles").path(0).path("sha256"))&&p.path("lineNumber").equals(r.path("lineNumber"))&&p.path("recordValues").equals(r.path("recordValues"))&&p.path("unit").asText().equals(EclipsePvtwParameters.unitFor(inspection.path("unitSystem").asText(),p.path("field").asText()));
    }
}
