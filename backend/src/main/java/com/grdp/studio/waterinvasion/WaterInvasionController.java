package com.grdp.studio.waterinvasion;

import com.grdp.studio.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.grdp.studio.waterinvasion.WaterInvasionModels.*;

/** 单井水侵入口：计算/导入返回后台任务；列表、详情只读取新数据库结果。 */
@RestController
@RequestMapping("/water-invasion")
public class WaterInvasionController {
    private final WaterInvasionService service;
    private final WaterInvasionStorage storage;
    private final WaterInvasionLegacyGateway legacy;
    public WaterInvasionController(WaterInvasionService service,WaterInvasionStorage storage,WaterInvasionLegacyGateway legacy){this.service=service;this.storage=storage;this.legacy=legacy;}
    @PostMapping("/tasks") public ApiResponse<Task> start(@Valid @RequestBody Start request,@RequestHeader(value="Cookie",required=false)String rawCookie){return launch(request,rawCookie,false);}
    @PostMapping("/imports") public ApiResponse<Task> importResult(@Valid @RequestBody Start request,@RequestHeader(value="Cookie",required=false)String rawCookie){return launch(request,rawCookie,true);}
    @PostMapping("/tasks/{id}/reconcile") public ApiResponse<Task> reconcile(@PathVariable long id,@RequestBody Scope scope,@RequestHeader(value="Cookie",required=false)String rawCookie){String cookie=legacy.credentials(rawCookie);legacy.authorize(scope.projectId(),cookie);return ApiResponse.success(service.reconcile(id,scope,cookie));}
    private ApiResponse<Task> launch(Start request,String rawCookie,boolean importing){
        String cookie=legacy.credentials(rawCookie); String actor=legacy.authorize(request.projectId(),cookie);
        return ApiResponse.success(service.start(request,actor,cookie,importing));
    }
    @GetMapping("/records") public ApiResponse<List<Task>> list(@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName,@RequestHeader(value="Cookie",required=false)String rawCookie){
        authorize(projectId,rawCookie); return ApiResponse.success(storage.list(new Scope(projectId,gasReservoirId,wellName)));
    }
    @GetMapping("/tasks/{id}") public ApiResponse<Task> task(@PathVariable long id,@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName,@RequestHeader(value="Cookie",required=false)String rawCookie){
        authorize(projectId,rawCookie); return ApiResponse.success(storage.task(id,new Scope(projectId,gasReservoirId,wellName)));
    }
    @GetMapping("/records/{id}") public ApiResponse<Detail> detail(@PathVariable long id,@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName,@RequestHeader(value="Cookie",required=false)String rawCookie){
        authorize(projectId,rawCookie); return ApiResponse.success(storage.detail(id,new Scope(projectId,gasReservoirId,wellName)));
    }
    @DeleteMapping("/records/{id}") public ApiResponse<Void> delete(@PathVariable long id,@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName,@RequestHeader(value="Cookie",required=false)String rawCookie){
        authorize(projectId,rawCookie); storage.delete(id,new Scope(projectId,gasReservoirId,wellName));return ApiResponse.success(null);
    }
    private void authorize(long project,String cookie){legacy.authorize(project,legacy.credentials(cookie));}
}
