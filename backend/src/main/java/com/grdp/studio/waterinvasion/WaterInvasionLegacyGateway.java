package com.grdp.studio.waterinvasion;

import com.grdp.studio.config.OriginalPlatformProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import static com.grdp.studio.waterinvasion.WaterInvasionModels.*;
import static com.grdp.studio.waterinvasion.WaterInvasionResultMapper.*;

/** 单井水侵专用旧平台适配器；只转发当前用户会话，不使用全局 Cookie 或保存凭据。 */
@Component
public class WaterInvasionLegacyGateway {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final String base,authBase,cookieName;
    public WaterInvasionLegacyGateway(OriginalPlatformProperties properties,ObjectMapper json,
        @Value("${grdp.water-invasion.auth-base-url:http://127.0.0.1:9919}") String authBase,
        @Value("${grdp.water-invasion.session-cookie-name:}") String cookieName) {
        this.base=properties.baseUrl().replaceAll("/$",""); this.json=json; this.authBase=authBase.replaceAll("/$",""); this.cookieName=cookieName;
    }
    public String credentials(String cookie) {
        var allowed=cookieName.isBlank()?Set.of("ahksoil_identity_session","grdp_identity_session"):Set.of(cookieName);
        String result=Arrays.stream(Objects.toString(cookie,"").split(";")).map(String::trim)
            .filter(s->s.contains("=") && allowed.contains(s.substring(0,s.indexOf('='))) && s.length()>s.indexOf('=')+1)
            .reduce((a,b)->a+"; "+b).orElse("");
        if(result.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录平台");
        return result;
    }
    public String authorize(long projectId,String cookie) {
        var session=getAbsolute(authBase+"/services/ory/kratos/sessions/whoami",cookie);
        String actor=Objects.toString(map(session.get("identity")).get("id"),"");
        if(!Boolean.TRUE.equals(session.get("active")) || actor.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"登录会话已失效");
        // 身份验证不替代项目权限：沿用原平台的项目访问校验，不信任前端传来的用户 ID。
        getAbsolute(base+"/api/projects/"+projectId+"?projectId="+projectId,cookie);
        return actor;
    }
    public Map<String,Object> result(Scope scope,String cookie) {
        return getAbsolute(base+"/api/projectanalysis/waterinvasionanalysis/"+scope.projectId()+"/"+scope.gasReservoirId()+"/well/"+encode(scope.wellName()),cookie);
    }
    public Map<String,Object> resultIfPresent(Scope scope,String cookie) {
        try{return result(scope,cookie);} catch(ResponseStatusException e){if(e.getStatusCode().value()==404)return null;throw e;}
    }
    public CompletableFuture<Void> start(Start s,String cookie) {
        Map<String,Object> body=Map.of("projectId",s.projectId(),"gasReservoirId",s.gasReservoirId(),"analysisType",1,"wellNames",List.of(s.wellName()),
            "isUseActualStaticPressure",s.isUseActualStaticPressure(),"waterGasRatioLimit",s.waterGasRatioLimit());
        var request=request(base+"/api/projectanalysis/waterinvasionanalysis",cookie,Duration.ofMinutes(10))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return http.sendAsync(request,HttpResponse.BodyHandlers.ofString()).thenAccept(response->{check(response);});
    }
    public List<Map<String,Object>> logs(long startMillis,String cookie) {
        var result=getAbsolute(base+"/api/common/notify/logs?start_time="+startMillis+"&keyword="+encode("水侵动态分析")+"&order=asc&page=1&page_size=2000",cookie);
        if(!(result.get("logs") instanceof List<?>)) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"旧平台未返回计算日志，无法确认本次计算完成");
        var rows=objects(result.get("logs"));
        if(rows.size()>=2000) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"本次计算日志数量超出安全读取范围，未保存未经确认的结果");
        return rows;
    }
    /** 有完整参数和两组生产输入的重算接口，{} 仅表示 HTTP 返回，仍须核对完成通知和结果。 */
    public CompletableFuture<Void> calculate(String wellName,Map<String,Object> payload,String cookie) {
        var request=request(base+"/api/projectanalysis/waterinvasionanalysis/"+encode(wellName)+"/calc",cookie,Duration.ofMinutes(10))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        return http.sendAsync(request,HttpResponse.BodyHandlers.ofString()).thenAccept(this::check);
    }
    private Map<String,Object> getAbsolute(String url,String cookie) {
        try {
            var response=http.send(request(url,cookie,Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofString()); check(response);
            return json.readValue(response.body(),Map.class);
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"后台任务已中断");}
        catch(ResponseStatusException e){throw e;}
        catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"旧平台接口不可达或返回格式无效");}
    }
    private HttpRequest.Builder request(String url,String cookie,Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Accept","application/json").header("Cookie",cookie)
            .header("Process-Env","prod").header("Origin",base);
    }
    private void check(HttpResponse<String> response) {
        int status=response.statusCode();
        if(status==401 || status==403) throw new ResponseStatusException(HttpStatus.valueOf(status),"原平台会话失效或没有项目访问权限");
        if(status==404) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"旧平台尚无该井水侵结果");
        if(status<200 || status>=300 || response.body().stripLeading().startsWith("<"))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"旧平台接口返回异常，HTTP "+status);
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}

    /** 必须找到本井的日志或与本井日志同 pin 的终态；不接受任意全局“完成”。 */
    public static boolean completed(List<Map<String,Object>> logs,Scope scope,long startMillis) {
        Pattern well=Pattern.compile("(?<![\\p{L}\\p{N}_-])"+Pattern.quote(scope.wellName())+"(?![\\p{L}\\p{N}_-])");
        Pattern terminal=Pattern.compile("\\[\\s*水侵动态分析-水体活跃性\\s*]\\s*[:：]\\s*完成");
        var relevant=new ArrayList<Map<String,Object>>(); var pins=new HashSet<String>();
        for(var log:logs) {
            var fields=map(log.get("fields"));
            String module=Objects.toString(log.getOrDefault("module",fields.get("module")),"");
            if(!module.equals("projectanalysis.waterinvasionanalysis"))continue;
            long timestamp=timestamp(log.get("timestamp"));
            if(timestamp<startMillis)continue;
            if(!matchesScope(fields,scope))continue;
            relevant.add(log);
            String message=Objects.toString(log.get("message"),"");
            String pin=Objects.toString(log.getOrDefault("pin",fields.get("pin")),"");
            if(well.matcher(message).find() && !pin.isBlank())pins.add(pin);
        }
        for(var log:relevant) {
            String message=Objects.toString(log.get("message"),"");
            String pin=Objects.toString(log.getOrDefault("pin",map(log.get("fields")).get("pin")),"");
            if(terminal.matcher(message).find() && (well.matcher(message).find() || !pin.isBlank() && pins.contains(pin)))return true;
        }
        return false;
    }
    private static boolean matchesScope(Map<String,Object> fields,Scope scope) {
        for(String key:List.of("projectId","project_id")) if(fields.containsKey(key) && !Objects.equals(number(fields.get(key)),(double)scope.projectId()))return false;
        for(String key:List.of("gasReservoirId","gas_reservoir_id")) if(fields.containsKey(key) && !Objects.equals(number(fields.get(key)),(double)scope.gasReservoirId()))return false;
        return true;
    }
    private static long timestamp(Object value) {
        if(value instanceof Number n)return n.longValue();
        try{return Instant.parse(Objects.toString(value,"")).toEpochMilli();}catch(Exception ignored){return -1;}
    }
}
