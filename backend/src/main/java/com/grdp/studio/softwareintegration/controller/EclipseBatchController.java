package com.grdp.studio.softwareintegration.controller;
import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.softwareintegration.service.EclipseBatchService;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.List;
@Validated @RestController @RequestMapping("/software-integration")
public class EclipseBatchController {
    private final EclipseBatchService batches;
    public EclipseBatchController(EclipseBatchService batches){this.batches=batches;}
    @PostMapping("/model-versions/{versionId}/eclipse-batches")
    public ResponseEntity<ApiResponse<EclipseBatchService.Batch>> create(@PathVariable @Min(1) long versionId,@RequestBody JsonNode request){return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(201,"success",batches.create(versionId,request)));}
    @GetMapping("/model-versions/{versionId}/eclipse-batches")
    public ApiResponse<List<EclipseBatchService.Summary>> list(@PathVariable @Min(1) long versionId){return ApiResponse.success(batches.list(versionId));}
    @GetMapping("/eclipse-batches/{id}")
    public ApiResponse<EclipseBatchService.Batch> get(@PathVariable @Min(1) long id){return ApiResponse.success(batches.get(id));}
    @PostMapping("/eclipse-batches/{id}/cancel")
    public ApiResponse<EclipseBatchService.Batch> cancel(@PathVariable @Min(1) long id){return ApiResponse.success(batches.cancel(id));}
}
