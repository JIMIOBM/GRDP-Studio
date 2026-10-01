package com.grdp.studio.storagewaterinvasion;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.waterinvasion.WaterInvasionLegacyGateway;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.grdp.studio.storagewaterinvasion.StorageWaterInvasionModels.*;

/** 库页面仅访问新后端。所有入口均验证用户会话、项目权限及库范围。 */
@RestController
@RequestMapping("/storage-water-invasion")
public class StorageWaterInvasionController {
    private final StorageWaterInvasionStorage storage;
    private final StorageWaterInvasionService service;
    private final WaterInvasionLegacyGateway legacy;
    public StorageWaterInvasionController(StorageWaterInvasionStorage storage,StorageWaterInvasionService service,WaterInvasionLegacyGateway legacy){this.storage=storage;this.service=service;this.legacy=legacy;}
    private String auth(long project,String raw){String cookie=legacy.credentials(raw);legacy.authorize(project,cookie);return cookie;}
    @GetMapping("/sources") public ApiResponse<List<Map<String,Object>>> sources(@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam long storageId,@RequestHeader(value="Cookie",required=false)String raw){auth(projectId,raw);return ApiResponse.success(storage.candidates(new Scope(projectId,gasReservoirId,storageId)));}
    @PostMapping("/preview") public ApiResponse<Preview> preview(@Valid @RequestBody Start request,@RequestHeader(value="Cookie",required=false)String raw){auth(request.projectId(),raw);return ApiResponse.success(storage.preview(request));}
    @PostMapping("/tasks") public ApiResponse<Task> start(@Valid @RequestBody Start request,@RequestHeader(value="Cookie",required=false)String raw){String cookie=legacy.credentials(raw);String actor=legacy.authorize(request.projectId(),cookie);return ApiResponse.success(service.start(request,actor,cookie));}
    @GetMapping("/records") public ApiResponse<List<Task>> records(@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam long storageId,@RequestHeader(value="Cookie",required=false)String raw){auth(projectId,raw);return ApiResponse.success(storage.list(new Scope(projectId,gasReservoirId,storageId)));}
    @GetMapping("/records/{id}") public ApiResponse<Detail> detail(@PathVariable long id,@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam long storageId,@RequestHeader(value="Cookie",required=false)String raw){auth(projectId,raw);return ApiResponse.success(storage.detail(id,new Scope(projectId,gasReservoirId,storageId)));}
    @PostMapping("/tasks/{id}/reconcile") public ApiResponse<Task> reconcile(@PathVariable long id,@Valid @RequestBody Scope scope,@RequestHeader(value="Cookie",required=false)String raw){String cookie=auth(scope.projectId(),raw);return ApiResponse.success(service.reconcile(id,scope,cookie));}
}
