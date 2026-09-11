package com.grdp.studio.pipeline;

import com.grdp.studio.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static com.grdp.studio.pipeline.PipelineDtos.*;

@RestController
@RequestMapping("/pipeline-capacity")
public class PipelineController {
    private final PipelineStorage storage;
    public PipelineController(PipelineStorage storage) {this.storage=storage;}
    @GetMapping("/model")
    public ApiResponse<Detail> detail(@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName) {
        return ApiResponse.success(storage.detail(projectId,gasReservoirId,wellName));
    }
    @PatchMapping("/model/{scope}")
    public ApiResponse<Detail> saveSection(@PathVariable String scope,@Valid @RequestBody SectionSaveRequest request) {
        return ApiResponse.success(storage.saveSection(scope,request));
    }
}
