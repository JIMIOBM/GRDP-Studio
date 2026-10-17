package com.grdp.studio.solutiongeneration.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.solutiongeneration.dto.SolutionRecordSaveRequest;
import com.grdp.studio.solutiongeneration.service.SolutionRecordStorageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/solution-generation")
public class SolutionRecordController {
    private final SolutionRecordStorageService storage;

    public SolutionRecordController(SolutionRecordStorageService storage) {
        this.storage = storage;
    }

    @PostMapping("/solution-records/save")
    public ApiResponse<Map<String, Object>> save(@Valid @RequestBody SolutionRecordSaveRequest request) {
        return ApiResponse.success(storage.save(request));
    }

    @PutMapping("/solution-records/{id}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable long id,
            @Valid @RequestBody SolutionRecordSaveRequest request) {
        return ApiResponse.success(storage.update(id, request));
    }

    @GetMapping("/solution-records")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam String wellName) {
        return ApiResponse.success(storage.list(projectId, gasReservoirId, wellName));
    }

    // Load one database snapshot for the whole project/reservoir; used to rebuild saved tree nodes.
    @GetMapping("/solution-records/by-scope")
    public ApiResponse<List<Map<String, Object>>> listByScope(
            @RequestParam long projectId,
            @RequestParam long gasReservoirId) {
        return ApiResponse.success(storage.listByScope(projectId, gasReservoirId));
    }

    @GetMapping("/solution-records/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.success(storage.detail(id));
    }

    @DeleteMapping("/solution-records/{id}")
    public ApiResponse<Void> delete(
            @PathVariable long id,
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam String wellName) {
        storage.delete(id, projectId, gasReservoirId, wellName);
        return ApiResponse.success();
    }
}