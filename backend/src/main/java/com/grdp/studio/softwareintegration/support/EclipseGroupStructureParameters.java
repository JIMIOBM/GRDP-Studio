package com.grdp.studio.softwareintegration.support;
import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class EclipseGroupStructureParameters {
    public static final String SCHEMA="eclipse-group-structure-parameters/1";
    private static final Set<String> FIELDS=Set.of("schemaVersion","baselineRunId","phase","parentGroup","objectName","keyword","sourceFile","lineNumber","unit","originalValue","targetValue","members");
    private static final Set<String> CONTROL_FIELDS=Set.of("parentGroup","objectName","keyword","sourceFile","lineNumber","unit","originalValue","parentTarget","leaves");
    private static final Pattern NAME=Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$");
    private EclipseGroupStructureParameters() {}
    private static boolean fields(JsonNode n,Set<String> keys){return n.isObject() && n.size()==keys.size() && n.properties().stream().allMatch(p->keys.contains(p.getKey()));}
    private static boolean name(JsonNode n){return n.isTextual() && NAME.matcher(n.asText()).matches();}
    private static boolean value(JsonNode n){return n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble()>0 && n.asDouble()<=100_000_000;}
    private static boolean line(JsonNode n){return n.isIntegralNumber() && n.canConvertToInt() && n.asInt()>0;}
    private static String unit(String system,String keyword){return keyword.equals("GCONPROD")?system.equals("FIELD")?"STB/day":"sm3/day":"dimensionless";}
    public static boolean valid(JsonNode p){
        if(p==null || !fields(p,FIELDS) || !SCHEMA.equals(p.path("schemaVersion").asText()) || !"INITIAL".equals(p.path("phase").asText())
            || !p.path("baselineRunId").isIntegralNumber() || !p.path("baselineRunId").canConvertToLong() || p.path("baselineRunId").asLong()<=0
            || !name(p.path("parentGroup")) || "FIELD".equals(p.path("parentGroup").asText()) || !name(p.path("objectName")) || !line(p.path("lineNumber"))
            || !p.path("sourceFile").isTextual() || !Pattern.matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]",p.path("sourceFile").asText())
            || !Set.of("GCONPROD","WGRUPCON","GEFAC").contains(p.path("keyword").asText()) || !value(p.path("originalValue")) || !value(p.path("targetValue"))
            || p.path("originalValue").asDouble()==p.path("targetValue").asDouble() || !p.path("members").isArray() || p.path("members").size()<4 || p.path("members").size()>1000)return false;
        String keyword=p.path("keyword").asText();
        if(keyword.equals("GCONPROD") ? !p.path("parentGroup").equals(p.path("objectName")) || !Set.of("STB/day","sm3/day").contains(p.path("unit").asText())
            : !"dimensionless".equals(p.path("unit").asText()) || keyword.equals("GEFAC") && (p.path("originalValue").asDouble()>1 || p.path("targetValue").asDouble()>1))return false;
        var names=new HashSet<String>();for(var m:p.path("members"))if(!name(m) || !names.add(m.asText()))return false;return true;
    }
    public static boolean matchesInspection(JsonNode p,JsonNode i){
        if(!valid(p) || i==null || !"eclipse-data-inspection/12".equals(i.path("schemaVersion").asText()) || !validControls(i))return false;
        int matched=0;
        for(var c:i.path("groupStructureControls")){
            if(!c.path("keyword").equals(p.path("keyword")) || !c.path("objectName").equals(p.path("objectName")))continue;
            for(String key:new String[]{"parentGroup","sourceFile","lineNumber","unit"})if(!c.path(key).equals(p.path(key)))return false;
            if(c.path("originalValue").asDouble()!=p.path("originalValue").asDouble())return false;
            int index=0;for(var leaf:c.path("leaves"))for(var member:leaf.path("members")){
                if(index>=p.path("members").size() || !member.path("well").equals(p.path("members").get(index++)))return false;
            }
            if(index!=p.path("members").size())return false;matched++;
        }return matched==1;
    }
    public static boolean validControls(JsonNode i){
        if(!Set.of("FIELD","METRIC").contains(i.path("unitSystem").asText()) || i.path("packageFiles").size()!=1 || !i.path("packageFiles").get(0).path("relativePath").equals(i.path("caseName")))return false;
        var controls=i.path("groupStructureControls");if(!controls.isArray() || controls.isEmpty() || controls.size()>1002)return false;
        var first=controls.get(0);var leaves=first.path("leaves");var parent=first.path("parentGroup");
        if(!name(parent) || "FIELD".equals(parent.asText()) || !leaves.isArray() || leaves.size()<2 || leaves.size()>500 || !value(first.path("parentTarget")))return false;
        var leafNames=new HashSet<String>();var wellNames=new HashSet<String>();var fixedGuides=new HashSet<String>();int nonDefaultEfficiency=0;
        for(var leaf:leaves){
            if(!fields(leaf,Set.of("group","efficiency","members")) || !name(leaf.path("group")) || leaf.path("group").equals(parent) || "FIELD".equals(leaf.path("group").asText()) || !leafNames.add(leaf.path("group").asText())
                || !value(leaf.path("efficiency")) || leaf.path("efficiency").asDouble()>1 || !leaf.path("members").isArray() || leaf.path("members").size()<2 || leaf.path("members").size()>1000)return false;
            if(leaf.path("efficiency").asDouble()!=1)nonDefaultEfficiency++;
            int groups=0;for(var g:i.path("scheduleMetadata").path("groups"))if(g.path("name").equals(leaf.path("group")) && g.path("parent").equals(parent))groups++;
            if(groups!=1)return false;
            int count=0;for(var w:i.path("scheduleMetadata").path("wells"))if(w.path("group").equals(leaf.path("group")))count++;
            if(count!=leaf.path("members").size())return false;
            for(var m:leaf.path("members")){
                if(!fields(m,Set.of("well","bhpLimit","oilRateLimit","guideRate")) || !name(m.path("well")) || !wellNames.add(m.path("well").asText()) || !value(m.path("bhpLimit")) || !value(m.path("oilRateLimit"))
                    || !m.path("guideRate").isNumber() || !Double.isFinite(m.path("guideRate").asDouble()) || m.path("guideRate").asDouble()<0 || m.path("guideRate").asDouble()>100_000_000)return false;
                if(m.path("guideRate").asDouble()>0)fixedGuides.add(m.path("well").asText());
                int found=0;for(var w:i.path("scheduleMetadata").path("wells"))if(w.path("name").equals(m.path("well")) && w.path("group").equals(leaf.path("group")))found++;
                if(found!=1)return false;
            }
        }
        if(wellNames.size()>1000 || nonDefaultEfficiency>1 || !fixedGuides.isEmpty() && (fixedGuides.size()!=wellNames.size() || nonDefaultEfficiency>0) || i.path("scheduleMetadata").path("groups").size()!=leaves.size())return false;
        for(var w:i.path("scheduleMetadata").path("wells"))if(w.path("group").equals(parent))return false;
        int parents=0,factors=0;var guideObjects=new HashSet<String>();var factorObjects=new HashSet<String>();var seen=new HashSet<String>();
        for(var c:controls){
            String keyword=c.path("keyword").asText(),object=c.path("objectName").asText();
            if(!fields(c,CONTROL_FIELDS) || !name(c.path("objectName")) || !Set.of("GCONPROD","WGRUPCON","GEFAC").contains(keyword) || !seen.add(keyword+":"+object) || !c.path("parentGroup").equals(parent)
                || !c.path("leaves").equals(leaves) || c.path("parentTarget").asDouble()!=first.path("parentTarget").asDouble() || !c.path("sourceFile").equals(i.path("caseName")) || !line(c.path("lineNumber"))
                || !unit(i.path("unitSystem").asText(),keyword).equals(c.path("unit").asText()) || !value(c.path("originalValue")))return false;
            for(var e:i.path("scheduleTimeline"))if(!e.path("sourceFile").equals(c.path("sourceFile")) || e.path("lineNumber").asInt()<=c.path("lineNumber").asInt())return false;
            if(keyword.equals("GCONPROD")){if(!c.path("objectName").equals(parent) || c.path("originalValue").asDouble()!=c.path("parentTarget").asDouble())return false;parents++;}
            else if(keyword.equals("WGRUPCON")){
                if(!fixedGuides.contains(object) || !guideObjects.add(object))return false;
                for(var leaf:leaves)for(var m:leaf.path("members"))if(m.path("well").equals(c.path("objectName")) && m.path("guideRate").asDouble()!=c.path("originalValue").asDouble())return false;
            }else{
                if(!leafNames.contains(object) || !factorObjects.add(object) || c.path("originalValue").asDouble()>1)return false;
                for(var leaf:leaves)if(leaf.path("group").equals(c.path("objectName")) && leaf.path("efficiency").asDouble()!=c.path("originalValue").asDouble())return false;factors++;
            }
        }
        return parents==1 && factors<=1 && guideObjects.equals(fixedGuides) && (nonDefaultEfficiency==0 || factors==1) && (guideObjects.isEmpty() || factors==0);
    }
}
