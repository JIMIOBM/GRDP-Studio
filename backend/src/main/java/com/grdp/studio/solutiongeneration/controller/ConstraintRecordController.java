package com.grdp.studio.solutiongeneration.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.solutiongeneration.dto.ConstraintRecordSaveRequest;
import com.grdp.studio.solutiongeneration.service.ConstraintRecordStorageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/solution-generation")
public class ConstraintRecordController {
    private final ConstraintRecordStorageService storage;

    public ConstraintRecordController(ConstraintRecordStorageService storage) {
        this.storage = storage;
    }

    @PostMapping("/constraint-records/save")
    public ApiResponse<Map<String, Object>> save(@Valid @RequestBody ConstraintRecordSaveRequest request) {
        return ApiResponse.success(storage.save(request));
    }

    @PutMapping("/constraint-records/{id}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable long id,
            @Valid @RequestBody ConstraintRecordSaveRequest request) {
        return ApiResponse.success(storage.update(id, request));
    }

    @GetMapping("/constraint-records")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam String wellName) {
        return ApiResponse.success(storage.list(projectId, gasReservoirId, wellName));
    }

    @GetMapping("/constraint-records/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.success(storage.detail(id));
    }

    @DeleteMapping("/constraint-records/{id}")
    public ApiResponse<Void> delete(
            @PathVariable long id,
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam String wellName) {
        storage.delete(id, projectId, gasReservoirId, wellName);
        return ApiResponse.success();
    }
}
