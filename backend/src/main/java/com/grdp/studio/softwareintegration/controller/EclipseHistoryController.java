package com.grdp.studio.softwareintegration.controller;
import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.softwareintegration.service.EclipseHistoryService;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.List;
@Validated @RestController @RequestMapping("/software-integration")
public class EclipseHistoryController {
    private final EclipseHistoryService service;
    public EclipseHistoryController(EclipseHistoryService service){this.service=service;}
    @PostMapping("/model-versions/{versionId}/eclipse-history-datasets")
    public ResponseEntity<ApiResponse<EclipseHistoryService.Dataset>> create(@PathVariable @Min(1)long versionId,@RequestBody JsonNode request){return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(201,"success",service.create(versionId,request)));}
    @GetMapping("/model-versions/{versionId}/eclipse-history-datasets")
    public ApiResponse<List<EclipseHistoryService.Summary>> list(@PathVariable @Min(1)long versionId){return ApiResponse.success(service.list(versionId));}
    @GetMapping("/eclipse-history-datasets/{id}")
    public ApiResponse<EclipseHistoryService.Dataset> get(@PathVariable @Min(1)long id){return ApiResponse.success(service.get(id));}
    @GetMapping("/eclipse-history-datasets/{id}/comparison")
    public ApiResponse<EclipseHistoryService.Evaluation> compare(@PathVariable @Min(1)long id,@RequestParam @Min(1)long runId){return ApiResponse.success(service.compare(id,runId));}
}
