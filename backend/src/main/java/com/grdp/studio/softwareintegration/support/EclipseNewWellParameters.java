package com.grdp.studio.softwareintegration.support;

import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationArtifactEntity;
import tools.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Initial independent water producer with explicit source, grid and limits. */
public final class EclipseNewWellParameters {
    public static final String SCHEMA="eclipse-new-well-parameters/1";
    private static final Set<String> FIELDS=Set.of("schemaVersion","baselineRunId","sourceFile","sourceSha256","gridFile","gridArtifactSha256","nx","ny","nz","insertionLine","well","group","i","j","k","referenceDepth","lengthUnit","diameter","kh","skin","waterRate","rateUnit","bhpLimit","pressureUnit");
    private EclipseNewWellParameters() {}
    public static boolean valid(JsonNode p) {
        if(p==null||!p.isObject())return false;
        var names=new HashSet<String>();p.properties().forEach(x->names.add(x.getKey()));
        if(!names.equals(FIELDS)||!SCHEMA.equals(p.path("schemaVersion").asText())||!p.path("baselineRunId").isIntegralNumber()||!p.path("baselineRunId").canConvertToLong()||p.path("baselineRunId").asLong()<=0)return false;
        for(String k:List.of("sourceFile","sourceSha256","gridFile","gridArtifactSha256","well","group","lengthUnit","rateUnit","pressureUnit"))if(!p.path(k).isString())return false;
        if(!p.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$")||!p.path("gridFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.EGRID$"))return false;
        for(String k:List.of("sourceSha256","gridArtifactSha256"))if(!p.path(k).asText().matches("^[0-9a-f]{64}$"))return false;
        for(String k:List.of("well","group"))if(!p.path(k).asText().matches("^[A-Za-z][A-Za-z0-9_-]{0,7}$"))return false;
        if("FIELD".equalsIgnoreCase(p.path("group").asText()))return false;
        for(String k:List.of("nx","ny","nz","insertionLine","i","j","k"))if(!p.path(k).isIntegralNumber()||!p.path(k).canConvertToInt()||p.path(k).asInt()<=0||p.path(k).asInt()>2_000_000)return false;
        if((long)p.path("nx").asInt()*p.path("ny").asInt()*p.path("nz").asInt()>2_000_000||p.path("i").asInt()>p.path("nx").asInt()||p.path("j").asInt()>p.path("ny").asInt()||p.path("k").asInt()>p.path("nz").asInt())return false;
        for(String k:List.of("referenceDepth","diameter","kh","skin","waterRate","bhpLimit"))if(!p.path(k).isNumber()||!Double.isFinite(p.path(k).asDouble()))return false;
        String length=p.path("lengthUnit").asText();double diameter=p.path("diameter").asDouble();
        return List.of("METRIC","FIELD","LAB","PVT-M").stream().anyMatch(s->unitsMatch(p,s))
                && positive(p,"referenceDepth",10_000_000)&&positive(p,"kh",1_000_000_000)&&positive(p,"waterRate",10_000_000)&&positive(p,"bhpLimit",1_000_000)
                && diameter>0&&diameter*("ft".equals(length)?0.3048:"cm".equals(length)?0.01:1)<=2&&p.path("skin").asDouble()>=0&&p.path("skin").asDouble()<=100;
    }
    private static boolean positive(JsonNode p,String k,double max){return p.path(k).asDouble()>0&&p.path(k).asDouble()<=max;}
    private static boolean unitsMatch(JsonNode p,String s){
        String length="FIELD".equals(s)?"ft":"LAB".equals(s)?"cm":"m";
        String rate="FIELD".equals(s)?"STB/DAY":"LAB".equals(s)?"SCC/HR":"SM3/DAY";
        String pressure="METRIC".equals(s)?"BARSA":"FIELD".equals(s)?"PSIA":"ATMA";
        return Set.of("METRIC","FIELD","LAB","PVT-M").contains(s)&&length.equals(p.path("lengthUnit").asText())&&rate.equals(p.path("rateUnit").asText())&&pressure.equals(p.path("pressureUnit").asText());
    }
    public static boolean matchesInspection(JsonNode p,JsonNode inspection){
        if(!valid(p)||inspection==null)return false;
        var c=inspection.path("newWellContext");var m=inspection.path("scheduleMetadata");var wells=m.path("wells");var packages=inspection.path("packageFiles");
        if(!"eclipse-new-well-context/1".equals(c.path("schemaVersion").asText())||!p.path("sourceFile").equals(c.path("sourceFile"))||!p.path("insertionLine").equals(c.path("insertionLine"))
                ||!packages.isArray()||packages.size()!=1||!p.path("sourceFile").equals(packages.path(0).path("relativePath"))||!p.path("sourceSha256").equals(packages.path(0).path("sha256"))
                ||inspection.path("phases").size()!=1||!"WATER".equals(inspection.path("phases").path(0).asText())||!unitsMatch(p,inspection.path("unitSystem").asText())
                ||!wells.isArray()||wells.isEmpty()||!m.path("groups").isArray()||!m.path("groups").isEmpty()||c.path("maxConnections").asInt()<1)return false;
        for(String axis:List.of("nx","ny","nz"))if(!p.path(axis).equals(inspection.path("dimensions").path(axis)))return false;
        var groups=new HashSet<String>();int members=0;
        for(JsonNode well:wells){
            String group=well.path("group").asText();
            if(group.isEmpty()||well.path("name").asText().equalsIgnoreCase(p.path("well").asText())||!p.path("sourceFile").equals(well.path("sourceFile"))
                    ||!well.path("lineNumber").isIntegralNumber()||well.path("lineNumber").asInt()>=p.path("insertionLine").asInt())return false;
            groups.add(group);if(group.equals(p.path("group").asText()))members++;
        }
        if(members==0||wells.size()+1>c.path("maxWells").asInt()||members+1>c.path("maxWellsPerGroup").asInt()||groups.size()>c.path("maxGroups").asInt())return false;
        for(JsonNode row:m.path("records"))if(!p.path("sourceFile").equals(row.path("sourceFile"))||!row.path("lineNumber").isIntegralNumber()||row.path("lineNumber").asInt()>=p.path("insertionLine").asInt()
                ||row.path("values").isEmpty()||row.path("values").path(0).asText().contains("*")||row.path("values").path(0).asText().contains("?"))return false;
        for(JsonNode row:m.path("completions")){
            int i=coordinate(row,"i"),j=coordinate(row,"j"),first=coordinate(row,"k1"),last=coordinate(row,"k2");
            if(i<=0||j<=0||first<=0||last<first||i>p.path("nx").asInt()||j>p.path("ny").asInt()||last>p.path("nz").asInt()
                    ||i==p.path("i").asInt()&&j==p.path("j").asInt()&&p.path("k").asInt()>=first&&p.path("k").asInt()<=last)return false;
        }
        return true;
    }
    private static int coordinate(JsonNode row,String key){try{return Integer.parseInt(row.path(key).asText());}catch(NumberFormatException e){return -1;}}
    public static boolean matchesBaseline(JsonNode p,JsonNode result,List<SoftwareIntegrationArtifactEntity> artifacts){
        if(!valid(p)||result==null||!"eclipse-summary-result/1".equals(result.path("schemaVersion").asText())||!"VALID_FULL".equals(result.path("resultContract").asText())||artifacts==null)return false;
        for(String axis:List.of("nx","ny","nz"))if(!p.path(axis).equals(result.path("grid").path(axis)))return false;
        String file=p.path("gridFile").asText(),sha=p.path("gridArtifactSha256").asText();
        if(!file.equals(result.path("grid").path("fileName").asText()))return false;
        long count=(long)p.path("nx").asInt()*p.path("ny").asInt()*p.path("nz").asInt(),indexed=0;
        for(JsonNode item:result.path("fieldIndex").path("files"))if(file.equals(item.path("name").asText()))for(JsonNode field:item.path("fields"))
            if("ACTNUM".equals(field.path("keyword").asText())&&"INTE".equals(field.path("dataType").asText())&&field.path("count").asLong()==count&&field.path("timeStep").isMissingNode())indexed++;
        boolean output=false;for(JsonNode item:result.path("outputFiles"))if(file.equals(item.path("name").asText())&&sha.equals(item.path("sha256").asText()))output=true;
        return indexed==1&&output&&artifacts.stream().filter(a->("eclipse-output-"+file).equals(a.getArtifactName())&&sha.equals(a.getSha256())&&"application/octet-stream".equals(a.getContentType())
                &&(a.getExpiresAt()==null||LocalDateTime.now().isBefore(a.getExpiresAt()))).count()==1;
    }
}
