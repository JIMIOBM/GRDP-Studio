package com.grdp.studio.softwareintegration.support;

import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationArtifactEntity;
import tools.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One initial active connection added to an existing OPEN/WRAT water producer. */
public final class EclipseNewCompletionParameters {
    public static final String SCHEMA="eclipse-new-completion-parameters/1";
    private static final Set<String> FIELDS=Set.of("schemaVersion","baselineRunId","sourceFile","sourceSha256","gridFile","gridArtifactSha256","nx","ny","nz","insertionLine","well","i","j","k","diameter","kh","skin","lengthUnit");
    private EclipseNewCompletionParameters() {}
    public static boolean valid(JsonNode p) {
        if(p==null||!p.isObject())return false;
        var names=new HashSet<String>();p.properties().forEach(f->names.add(f.getKey()));
        if(!names.equals(FIELDS)||!SCHEMA.equals(p.path("schemaVersion").asText())||!p.path("baselineRunId").isIntegralNumber()||!p.path("baselineRunId").canConvertToLong()||p.path("baselineRunId").asLong()<=0)return false;
        for(String key:List.of("sourceFile","sourceSha256","gridFile","gridArtifactSha256","well","lengthUnit"))if(!p.path(key).isTextual())return false;
        if(!p.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$")||!p.path("gridFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.EGRID$")
                ||!p.path("well").asText().matches("^[A-Za-z][A-Za-z0-9_-]{0,7}$"))return false;
        for(String key:List.of("sourceSha256","gridArtifactSha256"))if(!p.path(key).asText().matches("^[0-9a-f]{64}$"))return false;
        for(String key:List.of("nx","ny","nz","insertionLine","i","j","k"))if(!p.path(key).isIntegralNumber()||!p.path(key).canConvertToInt()||p.path(key).asInt()<=0||p.path(key).asInt()>2_000_000)return false;
        if((long)p.path("nx").asInt()*p.path("ny").asInt()*p.path("nz").asInt()>2_000_000||p.path("i").asInt()>p.path("nx").asInt()||p.path("j").asInt()>p.path("ny").asInt()||p.path("k").asInt()>p.path("nz").asInt())return false;
        for(String key:List.of("diameter","kh","skin"))if(!p.path(key).isNumber()||!Double.isFinite(p.path(key).asDouble()))return false;
        String length=p.path("lengthUnit").asText();double diameter=p.path("diameter").asDouble();
        return Set.of("m","ft","cm").contains(length)&&diameter>0&&diameter*("ft".equals(length)?0.3048:"cm".equals(length)?0.01:1)<=2
                &&p.path("kh").asDouble()>0&&p.path("kh").asDouble()<=1_000_000_000&&p.path("skin").asDouble()>=0&&p.path("skin").asDouble()<=100;
    }
    public static boolean matchesInspection(JsonNode p,JsonNode inspection) {
        if(!valid(p)||inspection==null)return false;
        var context=inspection.path("newWellContext");var metadata=inspection.path("scheduleMetadata");var packages=inspection.path("packageFiles");
        if(!"eclipse-new-well-context/1".equals(context.path("schemaVersion").asText())||!p.path("sourceFile").equals(context.path("sourceFile"))||!p.path("insertionLine").equals(context.path("insertionLine"))
                ||!packages.isArray()||packages.size()!=1||!p.path("sourceFile").equals(packages.path(0).path("relativePath"))||!p.path("sourceSha256").equals(packages.path(0).path("sha256"))
                ||inspection.path("phases").size()!=1||!"WATER".equals(inspection.path("phases").path(0).asText())||!metadata.path("groups").isArray()||!metadata.path("groups").isEmpty()
                ||!metadata.path("wells").isArray()||!metadata.path("records").isArray()||!metadata.path("completions").isArray())return false;
        String system=inspection.path("unitSystem").asText(),length="FIELD".equals(system)?"ft":"LAB".equals(system)?"cm":"m";
        if(!Set.of("METRIC","FIELD","LAB","PVT-M").contains(system)||!length.equals(p.path("lengthUnit").asText()))return false;
        for(String key:List.of("nx","ny","nz"))if(!p.path(key).equals(inspection.path("dimensions").path(key)))return false;
        String well=p.path("well").asText();int found=0,controls=0;
        for(var row:metadata.path("wells")){
            if(!boundBefore(row,p))return false;
            if(well.equals(row.path("name").asText()))found++;
        }
        if(found!=1)return false;
        for(var row:metadata.path("records")){
            var values=row.path("values");
            if(!boundBefore(row,p)||!values.isArray()||values.isEmpty()||values.get(0).asText().contains("*")||values.get(0).asText().contains("?"))return false;
            if("WCONPROD".equals(row.path("keyword").asText())&&well.equals(values.get(0).asText().trim())){
                if(values.size()<5||!"OPEN".equals(values.get(1).asText())||!"WRAT".equals(values.get(2).asText()))return false;
                try{double rate=Double.parseDouble(values.get(4).asText());if(!Double.isFinite(rate)||rate<=0)return false;}catch(NumberFormatException exception){return false;}
                controls++;
            }
        }
        if(controls!=1)return false;
        long connections=0;
        for(var row:metadata.path("completions")){
            int i=coordinate(row,"i"),j=coordinate(row,"j"),first=coordinate(row,"k1"),last=coordinate(row,"k2");
            if(!boundBefore(row,p)||!"COMPDAT".equals(row.path("keyword").asText())||i<=0||j<=0||first<=0||last<first
                    ||i>p.path("nx").asInt()||j>p.path("ny").asInt()||last>p.path("nz").asInt()
                    ||i==p.path("i").asInt()&&j==p.path("j").asInt()&&p.path("k").asInt()>=first&&p.path("k").asInt()<=last)return false;
            if(well.equals(row.path("well").asText().trim()))connections+=(long)last-first+1;
        }
        return connections>0&&connections+1<=context.path("maxConnections").asInt();
    }
    private static boolean boundBefore(JsonNode row,JsonNode p){return p.path("sourceFile").equals(row.path("sourceFile"))&&row.path("lineNumber").isIntegralNumber()&&row.path("lineNumber").asInt()>0&&row.path("lineNumber").asInt()<p.path("insertionLine").asInt();}
    private static int coordinate(JsonNode row,String key){try{String value=row.path(key).asText();return value.matches("^[1-9][0-9]*$")?Integer.parseInt(value):-1;}catch(NumberFormatException exception){return -1;}}
    public static boolean matchesBaseline(JsonNode p,JsonNode result,List<SoftwareIntegrationArtifactEntity> artifacts){
        if(!valid(p)||result==null||!"eclipse-summary-result/1".equals(result.path("schemaVersion").asText())||!"VALID_FULL".equals(result.path("resultContract").asText())||artifacts==null)return false;
        for(String axis:List.of("nx","ny","nz"))if(!p.path(axis).equals(result.path("grid").path(axis)))return false;
        String file=p.path("gridFile").asText(),sha=p.path("gridArtifactSha256").asText();
        if(!file.equals(result.path("grid").path("fileName").asText()))return false;
        long count=(long)p.path("nx").asInt()*p.path("ny").asInt()*p.path("nz").asInt(),indexed=0;
        for(var item:result.path("fieldIndex").path("files"))if(file.equals(item.path("name").asText()))for(var field:item.path("fields"))
            if("ACTNUM".equals(field.path("keyword").asText())&&"INTE".equals(field.path("dataType").asText())&&field.path("count").asLong()==count&&field.path("timeStep").isMissingNode())indexed++;
        boolean output=false;for(var item:result.path("outputFiles"))if(file.equals(item.path("name").asText())&&sha.equals(item.path("sha256").asText()))output=true;
        return indexed==1&&output&&artifacts.stream().filter(a->("eclipse-output-"+file).equals(a.getArtifactName())&&sha.equals(a.getSha256())&&"application/octet-stream".equals(a.getContentType())
                &&(a.getExpiresAt()==null||LocalDateTime.now().isBefore(a.getExpiresAt()))).count()==1;
    }
}
